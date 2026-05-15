package com.hebe.mcp

import com.hebe.config.HebeConfig
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("com.hebe.mcp.McpServer")

@Suppress("UnusedParameter")
suspend fun runMcpStdioServer(config: HebeConfig) {
    logger.info("Starting Hebe MCP Server (stdio mode)")

    val server = createHebeMcpServer()

    val transport =
        StdioServerTransport(
            System.`in`.asSource().buffered(),
            System.out.asSink().buffered(),
        )

    val session = server.createSession(transport)
    session.onClose {
        logger.info("MCP session closed")
    }
    kotlinx.coroutines.Job().let { job ->
        session.onClose { job.complete() }
        job.join()
    }
}

fun main(args: Array<String>) {
    logger.info("Starting Hebe MCP Server (stdio mode)")

    val server = createHebeMcpServer()

    val transport =
        StdioServerTransport(
            System.`in`.asSource().buffered(),
            System.out.asSink().buffered(),
        )

    runBlocking {
        val session = server.createSession(transport)
        session.onClose {
            logger.info("MCP session closed")
        }
        kotlinx.coroutines.Job().let { job ->
            session.onClose { job.complete() }
            job.join()
        }
    }
}

fun createHebeMcpServer(): Server =
    Server(
        serverInfo = Implementation(name = "hebe", version = "1.0.0"),
        options =
            ServerOptions(
                capabilities =
                    ServerCapabilities(
                        tools = ServerCapabilities.Tools(listChanged = false),
                    ),
            ),
    )

fun Server.addHelloWorldTool() {
    addTool(
        name = "hello_world",
        description = "A simple hello world tool for testing MCP integration",
        inputSchema =
            ToolSchema(
                properties =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "name",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Name to greet")
                                    },
                                )
                            },
                        )
                    },
                required = emptyList(),
            ),
    ) { request ->
        val name =
            request.arguments
                ?.get("name")
                ?.jsonPrimitive
                ?.content
                ?: "World"

        CallToolResult(
            content = listOf(TextContent(text = "Hello, $name!")),
            isError = false,
        )
    }
}
