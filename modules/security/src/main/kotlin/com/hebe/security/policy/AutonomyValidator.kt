package com.hebe.security.policy

import com.hebe.api.AutonomyLevel
import com.hebe.api.ParsedToolCall
import com.hebe.api.RiskLevel
import com.hebe.api.Tool
import com.hebe.api.ToolContext
import com.hebe.api.ValidationResult
import com.hebe.api.Validator
import com.hebe.tools.dispatch.DispatchValidator
import com.hebe.tools.dispatch.DispatchValidationResult

class AutonomyValidator(
    private val level: AutonomyLevel,
) : Validator {
    override suspend fun validate(
        call: ParsedToolCall,
        tool: Tool,
        ctx: ToolContext,
    ): ValidationResult {
        return when (level) {
            AutonomyLevel.ReadOnly -> {
                if (tool.readOnly) ValidationResult.Allow else ValidationResult.Deny("ReadOnly mode blocks side-effect tools")
            }
            AutonomyLevel.Supervised -> {
                when {
                    tool.risk == RiskLevel.Low -> ValidationResult.Allow
                    tool.requiresApproval || tool.risk == RiskLevel.High -> ValidationResult.RequireApproval(
                        "Supervised mode requires approval for ${tool.risk.name} risk tool: ${tool.spec.name}",
                    )
                    else -> ValidationResult.RequireApproval(
                        "Supervised mode requires approval for ${tool.risk.name} risk tool: ${tool.spec.name}",
                    )
                }
            }
            AutonomyLevel.Full -> {
                when (tool.risk) {
                    RiskLevel.Low -> ValidationResult.Allow
                    RiskLevel.Medium -> ValidationResult.Allow
                    RiskLevel.High -> {
                        if (tool.requiresApproval) {
                            ValidationResult.RequireApproval(
                                "High-risk tool ${tool.spec.name} requires approval regardless of autonomy level",
                            )
                        } else {
                            ValidationResult.Allow
                        }
                    }
                }
            }
            AutonomyLevel.YOLO -> {
                ValidationResult.Allow
            }
        }
    }
}

class AutonomyDispatchValidator(
    private val level: AutonomyLevel,
) : DispatchValidator {
    override suspend fun validate(
        call: ParsedToolCall,
        tool: Tool,
        ctx: ToolContext,
    ): DispatchValidationResult {
        return when (level) {
            AutonomyLevel.ReadOnly -> {
                if (tool.readOnly) DispatchValidationResult.Allow else DispatchValidationResult.Deny("ReadOnly mode blocks side-effect tools")
            }
            AutonomyLevel.Supervised -> {
                when {
                    tool.risk == RiskLevel.Low -> DispatchValidationResult.Allow
                    tool.requiresApproval || tool.risk == RiskLevel.High -> DispatchValidationResult.RequireApproval(
                        "Supervised mode requires approval for ${tool.risk.name} risk tool: ${tool.spec.name}",
                    )
                    else -> DispatchValidationResult.RequireApproval(
                        "Supervised mode requires approval for ${tool.risk.name} risk tool: ${tool.spec.name}",
                    )
                }
            }
            AutonomyLevel.Full -> {
                when (tool.risk) {
                    RiskLevel.Low -> DispatchValidationResult.Allow
                    RiskLevel.Medium -> DispatchValidationResult.Allow
                    RiskLevel.High -> {
                        if (tool.requiresApproval) {
                            DispatchValidationResult.RequireApproval(
                                "High-risk tool ${tool.spec.name} requires approval regardless of autonomy level",
                            )
                        } else {
                            DispatchValidationResult.Allow
                        }
                    }
                }
            }
            AutonomyLevel.YOLO -> {
                DispatchValidationResult.Allow
            }
        }
    }
}
