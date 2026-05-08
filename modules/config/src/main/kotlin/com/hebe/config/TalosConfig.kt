package com.hebe.config

data class HebeConfig(
    val version: String,
    val observability: ObservabilityConfig,
    val security: SecurityConfig,
    val plugins: PluginConfig,
    val channels: ChannelConfig,
    val providers: ProviderConfig,
    val memory: MemoryConfig,
    val scheduler: SchedulerConfig,
    val api: ApiConfig,
)

data class ObservabilityConfig(
    val level: String,
    val otelEndpoint: String?,
    val otelProtocol: String,
    val logFile: String?,
)

data class SecurityConfig(
    val secretStore: SecretStoreConfig,
    val mfaRequired: Boolean,
)

data class SecretStoreConfig(
    val provider: String,
    val keychainService: String?,
)

data class PluginConfig(
    val directory: String,
    val autoLoad: Boolean,
    val allowedPlugins: List<String>,
)

data class ChannelConfig(
    val defaultTimeoutSeconds: Int,
    val maxRetries: Int,
    val retryDelayMs: Int,
)

data class ProviderConfig(
    val defaultModel: String?,
    val fallbackModel: String?,
    val maxConcurrentRequests: Int,
)

data class MemoryConfig(
    val maxHistoryTokens: Int,
    val retentionDays: Int,
    val vectorDbPath: String?,
)

data class SchedulerConfig(
    val maxQueueSize: Int,
    val workerPoolSize: Int,
)

data class ApiConfig(
    val host: String,
    val port: Int,
    val corsOrigins: List<String>,
)

object ConfigDefaults {
    const val OBSERVABILITY_LEVEL = "INFO"
    const val OTEL_PROTOCOL = "grpc"
    const val CHANNEL_TIMEOUT_SECONDS = 30
    const val CHANNEL_MAX_RETRIES = 3
    const val CHANNEL_RETRY_DELAY_MS = 1000
    const val PROVIDER_MAX_CONCURRENT = 10
    const val MEMORY_MAX_HISTORY_TOKENS = 100_000
    const val MEMORY_RETENTION_DAYS = 30
    const val SCHEDULER_MAX_QUEUE_SIZE = 1000
    const val SCHEDULER_WORKER_POOL_SIZE = 4
    const val API_HOST = "0.0.0.0"
    const val API_PORT = 8080
}

object ConfigFormats {
    const val CURRENT = "1.0"
    val SUPPORTED = listOf("1.0")
}
