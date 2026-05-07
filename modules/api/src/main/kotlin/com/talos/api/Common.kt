package com.talos.api

import kotlinx.serialization.Serializable

@Serializable
enum class RiskLevel {
    Low,
    Medium,
    High,
}

typealias WorkspacePath = String

interface SecretLookup {
    fun secret(name: String): String?
}
