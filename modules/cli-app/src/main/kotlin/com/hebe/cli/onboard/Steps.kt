@file:Suppress("TooGenericExceptionCaught")

package com.hebe.cli.onboard

import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

data class OnboardAnswers(
    val llmBaseUrl: String,
    val apiKey: String,
    val defaultModel: String,
    val adminPassword: String,
    val telegramEnabled: Boolean,
    val telegramBotToken: String,
    val operatorTelegramId: Long,
)

fun validateLlmEndpoint(
    baseUrl: String,
    apiKey: String,
): Boolean =
    try {
        val conn = URI("$baseUrl/models").toURL().openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.connectTimeout = 5_000
        conn.readTimeout = 5_000
        conn.connect()
        val ok = conn.responseCode in 200..299
        conn.disconnect()
        ok
    } catch (_: Exception) {
        false
    }

fun validateTelegramToken(botToken: String): Boolean =
    try {
        val conn = URI("https://api.telegram.org/bot$botToken/getMe").toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 5_000
        conn.readTimeout = 5_000
        conn.connect()
        val ok = conn.responseCode == 200
        conn.disconnect()
        ok
    } catch (_: Exception) {
        false
    }

fun generateConfigToml(
    answers: OnboardAnswers,
    dataDir: Path,
): String =
    buildString {
        appendLine("[hebe]")
        appendLine("data_dir = \"$dataDir\"")
        appendLine()
        appendLine("[llm]")
        appendLine("base_url = \"${answers.llmBaseUrl}\"")
        appendLine("api_key_secret = \"llm.api_key\"")
        appendLine("default_model = \"${answers.defaultModel}\"")
        appendLine("embedding_model = \"text-embedding-3-small\"")
        appendLine()
        appendLine("[channels.web]")
        appendLine("enabled = true")
        appendLine("bind = \"127.0.0.1\"")
        appendLine("port = 8765")
        appendLine()
        if (answers.telegramEnabled) {
            appendLine("[channels.telegram]")
            appendLine("enabled = true")
            appendLine("operator_telegram_id = ${answers.operatorTelegramId}")
            appendLine()
        }
        appendLine("[autonomy]")
        appendLine("level = \"cautious\"")
        appendLine()
        appendLine("[security]")
        appendLine("plugin_signature_mode = \"disabled\"")
        appendLine()
        appendLine("[scheduler]")
        appendLine("enabled = true")
        appendLine()
        appendLine("[mcp.server]")
        appendLine("enabled = false")
        appendLine()
        appendLine("[mcp.client]")
        appendLine()
        appendLine("[plugins]")
        appendLine()
        appendLine("[cost]")
    }

fun isAlreadyOnboarded(
    configPath: Path,
    bootstrapPath: Path,
): Boolean = Files.exists(configPath) && !Files.exists(bootstrapPath)
