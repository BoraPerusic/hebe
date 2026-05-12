package com.hebe.core.auth

sealed interface AuthModeOutcome {
    data object Stored : AuthModeOutcome

    data class Failed(
        val reason: String,
    ) : AuthModeOutcome
}
