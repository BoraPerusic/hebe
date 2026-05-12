package com.hebe.tools.dispatch

import com.hebe.api.ParsedToolCall
import com.hebe.api.Tool
import com.hebe.api.ToolContext
import com.hebe.api.ValidationResult
import com.hebe.api.Validator

sealed interface DispatchValidationResult {
    data object Allow : DispatchValidationResult

    data class RequireApproval(
        val prompt: String,
    ) : DispatchValidationResult

    data class Deny(
        val reason: String,
    ) : DispatchValidationResult
}

interface DispatchValidator {
    suspend fun validate(
        call: ParsedToolCall,
        tool: Tool,
        ctx: ToolContext,
    ): DispatchValidationResult
}

fun Validator.toDispatchValidator(): DispatchValidator = DispatchValidatorImpl(this)

private class DispatchValidatorImpl(val validator: Validator) : DispatchValidator {
    override suspend fun validate(
        call: ParsedToolCall,
        tool: Tool,
        ctx: ToolContext,
    ): DispatchValidationResult {
        return when (val result = validator.validate(call, tool, ctx)) {
            is ValidationResult.Allow -> DispatchValidationResult.Allow
            is ValidationResult.RequireApproval -> DispatchValidationResult.RequireApproval(result.prompt)
            is ValidationResult.Deny -> DispatchValidationResult.Deny(result.reason)
        }
    }
}
