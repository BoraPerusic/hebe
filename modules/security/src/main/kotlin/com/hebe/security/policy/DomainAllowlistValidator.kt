package com.hebe.security.policy

import com.hebe.api.ParsedToolCall
import com.hebe.api.Tool
import com.hebe.api.ToolContext
import com.hebe.api.ValidationResult
import com.hebe.api.Validator
import com.hebe.tools.dispatch.DispatchValidator
import com.hebe.tools.dispatch.DispatchValidationResult

class DomainAllowlistValidator(
    private val allowedDomains: List<String>,
    private val ssrfGuard: SsrfGuard,
) : Validator {
    private val domainAllowlist = allowedDomains.map { DomainPattern(it) }

    override suspend fun validate(
        call: ParsedToolCall,
        tool: Tool,
        ctx: ToolContext,
    ): ValidationResult {
        val url = extractUrl(call.args) ?: return ValidationResult.Allow

        val ssrfResult = ssrfGuard.isBlocked(url)
        when (ssrfResult) {
            is SsrfGuard.SsrfResult.Blocked -> {
                return ValidationResult.Deny("SSRF guard blocked: ${ssrfResult.reason}")
            }
            is SsrfGuard.SsrfResult.Invalid -> {
                return ValidationResult.Deny("Invalid URL: ${ssrfResult.reason}")
            }
            SsrfGuard.SsrfResult.Allowed -> { /* continue */ }
        }

        val host = extractHost(url)
        for (pattern in domainAllowlist) {
            if (pattern.matches(host)) {
                return ValidationResult.Allow
            }
        }

        return ValidationResult.Deny("Domain $host not in allowlist")
    }

    private fun extractUrl(args: kotlinx.serialization.json.JsonObject): String? {
        val urlElement = args["url"] ?: args["endpoint"] ?: args["uri"] ?: args["href"]
        return if (urlElement is kotlinx.serialization.json.JsonPrimitive && urlElement.isString) {
            urlElement.content
        } else {
            null
        }
    }

    private fun extractHost(url: String): String {
        return try {
            java.net.URL(url).host
        } catch (e: Exception) {
            ""
        }
    }

    private class DomainPattern(pattern: String) {
        private val isWildcard = pattern.startsWith("*.")
        private val domainPart = if (isWildcard) pattern.drop(2).lowercase() else pattern.lowercase()

        fun matches(host: String): Boolean {
            val normalizedHost = host.lowercase()
            return if (isWildcard) {
                normalizedHost == domainPart || normalizedHost.endsWith(".$domainPart")
            } else {
                normalizedHost == domainPart
            }
        }
    }
}

class DomainAllowlistDispatchValidator(
    private val allowedDomains: List<String>,
    private val ssrfGuard: SsrfGuard,
) : DispatchValidator {
    private val domainAllowlist = allowedDomains.map { DomainPattern(it) }

    override suspend fun validate(
        call: ParsedToolCall,
        tool: Tool,
        ctx: ToolContext,
    ): DispatchValidationResult {
        val url = extractUrl(call.args) ?: return DispatchValidationResult.Allow

        val ssrfResult = ssrfGuard.isBlocked(url)
        when (ssrfResult) {
            is SsrfGuard.SsrfResult.Blocked -> {
                return DispatchValidationResult.Deny("SSRF guard blocked: ${ssrfResult.reason}")
            }
            is SsrfGuard.SsrfResult.Invalid -> {
                return DispatchValidationResult.Deny("Invalid URL: ${ssrfResult.reason}")
            }
            SsrfGuard.SsrfResult.Allowed -> { /* continue */ }
        }

        val host = extractHost(url)
        for (pattern in domainAllowlist) {
            if (pattern.matches(host)) {
                return DispatchValidationResult.Allow
            }
        }

        return DispatchValidationResult.Deny("Domain $host not in allowlist")
    }

    private fun extractUrl(args: kotlinx.serialization.json.JsonObject): String? {
        val urlElement = args["url"] ?: args["endpoint"] ?: args["uri"] ?: args["href"]
        return if (urlElement is kotlinx.serialization.json.JsonPrimitive && urlElement.isString) {
            urlElement.content
        } else {
            null
        }
    }

    private fun extractHost(url: String): String {
        return try {
            java.net.URL(url).host
        } catch (e: Exception) {
            ""
        }
    }

    private class DomainPattern(pattern: String) {
        private val isWildcard = pattern.startsWith("*.")
        private val domainPart = if (isWildcard) pattern.drop(2).lowercase() else pattern.lowercase()

        fun matches(host: String): Boolean {
            val normalizedHost = host.lowercase()
            return if (isWildcard) {
                normalizedHost == domainPart || normalizedHost.endsWith(".$domainPart")
            } else {
                normalizedHost == domainPart
            }
        }
    }
}
