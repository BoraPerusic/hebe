package com.hebe.config

import java.io.IOException
import java.nio.file.Path
import org.tomlj.Toml
import org.tomlj.TomlTable

sealed class ConfigResult<out T> {
    data class Ok<T>(
        val value: T,
        val diagnostics: List<ConfigDiagnostic>,
    ) : ConfigResult<T>()

    data class Error(
        val diagnostics: List<ConfigDiagnostic>,
    ) : ConfigResult<Nothing>()
}

data class ConfigDiagnostic(
    val level: DiagnosticLevel,
    val message: String,
    val source: String?,
    val line: Int?,
    val column: Int?,
)

enum class DiagnosticLevel {
    INFO,
    WARNING,
    ERROR,
}

class ConfigLoader {
    fun load(path: Path): ConfigResult<HebeConfig> {
        val diagnostics = mutableListOf<ConfigDiagnostic>()
        val tomlResult = tryParseToml(path, diagnostics) ?: return ConfigResult.Error(diagnostics)

        val version = tomlResult.getString("version") ?: ConfigFormats.CURRENT
        if (version !in ConfigFormats.SUPPORTED) {
            diagnostics.add(
                ConfigDiagnostic(
                    level = DiagnosticLevel.WARNING,
                    message = "Unknown config version '$version', expected one of ${ConfigFormats.SUPPORTED}",
                    source = path.toString(),
                    line = null,
                    column = null,
                ),
            )
        }

        val config = parseConfig(tomlResult, diagnostics)

        return if (diagnostics.any { it.level == DiagnosticLevel.ERROR }) {
            ConfigResult.Error(diagnostics)
        } else {
            ConfigResult.Ok(config, diagnostics)
        }
    }

    private fun tryParseToml(
        path: Path,
        diagnostics: MutableList<ConfigDiagnostic>,
    ): TomlTable? =
        try {
            Toml.parse(path)
        } catch (e: IOException) {
            diagnostics.add(
                ConfigDiagnostic(
                    level = DiagnosticLevel.ERROR,
                    message = "Failed to read TOML file: ${e.message}",
                    source = path.toString(),
                    line = null,
                    column = null,
                ),
            )
            null
        }

    private fun parseConfig(
        toml: TomlTable,
        diagnostics: MutableList<ConfigDiagnostic>,
    ): HebeConfig {
        val observability = parseObservability(toml.getTable("observability"), diagnostics)
        val security = parseSecurity(toml.getTable("security"), diagnostics)
        val plugins = parsePlugins(toml.getTable("plugins"))
        val channels = parseChannels(toml.getTable("channels"))
        val providers = parseProviders(toml.getTable("providers"))
        val memory = parseMemory(toml.getTable("memory"))
        val scheduler = parseScheduler(toml.getTable("scheduler"))
        val api = parseApi(toml.getTable("api"))

        return HebeConfig(
            version = toml.getString("version") ?: ConfigFormats.CURRENT,
            observability = observability,
            security = security,
            plugins = plugins,
            channels = channels,
            providers = providers,
            memory = memory,
            scheduler = scheduler,
            api = api,
        )
    }

    private fun parseObservability(
        table: TomlTable?,
        diagnostics: MutableList<ConfigDiagnostic>,
    ): ObservabilityConfig {
        if (table == null) {
            addInfo(diagnostics, "observability section missing, using defaults")
            return ObservabilityConfig(
                level = ConfigDefaults.OBSERVABILITY_LEVEL,
                otelEndpoint = null,
                otelProtocol = ConfigDefaults.OTEL_PROTOCOL,
                logFile = null,
            )
        }
        return ObservabilityConfig(
            level = table.getString("level") ?: ConfigDefaults.OBSERVABILITY_LEVEL,
            otelEndpoint = table.getString("otelEndpoint"),
            otelProtocol = table.getString("otelProtocol") ?: ConfigDefaults.OTEL_PROTOCOL,
            logFile = table.getString("logFile"),
        )
    }

    private fun parseSecurity(
        table: TomlTable?,
        diagnostics: MutableList<ConfigDiagnostic>,
    ): SecurityConfig {
        if (table == null) {
            addInfo(diagnostics, "security section missing, using defaults")
            return SecurityConfig(
                secretStore = SecretStoreConfig(provider = "memory", keychainService = null),
                mfaRequired = false,
            )
        }
        val secretStoreTable = table.getTable("secretStore")
        return SecurityConfig(
            secretStore =
                SecretStoreConfig(
                    provider = secretStoreTable?.getString("provider") ?: "memory",
                    keychainService = secretStoreTable?.getString("keychainService"),
                ),
            mfaRequired = table.getBoolean("mfaRequired") ?: false,
        )
    }

    private fun parsePlugins(table: TomlTable?): PluginConfig {
        if (table == null) {
            return PluginConfig(directory = "plugins", autoLoad = true, allowedPlugins = emptyList())
        }
        @Suppress("UNCHECKED_CAST")
        val allowedRaw = table.get("allowedPlugins") as? List<*>
        val allowed = allowedRaw?.filterIsInstance<String>() ?: emptyList()
        return PluginConfig(
            directory = table.getString("directory") ?: "plugins",
            autoLoad = table.getBoolean("autoLoad") ?: true,
            allowedPlugins = allowed,
        )
    }

    private fun parseChannels(table: TomlTable?): ChannelConfig {
        if (table == null) {
            return ChannelConfig(
                defaultTimeoutSeconds = ConfigDefaults.CHANNEL_TIMEOUT_SECONDS,
                maxRetries = ConfigDefaults.CHANNEL_MAX_RETRIES,
                retryDelayMs = ConfigDefaults.CHANNEL_RETRY_DELAY_MS,
            )
        }
        return ChannelConfig(
            defaultTimeoutSeconds =
                table.getLong("defaultTimeoutSeconds")?.toInt()
                    ?: ConfigDefaults.CHANNEL_TIMEOUT_SECONDS,
            maxRetries = table.getLong("maxRetries")?.toInt() ?: ConfigDefaults.CHANNEL_MAX_RETRIES,
            retryDelayMs = table.getLong("retryDelayMs")?.toInt() ?: ConfigDefaults.CHANNEL_RETRY_DELAY_MS,
        )
    }

    private fun parseProviders(table: TomlTable?): ProviderConfig {
        if (table == null) {
            return ProviderConfig(
                defaultModel = null,
                fallbackModel = null,
                maxConcurrentRequests = ConfigDefaults.PROVIDER_MAX_CONCURRENT,
            )
        }
        return ProviderConfig(
            defaultModel = table.getString("defaultModel"),
            fallbackModel = table.getString("fallbackModel"),
            maxConcurrentRequests =
                table.getLong("maxConcurrentRequests")?.toInt()
                    ?: ConfigDefaults.PROVIDER_MAX_CONCURRENT,
        )
    }

    private fun parseMemory(table: TomlTable?): MemoryConfig {
        if (table == null) {
            return MemoryConfig(
                maxHistoryTokens = ConfigDefaults.MEMORY_MAX_HISTORY_TOKENS,
                retentionDays = ConfigDefaults.MEMORY_RETENTION_DAYS,
                vectorDbPath = null,
            )
        }
        return MemoryConfig(
            maxHistoryTokens =
                table.getLong("maxHistoryTokens")?.toInt()
                    ?: ConfigDefaults.MEMORY_MAX_HISTORY_TOKENS,
            retentionDays = table.getLong("retentionDays")?.toInt() ?: ConfigDefaults.MEMORY_RETENTION_DAYS,
            vectorDbPath = table.getString("vectorDbPath"),
        )
    }

    private fun parseScheduler(table: TomlTable?): SchedulerConfig {
        if (table == null) {
            return SchedulerConfig(
                maxQueueSize = ConfigDefaults.SCHEDULER_MAX_QUEUE_SIZE,
                workerPoolSize = ConfigDefaults.SCHEDULER_WORKER_POOL_SIZE,
            )
        }
        return SchedulerConfig(
            maxQueueSize = table.getLong("maxQueueSize")?.toInt() ?: ConfigDefaults.SCHEDULER_MAX_QUEUE_SIZE,
            workerPoolSize = table.getLong("workerPoolSize")?.toInt() ?: ConfigDefaults.SCHEDULER_WORKER_POOL_SIZE,
        )
    }

    private fun parseApi(table: TomlTable?): ApiConfig {
        if (table == null) {
            return ApiConfig(
                host = ConfigDefaults.API_HOST,
                port = ConfigDefaults.API_PORT,
                corsOrigins = emptyList(),
            )
        }
        @Suppress("UNCHECKED_CAST")
        val corsRaw = table.get("corsOrigins") as? List<*>
        val corsList = corsRaw?.filterIsInstance<String>() ?: emptyList()
        return ApiConfig(
            host = table.getString("host") ?: ConfigDefaults.API_HOST,
            port = table.getLong("port")?.toInt() ?: ConfigDefaults.API_PORT,
            corsOrigins = corsList,
        )
    }

    private fun addInfo(
        diagnostics: MutableList<ConfigDiagnostic>,
        message: String,
    ) {
        diagnostics.add(ConfigDiagnostic(DiagnosticLevel.INFO, message, null, null, null))
    }
}
