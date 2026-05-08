package com.hebe.plugin.api

import kotlinx.coroutines.CoroutineScope

interface HebePlugin {
    val id: PluginId
    val version: String
    val displayName: String

    fun initialize(
        host: PluginHost,
        scope: CoroutineScope,
    )

    fun shutdown()
}

@JvmInline
value class PluginId(
    val value: String,
)

@JvmInline
value class PluginVersion(
    val value: String,
)
