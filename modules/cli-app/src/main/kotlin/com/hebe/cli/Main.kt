@file:Suppress(
    "TooGenericExceptionCaught",
    "MagicNumber",
    "LongMethod",
    "EmptyFunctionBlock",
    "MaxLineLength",
)

package com.hebe.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.hebe.security.estop.EstopIpc
import com.hebe.security.receipts.ReceiptVerifier
import com.hebe.security.receipts.VerifyResult
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Paths
import java.util.Base64
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) {
    HebeCLI().main(args)
}

class HebeCLI : CliktCommand(name = "hebe") {
    init {
        subcommands(
            RunCommand(),
            McpServeCommand(),
            PluginInstallCommand(),
            PluginListCommand(),
            PluginRemoveCommand(),
            DoctorCommand(),
            ServiceInstallCommand(),
            ServiceStartCommand(),
            ServiceStopCommand(),
            ServiceUninstallCommand(),
            StatusCommand(),
            CompletionBashCommand(),
            CompletionZshCommand(),
            CompletionFishCommand(),
            OnboardCommand(),
            EstopCommand(),
            MemoryShowCommand(),
        )
    }

    override fun run() {
        echo("Hebe v1.0.0")
        echo("Use --help for available commands")
    }
}

class RunCommand : CliktCommand(name = "run") {
    override fun run() {
        echo("Not yet implemented: hebe run")
    }
}

class McpServeCommand : CliktCommand(name = "mcp serve") {
    override fun run() {
        echo("Not yet implemented: hebe mcp serve")
    }
}

class PluginInstallCommand : CliktCommand(name = "plugin install") {
    private val refOrPath by argument(help = "OCI reference (e.g. ghcr.io/user/hello-plugin:0.1.0) or local path to .zip file")
    private val unsigned by option("--unsigned", help = "Skip signature verification (for local/sideloaded plugins)").flag()
    private val configPath =
        java.nio.file.Path
            .of(System.getProperty("user.home"), ".hebe", "config.toml")
    private val hebeConfig: com.hebe.config.HebeConfig by lazy {
        if (java.nio.file.Files
                .exists(configPath)
        ) {
            com.hebe.config.ConfigLoader().load(configPath).let { result ->
                when (result) {
                    is com.hebe.config.ConfigResult.Ok -> result.value
                    is com.hebe.config.ConfigResult.Error -> {
                        System.err.println("Warning: failed to load config, using defaults")
                        com.hebe.config.HebeConfig
                            .default()
                    }
                }
            }
        } else {
            com.hebe.config.HebeConfig
                .default()
        }
    }
    private val pluginsDir =
        java.nio.file.Path
            .of(System.getProperty("user.home"), ".hebe", "plugins")
    private val settingsStore: com.hebe.config.SettingsStore =
        com.hebe.config.defaultSettingsStore()

    override fun run() {
        val input = refOrPath
        if (input.isBlank()) {
            echo("Error: plugin reference or path required")
            return
        }
        val isFile =
            java.nio.file.Files
                .exists(
                    java.nio.file.Path
                        .of(input),
                ) &&
                java.nio.file.Path
                    .of(input)
                    .toFile()
                    .isFile
        val verifier =
            com.hebe.plugins.signature.SignatureVerifier(
                signatureMode = hebeConfig.security.pluginSignatureMode,
                trustedPublisherKeys = hebeConfig.plugins.publisherKeys,
                log = org.slf4j.LoggerFactory.getLogger("plugin-install"),
            )
        if (isFile) {
            val path =
                java.nio.file.Path
                    .of(input)
            echo("Installing plugin from local file: $path")
            runBlocking {
                val sideloadFlow =
                    com.hebe.plugins.install.SideloadFlow(
                        signatureVerifier = verifier,
                        pluginsDir = pluginsDir,
                        log = org.slf4j.LoggerFactory.getLogger("plugin-install"),
                    )
                val result = sideloadFlow.sideload(path, unsigned)
                when (result) {
                    is com.hebe.plugins.install.InstallResult.Ok -> {
                        runBlocking { settingsStore.setInstalledPlugin(result.name, result.version, source = "sideload") }
                        echo("Installed: ${result.name}")
                        echo("Location: ${result.extractDir}")
                    }
                    is com.hebe.plugins.install.InstallResult.Error -> {
                        echo("Error: ${result.message}")
                        throw com.github.ajalt.clikt.core
                            .Abort()
                    }
                }
            }
        } else {
            val registryHost = hebeConfig.plugins.registry.takeIf { it.isNotBlank() } ?: "ghcr.io"
            val fullRef = if (input.contains("/")) input else "$registryHost/$input"
            echo("Installing plugin from: $fullRef")
            runBlocking {
                val secretStore =
                    object : com.hebe.config.SecretStoreProvider {
                        override suspend fun get(key: String): ByteArray? = null

                        override suspend fun set(
                            key: String,
                            value: ByteArray,
                        ) {}

                        override suspend fun delete(key: String): Boolean = false

                        override suspend fun list(): List<String> = emptyList()
                    }
                val ociClient =
                    com.hebe.plugins.oci.OciClient(
                        registry = registryHost,
                        secretStore = secretStore,
                        log = org.slf4j.LoggerFactory.getLogger("plugin-install"),
                    )
                val installFlow =
                    com.hebe.plugins.install.InstallFlow(
                        ociClient = ociClient,
                        signatureVerifier = verifier,
                        pluginsDir = pluginsDir,
                        log = org.slf4j.LoggerFactory.getLogger("plugin-install"),
                    )
                val result = installFlow.install(fullRef)
                when (result) {
                    is com.hebe.plugins.install.InstallResult.Ok -> {
                        runBlocking { settingsStore.setInstalledPlugin(result.name, result.version, source = fullRef) }
                        echo("Installed: ${result.name}")
                        echo("Location: ${result.extractDir}")
                    }
                    is com.hebe.plugins.install.InstallResult.Error -> {
                        echo("Error: ${result.message}")
                        throw com.github.ajalt.clikt.core
                            .Abort()
                    }
                }
            }
        }
    }
}

class PluginListCommand : CliktCommand(name = "plugin list") {
    private val pluginsDir =
        java.nio.file.Path
            .of(System.getProperty("user.home"), ".hebe", "plugins")
    private val settingsStore: com.hebe.config.SettingsStore =
        com.hebe.config.defaultSettingsStore()

    override fun run() {
        runBlocking {
            val plugins = settingsStore.getInstalledPlugins()
            if (plugins.isEmpty()) {
                echo("No plugins installed")
                return@runBlocking
            }
            echo("")
            echo("%-18s %-8s %-12s %-20s".format("ID", "VERSION", "STATUS", "CAPABILITIES"))
            echo("%-18s %-8s %-12s %-20s".format("--", "-------", "------", "------------"))
            for (plugin in plugins.sortedBy { it.name }) {
                val pluginPath = pluginsDir.resolve("${plugin.name}-${plugin.version}")
                val status = resolvePluginStatus(pluginPath, plugin.source)
                val caps = resolvePluginCapabilities(pluginPath)
                echo("%-18s %-8s %-12s %-20s".format(plugin.name, plugin.version, status, caps))
            }
            echo("")
            echo("${plugins.size} plugin(s) installed")
        }
    }

    private fun resolvePluginStatus(
        pluginPath: java.nio.file.Path,
        source: String?,
    ): String {
        val exists = pluginPath.toFile().exists()
        if (!exists) {
            return "NOT_INSTALLED"
        }
        return if (source == "sideload") {
            "SIDELOADED"
        } else {
            "INSTALLED"
        }
    }

    private fun resolvePluginCapabilities(pluginPath: java.nio.file.Path): String {
        val exists = pluginPath.toFile().exists()
        if (!exists) {
            return "-"
        }
        val capsVal = readPluginCapabilities(pluginPath)
        return if (capsVal != null) {
            capsVal.capabilities.joinToString(",") { it.name.lowercase() }
        } else {
            "-"
        }
    }

    private fun readPluginCapabilities(pluginPath: java.nio.file.Path): com.hebe.plugin.api.PluginManifest? {
        val tomlPath = pluginPath.resolve("plugin.toml")
        val exists = tomlPath.toFile().exists()
        if (!exists) {
            return null
        }
        return try {
            val parser = com.hebe.plugins.manifest.ManifestParser
            val result = parser.parse(tomlPath)
            if (result is com.hebe.plugins.manifest.ManifestResult.Ok) {
                result.value
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}

class PluginRemoveCommand : CliktCommand(name = "plugin remove") {
    private val pluginsDir =
        java.nio.file.Path
            .of(System.getProperty("user.home"), ".hebe", "plugins")
    private val settingsStore: com.hebe.config.SettingsStore =
        com.hebe.config.defaultSettingsStore()
    private val name by argument(help = "Plugin name (e.g. hello-plugin-0.1.0)")

    override fun run() {
        val pluginPath = pluginsDir.resolve(name)
        if (!java.nio.file.Files
                .exists(pluginPath)
        ) {
            echo("Error: plugin '$name' not found at $pluginPath")
            return
        }
        try {
            java.nio.file.Files
                .walk(pluginPath)
                .sorted(Comparator.reverseOrder())
                .map { it.toFile() }
                .forEach { it.delete() }
            runBlocking { settingsStore.removeInstalledPlugin(name) }
            echo("Removed plugin: $name")
        } catch (e: Exception) {
            echo("Error removing plugin: ${e.message}")
        }
    }
}

class DoctorCommand : CliktCommand(name = "doctor") {
    private val url by option("--url", help = "Gateway base URL").default("http://127.0.0.1:8765")
    private val password by option("--password", help = "Admin password (plaintext)")

    override fun run() {
        val statusUrl = "$url/api/status"
        try {
            val conn = URI(statusUrl).toURL().openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 3_000
            conn.readTimeout = 5_000
            if (password != null) {
                val token = Base64.getEncoder().encodeToString("admin:$password".toByteArray())
                conn.setRequestProperty("Authorization", "Basic $token")
            }
            conn.connect()
            val responseCode = conn.responseCode
            if (responseCode == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                displayStatus(body)
            } else if (responseCode == 401) {
                echo("Error: authentication failed — use --password to supply the admin password")
            } else {
                echo("Error: gateway returned HTTP $responseCode")
            }
            conn.disconnect()
        } catch (e: Exception) {
            echo("Error: could not reach gateway at $statusUrl — is it running? (${e.message})")
        }
    }

    private fun displayStatus(json: String) {
        // Simple display — parse manually to avoid adding a JSON library dep
        echo("=== Hebe Gateway Status ===")
        if ("uptimeMs" in json) {
            val ms =
                json
                    .substringAfter("\"uptimeMs\":")
                    .substringBefore(",")
                    .trim()
                    .toLongOrNull()
            if (ms != null) echo("  Uptime:   ${formatUptime(ms)}")
        }
        echo("")
        echo("  Channels:")
        if ("\"channels\":" in json) {
            val channelsJson = json.substringAfter("\"channels\":").substringAfter("[").substringBefore("]")
            val entries = channelsJson.split("},{")
            for (entry in entries) {
                val name = entry.substringAfter("\"name\":\"").substringBefore("\"")
                val health = entry.substringAfter("\"health\":\"").substringBefore("\"")
                if (name.isNotEmpty()) echo("    $name: $health")
            }
        }
        echo("")
        echo("  LLM:")
        if ("\"llm\":" in json) {
            val reachable = "\"reachable\":true" in json
            echo("    reachable: $reachable")
            if ("\"endpoint\":" in json) {
                val endpoint = json.substringAfter("\"endpoint\":\"").substringBefore("\"")
                echo("    endpoint:  $endpoint")
            }
        }
    }

    private fun formatUptime(ms: Long): String {
        val s = ms / 1000
        val m = s / 60
        val h = m / 60
        return if (h > 0) {
            "${h}h ${m % 60}m"
        } else if (m > 0) {
            "${m}m ${s % 60}s"
        } else {
            "${s}s"
        }
    }
}

class ServiceInstallCommand : CliktCommand(name = "service install") {
    override fun run() {
        echo("Not yet implemented: hebe service install")
    }
}

class ServiceStartCommand : CliktCommand(name = "service start") {
    override fun run() {
        echo("Not yet implemented: hebe service start")
    }
}

class ServiceStopCommand : CliktCommand(name = "service stop") {
    override fun run() {
        echo("Not yet implemented: hebe service stop")
    }
}

class ServiceUninstallCommand : CliktCommand(name = "service uninstall") {
    override fun run() {
        echo("Not yet implemented: hebe service uninstall")
    }
}

class StatusCommand : CliktCommand(name = "status") {
    override fun run() {
        echo("Not yet implemented: hebe status")
    }
}

class CompletionBashCommand : CliktCommand(name = "completion bash") {
    override fun run() {
        echo("Not yet implemented: hebe completion bash")
    }
}

class CompletionZshCommand : CliktCommand(name = "completion zsh") {
    override fun run() {
        echo("Not yet implemented: hebe completion zsh")
    }
}

class CompletionFishCommand : CliktCommand(name = "completion fish") {
    override fun run() {
        echo("Not yet implemented: hebe completion fish")
    }
}

class OnboardCommand : CliktCommand(name = "onboard") {
    override fun run() {
        echo("Not yet implemented: hebe onboard")
    }
}

class EstopCommand : CliktCommand(name = "estop") {
    override fun run() {
        val socketPath =
            EstopIpc.getSocketPath(
                Paths.get(System.getProperty("user.home"), ".hebe"),
            )
        echo("Sending estop to local hebe instance…")
        val ok = EstopIpc.sendStop(socketPath)
        if (ok) {
            echo("Acknowledged. In-flight tool calls cancelled. Pending approvals expired.")
        } else {
            echo("Error: could not reach hebe instance at $socketPath — is it running?")
        }
    }
}

class MemoryShowCommand : CliktCommand(name = "memory show") {
    private val pathArg by argument(help = "Path to receipts directory or file")

    override fun run() {
        val path = Paths.get(pathArg)
        val publicKeyPath = Paths.get(System.getProperty("user.home"), ".hebe", "receipts", "public.key")

        if (!path.exists()) {
            echo("Error: Path not found: $path")
            return
        }

        val publicKeyBytes =
            if (publicKeyPath.exists()) {
                java.util.Base64
                    .getDecoder()
                    .decode(publicKeyPath.readText().trim())
            } else {
                echo("Error: Public key not found at $publicKeyPath")
                return
            }

        val verifier = ReceiptVerifier()
        val result =
            if (path.toFile().isDirectory) {
                verifier.verifyDirectory(path, publicKeyBytes)
            } else {
                verifier.verify(path, publicKeyBytes)
            }

        when (result) {
            is VerifyResult.Ok -> {
                echo("OK ${result.records} records, last hash: ${result.lastSelfHash}")
            }
            is VerifyResult.Failed -> {
                echo("FAILED at record ${result.recordSeq}: ${result.reason}")
            }
        }
    }
}
