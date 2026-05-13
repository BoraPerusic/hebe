package com.hebe.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
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
    override fun run() {
        echo("Not yet implemented: hebe plugin install")
    }
}

class PluginListCommand : CliktCommand(name = "plugin list") {
    override fun run() {
        echo("Not yet implemented: hebe plugin list")
    }
}

class PluginRemoveCommand : CliktCommand(name = "plugin remove") {
    override fun run() {
        echo("Not yet implemented: hebe plugin remove")
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
