package com.hebe.security.policy

import com.hebe.api.PathScope
import com.hebe.api.ParsedToolCall
import com.hebe.api.Tool
import com.hebe.api.ToolContext
import com.hebe.api.ValidationResult
import com.hebe.api.Validator
import com.hebe.api.workspace.WorkspacePath
import com.hebe.tools.dispatch.DispatchValidator
import com.hebe.tools.dispatch.DispatchValidationResult
import java.nio.file.Path
import java.nio.file.Paths

class WorkspaceBoundaryValidator(
    private val forbiddenPaths: List<String>,
    private val workspaceRoot: Path,
    private val additionalAllowedRoots: List<Path> = emptyList(),
) : Validator {
    override suspend fun validate(
        call: ParsedToolCall,
        tool: Tool,
        ctx: ToolContext,
    ): ValidationResult {
        if (tool.spec.pathScope == PathScope.Anywhere) {
            return ValidationResult.Allow
        }

        val pathArgs = extractPathArgs(call.args)
        for (pathValue in pathArgs) {
            val normalized = Paths.get(pathValue).toAbsolutePath().normalize()

            for (forbidden in forbiddenPathAbsolutes) {
                if (normalized.toString().startsWith(forbidden.toString())) {
                    return ValidationResult.Deny("Path $pathValue is in forbidden directory: ${forbidden.toString()}")
                }
            }

            val isWithinWorkspace = normalized.startsWith(workspaceRoot.toAbsolutePath().normalize())
            val isWithinAllowedRoot = additionalAllowedRoots.any { normalized.startsWith(it.toAbsolutePath().normalize()) }

            if (!isWithinWorkspace && !isWithinAllowedRoot) {
                if (tool.spec.pathScope == PathScope.WorkspaceOnly) {
                    return ValidationResult.Deny("Path $pathValue is outside workspace and not in allowed roots")
                }
            }
        }

        return ValidationResult.Allow
    }

    private val forbiddenPathAbsolutes = forbiddenPaths.map { Paths.get(it).toAbsolutePath().normalize() }

    private fun extractPathArgs(args: kotlinx.serialization.json.JsonObject): List<String> {
        val keys = listOf("path", "file", "target", "dest", "cwd", "dir", "folder", "root", "home")
        val result = mutableListOf<String>()

        for (key in args.keys) {
            if (key.lowercase() in keys) {
                val element = args[key]
                if (element is kotlinx.serialization.json.JsonPrimitive && element.isString) {
                    result.add(element.content)
                }
            }
        }

        return result
    }
}

class WorkspaceBoundaryDispatchValidator(
    private val forbiddenPaths: List<String>,
    private val workspaceRoot: Path,
    private val additionalAllowedRoots: List<Path> = emptyList(),
) : DispatchValidator {
    override suspend fun validate(
        call: ParsedToolCall,
        tool: Tool,
        ctx: ToolContext,
    ): DispatchValidationResult {
        if (tool.spec.pathScope == PathScope.Anywhere) {
            return DispatchValidationResult.Allow
        }

        val pathArgs = extractPathArgs(call.args)
        for (pathValue in pathArgs) {
            val normalized = Paths.get(pathValue).toAbsolutePath().normalize()

            for (forbidden in forbiddenPathAbsolutes) {
                if (normalized.toString().startsWith(forbidden.toString())) {
                    return DispatchValidationResult.Deny("Path $pathValue is in forbidden directory: ${forbidden.toString()}")
                }
            }

            val isWithinWorkspace = normalized.startsWith(workspaceRoot.toAbsolutePath().normalize())
            val isWithinAllowedRoot = additionalAllowedRoots.any { normalized.startsWith(it.toAbsolutePath().normalize()) }

            if (!isWithinWorkspace && !isWithinAllowedRoot) {
                if (tool.spec.pathScope == PathScope.WorkspaceOnly) {
                    return DispatchValidationResult.Deny("Path $pathValue is outside workspace and not in allowed roots")
                }
            }
        }

        return DispatchValidationResult.Allow
    }

    private val forbiddenPathAbsolutes = forbiddenPaths.map { Paths.get(it).toAbsolutePath().normalize() }

    private fun extractPathArgs(args: kotlinx.serialization.json.JsonObject): List<String> {
        val keys = listOf("path", "file", "target", "dest", "cwd", "dir", "folder", "root", "home")
        val result = mutableListOf<String>()

        for (key in args.keys) {
            if (key.lowercase() in keys) {
                val element = args[key]
                if (element is kotlinx.serialization.json.JsonPrimitive && element.isString) {
                    result.add(element.content)
                }
            }
        }

        return result
    }
}
