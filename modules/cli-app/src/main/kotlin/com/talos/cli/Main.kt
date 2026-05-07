package com.talos.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main

fun main(args: Array<String>) {
    TalosCLI().main(args)
}

class TalosCLI : CliktCommand(name = "talos") {
    override fun run() {
        echo("Talos v1.0.0")
        echo("Use --help for available commands")
    }
}

class VersionCommand : CliktCommand(name = "version") {
    override fun run() {
        echo("Talos v1.0.0")
    }
}
