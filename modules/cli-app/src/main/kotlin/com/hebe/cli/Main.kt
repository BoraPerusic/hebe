package com.hebe.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main

fun main(args: Array<String>) {
    HebeCLI().main(args)
}

class HebeCLI : CliktCommand(name = "hebe") {
    override fun run() {
        echo("Hebe v1.0.0")
        echo("Use --help for available commands")
    }
}

class VersionCommand : CliktCommand(name = "version") {
    override fun run() {
        echo("Hebe v1.0.0")
    }
}
