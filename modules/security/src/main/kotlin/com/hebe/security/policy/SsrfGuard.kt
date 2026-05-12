package com.hebe.security.policy

import java.net.InetAddress
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

class SsrfGuard(
    private val allowLoopbackFor: List<String> = emptyList(),
) {
    private val dnsCache = ConcurrentHashMap<String, LongArray>()
    private val dnsCacheTtlMs = 60_000L

    private val blockedRanges = listOf(
        BlockedRange("127.0.0.0/8", "Loopback"),
        BlockedRange("::1", "Loopback"),
        BlockedRange("169.254.0.0/16", "Link-local"),
        BlockedRange("fe80::/10", "Link-local"),
        BlockedRange("10.0.0.0/8", "Private"),
        BlockedRange("172.16.0.0/12", "Private"),
        BlockedRange("192.168.0.0/16", "Private"),
        BlockedRange("fc00::/7", "Unique-local"),
        BlockedRange("169.254.169.254", "AWS/Azure metadata"),
        BlockedRange("metadata.google.internal", "GCP metadata"),
    )

    fun isBlocked(url: String): SsrfResult {
        return try {
            val parsed = URL(url)
            val host = parsed.host

            if (host in allowLoopbackFor) {
                return SsrfResult.Allowed
            }

            val addresses = resolveHostnames(host)
            for (addr in addresses) {
                for (range in blockedRanges) {
                    if (range.contains(addr)) {
                        return SsrfResult.Blocked("URL hostname resolved to blocked range: ${range.description}")
                    }
                }
            }

            SsrfResult.Allowed
        } catch (e: Exception) {
            SsrfResult.Invalid("Failed to parse URL: ${e.message}")
        }
    }

    private fun resolveHostnames(host: String): List<String> {
        if (allowLoopbackFor.contains(host)) {
            return listOf("127.0.0.1", "::1")
        }

        val cached = dnsCache[host]
        if (cached != null && System.currentTimeMillis() - cached[0] < dnsCacheTtlMs) {
            return cached.drop(1).map { numToAddr(it) }
        }

        return try {
            val addresses = InetAddress.getAllByName(host)
            val addrs = addresses.map { it.hostAddress ?: return@map null }
                .filterNotNull()
            if (addrs.isNotEmpty()) {
                dnsCache[host] = longArrayOf(System.currentTimeMillis()) + addrs.map { addrToNum(it) }.toLongArray()
            }
            addrs
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun addrToNum(addr: String): Long {
        val parts = addr.split(":")
        if (addr.contains(".")) {
            val octets = addr.split(".").map { it.toLongOrNull() ?: 0 }
            return (octets.getOrElse(0) { 0 } shl 24) or
                    (octets.getOrElse(1) { 0 } shl 16) or
                    (octets.getOrElse(2) { 0 } shl 8) or
                    octets.getOrElse(3) { 0 }
        }
        return 0L
    }

    private fun numToAddr(num: Long): String {
        return "${(num shr 24) and 0xff}.${(num shr 16) and 0xff}.${(num shr 8) and 0xff}.${num and 0xff}"
    }

    sealed class SsrfResult {
        data object Allowed : SsrfResult()
        data class Blocked(val reason: String) : SsrfResult()
        data class Invalid(val reason: String) : SsrfResult()
    }

    private class BlockedRange(
        val cidr: String,
        val description: String,
    ) {
        fun contains(addr: String): Boolean {
            if (addr == cidr) return true
            if (cidr.contains("/")) {
                return false
            }
            return false
        }
    }
}
