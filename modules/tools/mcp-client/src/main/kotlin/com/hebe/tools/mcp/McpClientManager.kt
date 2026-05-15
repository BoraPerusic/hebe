package com.hebe.tools.mcp

import com.hebe.api.RiskLevel
import com.hebe.api.SecretLookup
import com.hebe.config.McpClientServerConfig
import com.hebe.tools.dispatch.ToolRegistry
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.mcpClient
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import java.util.concurrent.ConcurrentHashMap
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import org.slf4j.LoggerFactory

class McpClientManager(
    private val registry: ToolRegistry,
    private val secretLookup: SecretLookup,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val connectedClients = ConcurrentHashMap<String, Client>()
    private val serverConfigs = ConcurrentHashMap<String, McpClientServerConfig>()
    private val toolFilter = McpToolFilter()

    @Suppress("TooGenericExceptionCaught")
    suspend fun connect(serverConfigs: List<McpClientServerConfig>) {
        for (config in serverConfigs) {
            try {
                connectServer(config)
            } catch (e: RuntimeException) {
                logger.error("Failed to connect to MCP server '{}': {}", config.name, e.message)
                throw e
            }
        }
    }

    private suspend fun connectServer(config: McpClientServerConfig) {
        logger.info("Connecting to MCP server '{}' via {}...", config.name, config.transport)

        val client =
            when (config.transport) {
                "stdio" -> connectStdio(config)
                else -> throw IllegalArgumentException("Unsupported transport: ${config.transport}")
            }

        connectedClients[config.name] = client
        serverConfigs[config.name] = config

        val toolsResult = client.listTools()
        logger.info("Server '{}' provides {} tools", config.name, toolsResult.tools.size)

        for (toolInfo in toolsResult.tools) {
            val toolName = "mcp_${config.name}_${toolInfo.name}"
            if (registry.get(toolName) != null) {
                logger.warn("Tool '{}' already exists, skipping (hebe wins)", toolName)
                continue
            }

            val remoteTool =
                RemoteTool(
                    name = toolName,
                    description = toolInfo.description ?: "",
                    inputSchema = toolInfo.inputSchema,
                    originalName = toolInfo.name,
                    mcpClient = client,
                    risk = RiskLevel.Medium,
                )
            registry.register(remoteTool)
            logger.debug("Registered remote tool: {}", toolName)
        }
    }

    fun toolsForMessage(
        serverName: String,
        userMessage: String,
    ): List<String> {
        val config = serverConfigs[serverName] ?: return emptyList()
        val allTools =
            registry
                .list()
                .filter { it.spec.name.startsWith("mcp_${serverName}_") }
                .map { it.spec.name }
        return toolFilter.applicableTools(config, allTools, userMessage)
    }

    private suspend fun connectStdio(config: McpClientServerConfig): Client {
        val secrets = buildEnvWithSecrets(config.envSecrets)
        val processBuilder =
            ProcessBuilder(config.command)
                .redirectErrorStream(true)

        processBuilder.environment().clear()
        processBuilder.environment().putAll(secrets)

        val process = processBuilder.start()

        val transport =
            StdioClientTransport(
                input = process.inputStream.asSource().buffered(),
                output = process.outputStream.asSink().buffered(),
            )

        return mcpClient(
            clientInfo = Implementation(name = "hebe-mcp-client", version = "1.0.0"),
            transport = transport,
        )
    }

    internal fun buildEnvWithSecrets(
        envSecrets: Map<String, String>,
        systemEnv: Map<String, String> = System.getenv(),
    ): Map<String, String> {
        val env =
            systemEnv.filterKeys { envVar ->
                SENSITIVE_ENV_KEYS.none { sensitiveKey -> envVar.contains(sensitiveKey) }
            }
        val withSecrets =
            envSecrets.mapValues { (_, secretName) ->
                secretLookup.secret(secretName)
                    ?: error("Secret not found: $secretName")
            }
        return env + withSecrets
    }

    suspend fun disconnect(serverName: String) {
        val client = connectedClients.remove(serverName)
        if (client != null) {
            logger.info("Disconnecting MCP server '{}'", serverName)
            client.close()
            registry
                .list()
                .filter { it.spec.name.startsWith("mcp_${serverName}_") }
                .forEach { registry.unregister(it.spec.name) }
        }
    }

    suspend fun disconnectAll() {
        connectedClients.keys.toList().forEach { serverName ->
            disconnect(serverName)
        }
    }

    private companion object {
        private val SENSITIVE_ENV_KEYS =
            setOf(
                "API_KEY",
                "TOKEN",
                "SECRET",
                "PASSWORD",
                "CREDENTIAL",
                "AUTH",
            )
    }
}
