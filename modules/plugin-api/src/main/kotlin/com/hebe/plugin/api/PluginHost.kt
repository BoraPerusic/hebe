package com.hebe.plugin.api

import java.time.Instant
import kotlinx.coroutines.CoroutineScope

interface PluginHost {
    val capabilities: PluginCapabilities
    val secrets: SecretStore
    val httpClient: GatedHttpClient

    fun registerCapability(capability: Capability): Unit

    fun requestPermission(permission: Permission): PermissionResult

    fun createScopedScope(
        parent: CoroutineScope,
        name: String,
    ): CoroutineScope
}

interface PluginCapabilities {
    fun <T : Capability> get(type: Class<T>): T?

    fun <T : Capability> register(capability: T)
}

sealed interface Capability {
    val name: String
    val version: String
}

sealed interface Permission {
    val name: String
}

sealed class PermissionResult {
    data object Granted : PermissionResult()

    data object Denied : PermissionResult()

    data class Revoked(
        val reason: String,
    ) : PermissionResult()
}

interface SecretStore {
    suspend fun get(key: String): SecretHandle?

    suspend fun set(
        key: String,
        value: ByteArray,
    ): Unit

    suspend fun delete(key: String): Boolean
}

interface SecretHandle {
    val value: ByteArray
    val metadata: SecretMetadata
}

interface SecretMetadata {
    val createdAt: Instant
    val expiresAt: Instant?
}

interface GatedHttpClient {
    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse

    suspend fun post(
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
    ): HttpResponse

    suspend fun put(
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
    ): HttpResponse

    suspend fun delete(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse
}

data class HttpResponse(
    val statusCode: Int,
    val headers: Map<String, String>,
    val body: ByteArray?,
) {
    private companion object {
        val OK_RANGE = 200..299
        val REDIRECT_RANGE = 300..399
        val CLIENT_ERROR_RANGE = 400..499
        val SERVER_ERROR_RANGE = 500..599
    }

    val isOk: Boolean get() = statusCode in OK_RANGE
    val isRedirect: Boolean get() = statusCode in REDIRECT_RANGE
    val isClientError: Boolean get() = statusCode in CLIENT_ERROR_RANGE
    val isServerError: Boolean get() = statusCode in SERVER_ERROR_RANGE
}
