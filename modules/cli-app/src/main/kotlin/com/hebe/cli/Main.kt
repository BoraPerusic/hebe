package com.hebe.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands

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
    override fun run() {
        echo("Not yet implemented: hebe doctor")
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
        echo("Not yet implemented: hebe estop")
    }
}

class MemoryShowCommand : CliktCommand(name = "memory show") {
    override fun run() {
        echo("Not yet implemented: hebe memory show")
    }
}
