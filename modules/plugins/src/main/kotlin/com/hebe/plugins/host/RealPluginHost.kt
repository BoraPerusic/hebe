@file:Suppress("UnusedPrivateProperty", "NewLineAtEndOfFile")

package com.hebe.plugins.host

import com.hebe.api.Observer
import com.hebe.plugin.api.Permission
import com.hebe.plugin.api.PluginCapabilityException
import com.hebe.plugin.api.PluginHost
import com.hebe.plugin.api.PluginManifest
import com.hebe.plugin.api.SecretHandle
import com.hebe.security.policy.SsrfGuard
import org.slf4j.Logger

private val ENV_DENYLIST_PATTERNS =
    listOf(
        "_TOKEN",
        "_SECRET",
        "_KEY",
        "_PASSWORD",
        "_CREDENTIAL",
        "_AUTH",
    )

class RealPluginHost(
    override val pluginId: String,
    private val pluginManifest: PluginManifest,
    private val secretResolver: (String) -> String?,
    override val observer: Observer,
    override val log: Logger,
    private val ssrfGuard: SsrfGuard = SsrfGuard(),
) : PluginHost {
    override val manifest: PluginManifest = pluginManifest

    private val allowedDomains: Set<String> = pluginManifest.allowlistDomains.toSet()

    private val hasHttpPermission: Boolean =
        Permission.HttpClient in pluginManifest.permissions

    private val hasEnvReadPermission: Boolean =
        Permission.EnvRead in pluginManifest.permissions

    private val secretPermissions: Map<String, Permission.Secret> =
        pluginManifest.permissions
            .filterIsInstance<Permission.Secret>()
            .associateBy { it.name }

    override fun http(): GatedHttpClientImpl {
        if (!hasHttpPermission) {
            throw PluginCapabilityException(
                "Plugin '$pluginId' does not have http_client permission",
            )
        }
        return GatedHttpClientImpl(pluginManifest, secretResolver, ssrfGuard)
    }

    override fun env(name: String): String? {
        if (!hasEnvReadPermission) {
            throw PluginCapabilityException(
                "Plugin '$pluginId' does not have env_read permission",
            )
        }
        if (isSensitiveEnvName(name)) {
            log.warn("Plugin '{}' denied env read for sensitive key '{}'", pluginId, name)
            return null
        }
        return System.getenv(name)
    }

    override fun secret(name: String): SecretHandle? {
        val permission = secretPermissions[name]
        if (permission == null) {
            throw PluginCapabilityException(
                "Plugin '$pluginId' does not have secrets:$name permission",
            )
        }
        return SecretHandle(name)
    }

    private fun isSensitiveEnvName(name: String): Boolean {
        val upper = name.uppercase()
        return ENV_DENYLIST_PATTERNS.any { pattern -> upper.endsWith(pattern) }
    }

    fun secretValue(name: String): String? = secretResolver(name)
}
