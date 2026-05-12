package com.hebe.tools.dispatch

import com.hebe.api.ParsedToolCall
import com.hebe.api.Tool
import com.hebe.api.ToolContext

sealed interface ValidationResult {
    data object Allow : ValidationResult

    data class RequireApproval(
        val prompt: String,
    ) : ValidationResult

    data class Deny(
        val reason: String,
    ) : ValidationResult
}

interface Validator {
    suspend fun validate(
        call: ParsedToolCall,
        tool: Tool,
        ctx: ToolContext,
    ): ValidationResult
}
