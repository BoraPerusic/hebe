package com.hebe.tools.builtin.memory

import com.hebe.api.MemoryCategory
import com.hebe.api.MemoryScope
import com.hebe.api.MemoryStore
import com.hebe.api.RiskLevel
import com.hebe.api.Tool
import com.hebe.api.ToolContext
import com.hebe.api.ToolResult
import com.hebe.api.ToolSpec
import com.hebe.memory.hygiene.HygieneResult
import com.hebe.memory.hygiene.HygieneScanner
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

class MemoryWriteTool(
    private val memory: MemoryStore,
    private val hygieneScanner: HygieneScanner = HygieneScanner(),
) : Tool {
    private val logger = LoggerFactory.getLogger(javaClass)

    override val spec = ToolSpec(
        name = "memory_write",
        description = "Write a document to memory.",
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
                            put("description", JsonPrimitive("Document path"))
                        },
                    )
                    put(
                        "content",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Document content"))
                        },
                    )
                    put(
                        "category",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Category: Document, Fact, Preference, Skill (default: Document)"))
                            put("default", JsonPrimitive("Document"))
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
        val path = args["path"]?.jsonPrimitive?.content
            ?: return ToolResult.Err("missing required argument: path")
        val content = args["content"]?.jsonPrimitive?.content
            ?: return ToolResult.Err("missing required argument: content")
        val categoryStr = args["category"]?.jsonPrimitive?.content ?: "Document"

        val category = try {
            MemoryCategory.valueOf(categoryStr)
        } catch (_: Exception) {
            MemoryCategory.Document
        }

        logger.debug("memory_write path={} category={}", path, category)

        val hygieneResult = hygieneScanner.scan(content)
        if (hygieneResult is HygieneResult.Reject) {
            val findings = hygieneResult.findings.joinToString("; ") { "${it.rule}(${it.severity})" }
            logger.warn("hygiene rejected write to {}: {}", path, findings)
            return ToolResult.Err("content blocked by hygiene: $findings")
        }

        return try {
            memory.appendDoc(path, content, MemoryScope.Default, category)
            ToolResult.Ok(JsonPrimitive("written: $path"))
        } catch (e: Exception) {
            ToolResult.Err("write failed: ${e.message}")
        }
    }
}