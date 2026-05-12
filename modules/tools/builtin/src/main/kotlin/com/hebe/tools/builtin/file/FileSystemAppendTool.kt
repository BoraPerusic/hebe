package com.hebe.tools.builtin.file

import com.hebe.api.RiskLevel
import com.hebe.api.Tool
import com.hebe.api.ToolContext
import com.hebe.api.ToolResult
import com.hebe.api.ToolSpec
import com.hebe.api.workspace.WorkspacePath
import com.hebe.memory.hygiene.HygieneResult
import com.hebe.memory.hygiene.HygieneScanner
import com.hebe.memory.workspace.WorkspaceFs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

class FileSystemAppendTool(
    private val fs: WorkspaceFs,
    private val hygieneScanner: HygieneScanner = HygieneScanner(),
) : Tool {
    private val logger = LoggerFactory.getLogger(javaClass)

    override val spec = ToolSpec(
        name = "file_system_append",
        description = "Append content to a file in the workspace.",
        schema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("required", buildJsonArray { add(JsonPrimitive("path")); add(JsonPrimitive("content")) })
            put(
                "properties",
                buildJsonObject {
                    put(
                        "path",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Workspace-relative destination path"))
                        },
                    )
                    put(
                        "content",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Content to append"))
                        },
                    )
                },
            )
        },
        pathScope = com.hebe.api.PathScope.WorkspaceOnly,
    )

    override val risk = RiskLevel.Medium
    override val readOnly = false

    override suspend fun invoke(args: JsonObject, ctx: ToolContext): ToolResult {
        val pathStr = args["path"]?.jsonPrimitive?.content
            ?: return ToolResult.Err("missing required argument: path")
        val content = args["content"]?.jsonPrimitive?.content
            ?: return ToolResult.Err("missing required argument: content")

        val path = WorkspacePath(pathStr)
        logger.debug("appending workspace path: {}", path.value)

        val hygieneResult = hygieneScanner.scan(content)
        if (hygieneResult is HygieneResult.Reject) {
            val findings = hygieneResult.findings.joinToString("; ") { "${it.rule}(${it.severity})" }
            logger.warn("hygiene rejected append to {}: {}", path.value, findings)
            return ToolResult.Err("content blocked by hygiene: $findings")
        }

        return runCatching {
            fs.append(path, content)
            ToolResult.Ok(JsonPrimitive("appended to: ${path.value}"))
        }.getOrElse { e ->
            ToolResult.Err("append failed: ${e.message}")
        }
    }
}