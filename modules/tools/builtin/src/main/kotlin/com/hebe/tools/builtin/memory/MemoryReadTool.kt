package com.hebe.tools.builtin.memory

import com.hebe.api.MemoryCategory
import com.hebe.api.MemoryScope
import com.hebe.api.MemoryStore
import com.hebe.api.RiskLevel
import com.hebe.api.Tool
import com.hebe.api.ToolContext
import com.hebe.api.ToolResult
import com.hebe.api.ToolSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

class MemoryReadTool(
    private val memory: MemoryStore,
) : Tool {
    private val logger = LoggerFactory.getLogger(javaClass)

    override val spec = ToolSpec(
        name = "memory_read",
        description = "Read a document from memory by path.",
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
                            put("description", JsonPrimitive("Document path"))
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
        val path = args["path"]?.jsonPrimitive?.content
            ?: return ToolResult.Err("missing required argument: path")

        logger.debug("memory_read path={}", path)

        return try {
            val content = memory.readDoc(path)
            if (content != null) {
                ToolResult.Ok(JsonPrimitive(content))
            } else {
                ToolResult.Err("document not found: $path")
            }
        } catch (e: Exception) {
            ToolResult.Err("read failed: ${e.message}")
        }
    }
}