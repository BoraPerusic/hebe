package com.hebe.tools.builtin.file

import com.hebe.api.RiskLevel
import com.hebe.api.Tool
import com.hebe.api.ToolContext
import com.hebe.api.ToolResult
import com.hebe.api.ToolSpec
import com.hebe.api.workspace.WorkspacePath
import com.hebe.memory.workspace.MarkdownInferrer
import com.hebe.memory.workspace.WorkspaceFs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.nio.file.Path

class FileSystemReadTool(
    private val fs: WorkspaceFs,
) : Tool {
    private val logger = LoggerFactory.getLogger(javaClass)

    override val spec = ToolSpec(
        name = "file_system_read",
        description = "Read a file from the workspace.",
        schema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("required", buildJsonArray { add(JsonPrimitive("path")) })
            put(
                "properties",
                buildJsonObject {
                    put(
                        "path",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Workspace-relative path to the file"))
                        },
                    )
                    put(
                        "encoding",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Encoding: utf-8 (default) or base64"))
                            put("default", JsonPrimitive("utf-8"))
                        },
                    )
                },
            )
        },
        pathScope = com.hebe.api.PathScope.WorkspaceOnly,
    )

    override val risk = RiskLevel.Low
    override val readOnly = true

    override suspend fun invoke(args: JsonObject, ctx: ToolContext): ToolResult {
        val pathStr = args["path"]?.jsonPrimitive?.content
            ?: return ToolResult.Err("missing required argument: path")
        val encoding = args["encoding"]?.jsonPrimitive?.content ?: "utf-8"

        val path = WorkspacePath(pathStr)
        logger.debug("reading workspace path: {}", path.value)

        return when (val content = fs.read(path)) {
            null -> ToolResult.Err("file not found: ${path.value}")
            else -> {
                if (encoding == "base64") {
                    val bytes = content.toByteArray()
                    val base64 = java.util.Base64.getEncoder().encodeToString(bytes)
                    return ToolResult.Ok(JsonPrimitive(base64))
                }
                val meta = MarkdownInferrer.metadata(path.value, content)
                val isMarkdown = meta.extension == "md" || meta.extension == "markdown"
                buildJsonObject {
                    put("content", JsonPrimitive(content))
                    if (isMarkdown) {
                        put("title", JsonPrimitive(meta.title))
                        put("headings", buildJsonArray { meta.headings.forEach { add(JsonPrimitive(it)) } })
                        meta.frontmatter?.let { fm ->
                            put(
                                "frontmatter",
                                buildJsonObject {
                                    fm.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
                                },
                            )
                        }
                    }
                    put("extension", JsonPrimitive(meta.extension))
                    put("size", JsonPrimitive(content.length))
                }.let { ToolResult.Ok(it) }
            }
        }
    }
}