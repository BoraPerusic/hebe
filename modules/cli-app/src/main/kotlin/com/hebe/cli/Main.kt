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
import com.hebe.cli.daemon.PidFile
import com.hebe.cli.daemon.Shutdown
import com.hebe.config.ConfigLoader
import com.hebe.config.ConfigResult
import com.hebe.config.HebeConfig
import com.hebe.config.OsKeychainSecretStore
import com.hebe.gateway.Gateway
import com.hebe.observability.LogbackObserver
import com.hebe.observability.OtelBootstrap
import com.hebe.security.estop.EstopIpc
import com.hebe.security.receipts.ReceiptVerifier
import com.hebe.security.receipts.VerifyResult
import java.net.HttpURLConnection
import java.security.MessageDigest
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import com.hebe.api.Observer
import com.hebe.api.SecretLookup
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

private fun loadConfigOrDefault(path: java.nio.file.Path): HebeConfig =
    if (java.nio.file.Files.exists(path)) {
        com.hebe.config.ConfigLoader().load(path).let { result ->
            when (result) {
                is com.hebe.config.ConfigResult.Ok -> result.value
                is com.hebe.config.ConfigResult.Error -> {
                    System.err.println("Warning: failed to load config, using defaults")
                    HebeConfig.default()
                }
            }
        }
    } else {
        HebeConfig.default()
    }

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
            ServiceStatusCommand(),
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
    private val log = LoggerFactory.getLogger(RunCommand::class.java)

    override fun run() {
        val workspaceRoot = Path.of(System.getProperty("user.home"), ".hebe")
        Files.createDirectories(workspaceRoot)

        val configPath = workspaceRoot.resolve("config.toml")
        val config =
            if (Files.exists(configPath)) {
                when (val r = ConfigLoader().load(configPath)) {
                    is ConfigResult.Ok -> r.value
                    is ConfigResult.Error -> {
                        log.warn("Failed to load config, using defaults: {}", r.diagnostics)
                        HebeConfig.default()
                    }
                }
            } else {
                HebeConfig.default()
            }

        val secretStore = OsKeychainSecretStore.create(workspaceRoot)
        val logbackObserver = LogbackObserver()
        val observer = OtelBootstrap.createObserver(logbackObserver)

        log.info("building agent components")
        val components = AgentFactory.build(config, secretStore, workspaceRoot, observer, log)

        val channelWiring =
            ChannelWiring(
                webChannel = components.webChannel,
                receiptsDir = workspaceRoot.resolve("receipts"),
            )

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        runBlocking {
            channelWiring.registerChannels(components.channelManager, components.telegramChannel)
        }
        components.channelManager.start(scope)
        components.scheduler?.start(scope)

        val pidFile = PidFile.acquire(workspaceRoot.resolve("hebe.pid"))
        Shutdown.installHook(scope, pidFile) {
            log.info("draining in-flight turns")
            components.shutdown()
        }

        log.info("starting gateway on {}:{}", config.channels.web.bind, config.channels.web.port)
        Gateway().start(
            config = config.channels.web,
            secretStore = secretStore,
            mcpDeps = null,
        ) { channelWiring.applyToGateway(this) }
    }
}

class McpServeCommand : CliktCommand(name = "mcp serve") {
    private val configPath =
        java.nio.file.Path
            .of(System.getProperty("user.home"), ".hebe", "config.toml")

    override fun run() {
        val hebeConfig = loadConfigOrDefault(configPath)
        val mcpConfig = hebeConfig.mcp.server
        if (!mcpConfig.stdio) {
            echo("Stdio MCP server disabled in config")
            return
        }

        val registry =
            com.hebe.tools.dispatch
                .ToolRegistry()

        val workspacePath =
            java.nio.file.Path.of(
                hebeConfig.hebe.dataDir.replace("~", System.getProperty("user.home")),
            )
        com.hebe.mcp.registerMcpBuiltinTools(registry, workspacePath)

        val validators =
            com.hebe.security.policy.PolicyChain
                .standard(hebeConfig, workspacePath)

        val receiptsDir =
            java.nio.file.Paths
                .get(System.getProperty("user.home"), ".hebe", "receipts")
        val signingKey = loadOrCreateSigningKey(receiptsDir)
        val receipts =
            com.hebe.security.receipts
                .Receipts(receiptsDir, signingKey)

        val dispatcher =
            com.hebe.mcp.McpDispatcherFactory
                .createLightweightDispatcher(registry, validators, receipts)

        echo("Starting Hebe MCP Server (stdio mode)...")
        runBlocking<Unit> {
            com.hebe.mcp.runMcpStdioServer(hebeConfig, registry, dispatcher)
        }
    }

    private fun loadOrCreateSigningKey(receiptsDir: java.nio.file.Path): com.hebe.security.receipts.Ed25519PrivateKey {
        val keyFile = receiptsDir.resolve("private.key")
        return if (keyFile.exists()) {
            val bytes = Base64.getDecoder().decode(keyFile.readText().trim())
            com.hebe.security.receipts.Ed25519PrivateKey
                .load(bytes)
        } else {
            java.nio.file.Files
                .createDirectories(receiptsDir)
            val key =
                com.hebe.security.receipts.Ed25519PrivateKey
                    .generate()
            java.nio.file.Files
                .writeString(keyFile, Base64.getEncoder().encodeToString(key.encode()))
            key
        }
    }
}

class PluginInstallCommand : CliktCommand(name = "plugin install") {
    private val refOrPath by argument(help = "OCI reference (e.g. ghcr.io/user/hello-plugin:0.1.0) or local path to .zip file")
    private val unsigned by option("--unsigned", help = "Skip signature verification (for local/sideloaded plugins)").flag()
    private val configPath =
        java.nio.file.Path
            .of(System.getProperty("user.home"), ".hebe", "config.toml")
    private val hebeConfig: com.hebe.config.HebeConfig by lazy {
        loadConfigOrDefault(configPath)
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
        } catch (_: Exception) {
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
    private val jsonOutput by option("--json", help = "Output results as JSON").flag()
    private val verbose by option("--verbose", help = "Include recent events dump").flag()

    override fun run() {
        val workspaceRoot = Path.of(System.getProperty("user.home"), ".hebe")
        val configPath = workspaceRoot.resolve("config.toml")

        val config = if (Files.exists(configPath)) {
            when (val r = ConfigLoader().load(configPath)) {
                is ConfigResult.Ok -> r.value
                is ConfigResult.Error -> {
                    echo("Warning: failed to load config: ${r.diagnostics}")
                    HebeConfig.default()
                }
            }
        } else {
            HebeConfig.default()
        }

        val secretStore = OsKeychainSecretStore.create(workspaceRoot)
        val logbackObserver = LogbackObserver()
        val observer = OtelBootstrap.createObserver(logbackObserver)

        val results = kotlinx.coroutines.runBlocking {
            com.hebe.cli.doctor.runAllChecks(
                config = config,
                secretStore = secretStore,
                workspaceRoot = workspaceRoot,
                observer = observer,
            )
        }

        if (jsonOutput) {
            echo(com.hebe.cli.doctor.renderCheckJson(results))
        } else {
            echo(com.hebe.cli.doctor.renderCheckTable(results))
            if (verbose) {
                val events = logbackObserver.recentEvents(50)
                if (events.isNotEmpty()) {
                    echo("")
                    echo("--- Recent Events (last 50) ---")
                    events.forEach { echo(it.toString()) }
                }
            }
        }

        if (com.hebe.cli.doctor.hasAnyFailure(results)) {
            throw com.github.ajalt.clikt.core.Abort()
        }
    }
}

class ServiceInstallCommand : CliktCommand(name = "service install") {
    private val jar by option("--jar", help = "Path to hebe.jar").default("")
    private val java by option("--java", help = "Path to java binary").default(
        ProcessHandle
            .current()
            .info()
            .command()
            .orElse("java"),
    )

    override fun run() {
        val dataDir = Path.of(System.getProperty("user.home"), ".hebe")
        val svc =
            com.hebe.cli.service
                .platformService(dataDir)
        val jarPath =
            jar.ifEmpty {
                val devJar = Path.of(System.getProperty("user.dir"), "modules", "cli-app", "build", "libs", "hebe.jar")
                if (Files.exists(devJar)) {
                    devJar.toString()
                } else {
                    echo("Error: --jar not specified and hebe.jar not found at $devJar")
                    return
                }
            }
        val result = svc.install(jarPath, java)
        if (result.isSuccess) {
            echo("Service installed. Run `hebe service start` to start it.")
            echo("Note (Linux): run `loginctl enable-linger \$USER` to survive logouts.")
        } else {
            echo("Error: ${result.exceptionOrNull()?.message}")
        }
    }
}

class ServiceStartCommand : CliktCommand(name = "service start") {
    override fun run() {
        val dataDir = Path.of(System.getProperty("user.home"), ".hebe")
        val result =
            com.hebe.cli.service
                .platformService(dataDir)
                .start()
        if (result.isSuccess) echo("Service started.") else echo("Error: ${result.exceptionOrNull()?.message}")
    }
}

class ServiceStopCommand : CliktCommand(name = "service stop") {
    override fun run() {
        val dataDir = Path.of(System.getProperty("user.home"), ".hebe")
        val result =
            com.hebe.cli.service
                .platformService(dataDir)
                .stop()
        if (result.isSuccess) echo("Service stopped.") else echo("Error: ${result.exceptionOrNull()?.message}")
    }
}

class ServiceUninstallCommand : CliktCommand(name = "service uninstall") {
    override fun run() {
        val dataDir = Path.of(System.getProperty("user.home"), ".hebe")
        val result =
            com.hebe.cli.service
                .platformService(dataDir)
                .uninstall()
        if (result.isSuccess) echo("Service uninstalled.") else echo("Error: ${result.exceptionOrNull()?.message}")
    }
}

class ServiceStatusCommand : CliktCommand(name = "service status") {
    override fun run() {
        val dataDir = Path.of(System.getProperty("user.home"), ".hebe")
        val status = com.hebe.cli.service.platformService(dataDir).status()
        val (label, desc) =
            when (status) {
                is com.hebe.cli.service.ServiceStatus.Running -> "running" to "Hebe service is running"
                is com.hebe.cli.service.ServiceStatus.Stopped -> "stopped" to "Hebe service is stopped"
                is com.hebe.cli.service.ServiceStatus.NotInstalled -> "not-installed" to "Hebe service is not installed"
            }
        echo("$label: $desc")
    }
}

class StatusCommand : CliktCommand(name = "status") {
    private val url by option("--url", help = "Gateway base URL").default("http://127.0.0.1:8765")
    private val password by option("--password", help = "Admin password (plaintext)")
    private val recent by option("--recent", help = "Show last 20 receipts").flag()
    private val watch by option("--watch", help = "Refresh every 2s").flag()

    override fun run() {
        if (watch) {
            while (true) {
                print("[2J[H")
                printStatus()
                Thread.sleep(2_000)
            }
        } else {
            printStatus()
            if (recent) printReceipts()
        }
    }

    private fun printStatus() {
        try {
            val conn = URI("$url/api/status").toURL().openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 3_000
            conn.readTimeout = 5_000
            if (password != null) {
                val token = Base64.getEncoder().encodeToString("admin:$password".toByteArray())
                conn.setRequestProperty("Authorization", "Basic $token")
            }
            conn.connect()
            if (conn.responseCode == 200) {
                val json = conn.inputStream.bufferedReader().readText()
                printStatusTable(json)
            } else {
                echo("HTTP ${conn.responseCode}")
            }
            conn.disconnect()
        } catch (e: Exception) {
            echo("Cannot reach gateway at $url: ${e.message}")
        }
    }

    private fun printReceipts() {
        try {
            val conn = URI("$url/api/receipts?limit=20").toURL().openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 3_000
            conn.readTimeout = 5_000
            if (password != null) {
                val token = Base64.getEncoder().encodeToString("admin:$password".toByteArray())
                conn.setRequestProperty("Authorization", "Basic $token")
            }
            conn.connect()
            if (conn.responseCode == 200) {
                echo("\n--- Recent receipts ---")
                echo(conn.inputStream.bufferedReader().readText())
            }
            conn.disconnect()
        } catch (_: Exception) {
        }
    }

    private fun printStatusTable(json: String) {
        try {
            val doc = Json.parseToJsonElement(json)
            val obj: JsonObject = doc.jsonObject
            echo("")
            echo("%-24s %s".format("Property", "Value"))
            echo("%-24s %s".format("--------", "-----"))
            obj.forEach { (key, value) ->
                echo("%-24s %s".format(key, value.toString().removeSurrounding("\"")))
            }
        } catch (_: Exception) {
            echo(json)
        }
    }
}

class CompletionBashCommand : CliktCommand(name = "completion bash") {
    override fun run() {
        echo(buildCompletionScript("bash"))
    }
}

class CompletionZshCommand : CliktCommand(name = "completion zsh") {
    override fun run() {
        echo(buildCompletionScript("zsh"))
    }
}

class CompletionFishCommand : CliktCommand(name = "completion fish") {
    override fun run() {
        echo(buildCompletionScript("fish"))
    }
}

private fun ask(
    label: String,
    default: String = "",
): String {
    val hint = if (default.isNotEmpty()) " [$default]" else ""
    print("$label$hint: ")
    System.out.flush()
    val line = readLine()?.trim() ?: ""
    return line.ifEmpty { default }
}

private fun askSecret(label: String): String {
    val console = System.console()
    return if (console != null) {
        String(console.readPassword("$label: ") ?: charArrayOf())
    } else {
        print("$label: ")
        System.out.flush()
        readLine()?.trim() ?: ""
    }
}

private val SUBCOMMANDS =
    listOf(
        "run",
        "mcp serve",
        "plugin install",
        "plugin list",
        "plugin remove",
        "doctor",
        "service install",
        "service start",
        "service stop",
        "service uninstall",
        "status",
        "completion bash",
        "completion zsh",
        "completion fish",
        "onboard",
        "estop",
        "memory show",
    )

private fun buildCompletionScript(shell: String): String {
    val dollar = "$"
    val subcmds = SUBCOMMANDS.joinToString(" ")
    return when (shell) {
        "bash" ->
            """
            _hebe_completions() {
                local cur="$dollar{COMP_WORDS[${dollar}COMP_CWORD]}"
                local subcommands="$subcmds"
                COMPREPLY=( ${'$'}(compgen -W "${dollar}subcommands" -- "${dollar}cur") )
            }
            complete -F _hebe_completions hebe
            """.trimIndent()
        "zsh" ->
            """
            #compdef hebe
            _hebe() {
                local subcmds=(${SUBCOMMANDS.joinToString(" ") { "\"$it\"" }})
                _describe 'command' subcmds
            }
            _hebe
            """.trimIndent()
        "fish" -> {
            val topLevel = SUBCOMMANDS.map { it.split(" ").first() }.distinct()
            val notSeen = "not __fish_seen_subcommand_from ${topLevel.joinToString(" ")}"
            val topLines = topLevel.joinToString("\n") { "complete -c hebe -f -n '$notSeen' -a $it" }
            val subLines = SUBCOMMANDS
                .filter { " " in it }
                .joinToString("\n") { sub ->
                    val parts = sub.split(" ", limit = 2)
                    "complete -c hebe -f -n '__fish_seen_subcommand_from ${parts[0]}' -a ${parts[1]}"
                }
            "$topLines\n$subLines"
        }
        else -> ""
    }
}

class OnboardCommand : CliktCommand(name = "onboard") {
    private val force by option("--force", help = "Re-run even if already configured").flag()
    private val nonInteractive by option(
        "--non-interactive",
        help = "Read configuration from environment variables: HEBE_LLM_BASE_URL, HEBE_API_KEY, HEBE_ADMIN_PASSWORD, HEBE_DEFAULT_MODEL, HEBE_TELEGRAM_TOKEN, HEBE_OPERATOR_ID",
    ).flag()

    @Suppress("LongMethod", "ComplexMethod")
    override fun run() {
        val dataDir = Path.of(System.getProperty("user.home"), ".hebe")
        Files.createDirectories(dataDir)
        val configPath = dataDir.resolve("config.toml")
        val bootstrapPath = dataDir.resolve("BOOTSTRAP.md")

        if (!force &&
            com.hebe.cli.onboard
                .isAlreadyOnboarded(configPath, bootstrapPath)
        ) {
            echo("Already onboarded. Use --force to reconfigure.")
            return
        }

        val llmBaseUrl: String
        val apiKey: String
        val defaultModel: String
        val adminPassword: String
        val enableTelegram: Boolean
        val botToken: String
        val operatorId: Long

        if (nonInteractive) {
            llmBaseUrl = System.getenv("HEBE_LLM_BASE_URL") ?: run { echo("Error: HEBE_LLM_BASE_URL not set"); return }
            apiKey = System.getenv("HEBE_API_KEY") ?: run { echo("Error: HEBE_API_KEY not set"); return }
            adminPassword = System.getenv("HEBE_ADMIN_PASSWORD") ?: run { echo("Error: HEBE_ADMIN_PASSWORD not set"); return }
            defaultModel = System.getenv("HEBE_DEFAULT_MODEL") ?: "gpt-4o-mini"
            val tgToken = System.getenv("HEBE_TELEGRAM_TOKEN") ?: ""
            botToken = tgToken
            enableTelegram = tgToken.isNotEmpty()
            operatorId = System.getenv("HEBE_OPERATOR_ID")?.toLongOrNull() ?: 0L
        } else {
            echo("=== Hebe Onboarding Wizard ===")
            echo("")

            // Step 1: LLM endpoint
            var tmpUrl = ""
            var tmpKey = ""
            while (true) {
                tmpUrl = ask("LLM base URL", "https://api.openai.com/v1")
                tmpKey = askSecret("API key")
                echo("Validating LLM endpoint...")
                if (com.hebe.cli.onboard
                        .validateLlmEndpoint(tmpUrl, tmpKey)
                ) {
                    echo("OK")
                    break
                }
                echo("Warning: endpoint did not respond with 200. Continue anyway? [y/N]")
                if (readLine()?.trim()?.lowercase() != "y") continue
                break
            }
            llmBaseUrl = tmpUrl
            apiKey = tmpKey

            // Step 2: Default model
            defaultModel = ask("Default model", "gpt-4o-mini")

            // Step 3: Admin password
            adminPassword = askSecret("Web admin password")
            val confirmPassword = askSecret("Confirm admin password")
            if (adminPassword != confirmPassword) {
                echo("Passwords do not match. Aborting.")
                return
            }

            // Step 4: Telegram (optional)
            echo("Enable Telegram channel? [y/N]")
            val tgEnabled = readLine()?.trim()?.lowercase() == "y"
            var tmpToken = ""
            var tmpOperatorId = 0L
            if (tgEnabled) {
                while (true) {
                    tmpToken = askSecret("Telegram bot token")
                    echo("Validating bot token...")
                    if (com.hebe.cli.onboard
                            .validateTelegramToken(tmpToken)
                    ) {
                        echo("OK")
                        break
                    }
                    echo("Invalid token. Retry? [y/N]")
                    if (readLine()?.trim()?.lowercase() != "y") break
                }
                tmpOperatorId = ask("Your Telegram user ID (numeric)", "0").trim().toLongOrNull() ?: 0L
            }
            enableTelegram = tgEnabled
            botToken = tmpToken
            operatorId = tmpOperatorId
        }

        val answers =
            com.hebe.cli.onboard.OnboardAnswers(
                llmBaseUrl = llmBaseUrl,
                apiKey = apiKey,
                defaultModel = defaultModel,
                adminPassword = adminPassword,
                telegramEnabled = enableTelegram && botToken.isNotEmpty(),
                telegramBotToken = botToken,
                operatorTelegramId = operatorId,
            )

        // Step 5: Write config
        echo("Writing config.toml...")
        val toml =
            com.hebe.cli.onboard
                .generateConfigToml(answers, dataDir)
        Files.writeString(configPath, toml)

        // Step 6: Persist secrets
        echo("Storing secrets...")
        val secretStore = OsKeychainSecretStore.create(dataDir)
        runBlocking {
            secretStore.set("llm.api_key", apiKey.toByteArray())
            secretStore.set("web.password", MessageDigest.getInstance("SHA-256").digest(adminPassword.toByteArray()))
            if (enableTelegram && botToken.isNotEmpty()) {
                secretStore.set("telegram.bot_token", botToken.toByteArray())
            }
        }

        // Step 7: Seed workspace + signing key
        echo("Seeding workspace...")
        val workspaceFs =
            com.hebe.memory.workspace
                .WorkspaceFs(dataDir)
        com.hebe.memory.workspace.WorkspaceSeeder
            .seedIfMissing(workspaceFs, dataDir)
        runBlocking {
            com.hebe.security.receipts.SigningKey
                .bootstrap(secretStore)
        }

        // Step 8: Finalise
        Files.deleteIfExists(bootstrapPath)
        echo("")
        echo("All set! Run `hebe doctor` to verify, then `hebe run` to start.")
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
