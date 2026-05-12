package com.hebe.security.policy

import com.hebe.api.ParsedToolCall
import com.hebe.api.Tool
import com.hebe.api.ToolContext
import com.hebe.api.ValidationResult
import com.hebe.api.Validator
import com.hebe.memory.hygiene.HygieneResult
import com.hebe.memory.hygiene.HygieneScanner
import com.hebe.memory.hygiene.Severity
import com.hebe.tools.dispatch.DispatchValidator
import com.hebe.tools.dispatch.DispatchValidationResult

class PromptInjectionValidator : Validator {
    private val scanner = HygieneScanner(HygieneScanner.defaultRules())
    private val turnVerdicts = mutableMapOf<String, HygieneResult>()

    override suspend fun validate(
        call: ParsedToolCall,
        tool: Tool,
        ctx: ToolContext,
    ): ValidationResult {
        val cachedResult = turnVerdicts[ctx.turnId]
        if (cachedResult != null) {
            return resultToValidation(cachedResult)
        }

        val contentToScan = buildString {
            for ((_, value) in call.args) {
                if (value is kotlinx.serialization.json.JsonPrimitive && value.isString) {
                    appendLine(value.content)
                }
            }
        }

        val result = scanner.scan(contentToScan)
        turnVerdicts[ctx.turnId] = result

        return resultToValidation(result)
    }

    private fun resultToValidation(result: HygieneResult): ValidationResult {
        return when (result) {
            is HygieneResult.Clean -> ValidationResult.Allow
            is HygieneResult.Warn -> {
                if (result.findings.any { it.severity == Severity.High }) {
                    ValidationResult.RequireApproval("Prompt injection patterns detected: ${summarize(result)}")
                } else {
                    ValidationResult.Allow
                }
            }
            is HygieneResult.Reject -> {
                ValidationResult.Deny("Prompt injection blocked: ${summarize(result)}")
            }
        }
    }

    private fun summarize(result: HygieneResult): String {
        val findings = when (result) {
            is HygieneResult.Clean -> return ""
            is HygieneResult.Warn -> result.findings
            is HygieneResult.Reject -> result.findings
        }
        return findings.joinToString("; ") { it.rule }
    }

    fun clearTurnCache(turnId: String) {
        turnVerdicts.remove(turnId)
    }

    fun clearAllCache() {
        turnVerdicts.clear()
    }
}

class PromptInjectionDispatchValidator : DispatchValidator {
    private val scanner = HygieneScanner(HygieneScanner.defaultRules())
    private val turnVerdicts = mutableMapOf<String, HygieneResult>()

    override suspend fun validate(
        call: ParsedToolCall,
        tool: Tool,
        ctx: ToolContext,
    ): DispatchValidationResult {
        val cachedResult = turnVerdicts[ctx.turnId]
        if (cachedResult != null) {
            return resultToValidation(cachedResult)
        }

        val contentToScan = buildString {
            for ((_, value) in call.args) {
                if (value is kotlinx.serialization.json.JsonPrimitive && value.isString) {
                    appendLine(value.content)
                }
            }
        }

        val result = scanner.scan(contentToScan)
        turnVerdicts[ctx.turnId] = result

        return resultToValidation(result)
    }

    private fun resultToValidation(result: HygieneResult): DispatchValidationResult {
        return when (result) {
            is HygieneResult.Clean -> DispatchValidationResult.Allow
            is HygieneResult.Warn -> {
                if (result.findings.any { it.severity == Severity.High }) {
                    DispatchValidationResult.RequireApproval("Prompt injection patterns detected: ${summarize(result)}")
                } else {
                    DispatchValidationResult.Allow
                }
            }
            is HygieneResult.Reject -> {
                DispatchValidationResult.Deny("Prompt injection blocked: ${summarize(result)}")
            }
        }
    }

    private fun summarize(result: HygieneResult): String {
        val findings = when (result) {
            is HygieneResult.Clean -> return ""
            is HygieneResult.Warn -> result.findings
            is HygieneResult.Reject -> result.findings
        }
        return findings.joinToString("; ") { it.rule }
    }
}
