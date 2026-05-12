package com.hebe.security.policy

import com.hebe.api.AutonomyLevel
import com.hebe.api.ValidationResult
import com.hebe.api.Validator
import com.hebe.config.HebeConfig
import com.hebe.tools.dispatch.DispatchValidator
import com.hebe.tools.dispatch.DispatchValidationResult
import java.nio.file.Path

object PolicyChain {
    fun standard(
        config: HebeConfig,
        workspaceRoot: Path,
    ): List<DispatchValidator> {
        val validators = mutableListOf<DispatchValidator>()

        validators.add(
            AutonomyDispatchValidator(config.autonomy.level),
        )

        validators.add(
            WorkspaceBoundaryDispatchValidator(
                forbiddenPaths = config.security.forbiddenPaths,
                workspaceRoot = workspaceRoot,
            ),
        )

        validators.add(
            CommandPolicyDispatchValidator(
                allowedCommandGlobs = config.security.allowedCommandGlobs,
                forbiddenCommandGlobs = config.security.forbiddenCommandGlobs,
            ),
        )

        val ssrfGuard = SsrfGuard()
        validators.add(
            DomainAllowlistDispatchValidator(
                allowedDomains = config.security.httpAllowlistDomains,
                ssrfGuard = ssrfGuard,
            ),
        )

        validators.add(
            PromptInjectionDispatchValidator(),
        )

        return validators
    }

    fun toApiValidators(dispatchValidators: List<DispatchValidator>): List<Validator> {
        return dispatchValidators.map { it.toApiValidator() }
    }
}

private fun DispatchValidator.toApiValidator(): Validator = object : Validator {
    override suspend fun validate(
        call: com.hebe.api.ParsedToolCall,
        tool: com.hebe.api.Tool,
        ctx: com.hebe.api.ToolContext,
    ): com.hebe.api.ValidationResult {
        return when (val result = this@toApiValidator.validate(call, tool, ctx)) {
            is DispatchValidationResult.Allow -> com.hebe.api.ValidationResult.Allow
            is DispatchValidationResult.RequireApproval -> com.hebe.api.ValidationResult.RequireApproval(result.prompt)
            is DispatchValidationResult.Deny -> com.hebe.api.ValidationResult.Deny(result.reason)
        }
    }
}
