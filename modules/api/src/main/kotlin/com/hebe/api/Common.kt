package com.hebe.api

import kotlinx.serialization.Serializable

@Serializable
enum class RiskLevel {
    Low,
    Medium,
    High,
}

interface SecretLookup {
    fun secret(name: String): String?
}
