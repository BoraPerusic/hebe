package com.hebe.tools.builtin.memory

import com.hebe.api.MemoryHit
import com.hebe.api.MemoryScope
import com.hebe.api.MemoryStore
import com.hebe.api.RiskLevel
import com.hebe.api.Tool
import com.hebe.api.ToolContext
import com.hebe.api.ToolResult
import com.hebe.api.ToolSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

class MemorySearchTool(
    private val memory: MemoryStore,
) : Tool {
    private val logger = LoggerFactory.getLogger(javaClass)

    override val spec = ToolSpec(
        name = "memory_search",
        description = "Search memory for relevant documents using hybrid FTS + vector search.",
        schema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("required", buildJsonArray { add(JsonPrimitive("query")) })
            put(
                "properties",
                buildJsonObject {
                    put(
                        "query",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Search query"))
                        },
                    )
                    put(
                        "k",
                        buildJsonObject {
                            put("type", JsonPrimitive("integer"))
                            put("description", JsonPrimitive("Number of results (default: 10)"))
                            put("default", JsonPrimitive(10))
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
        val query = args["query"]?.jsonPrimitive?.content
            ?: return ToolResult.Err("missing required argument: query")
        val k = args["k"]?.jsonPrimitive?.content?.toIntOrNull() ?: 10

        logger.debug("memory_search query={} k={}", query, k)

        return try {
            val hits = memory.search(query, k)
            val results = buildJsonArray {
                hits.forEach { hit ->
                    add(hitToJson(hit))
                }
            }
            ToolResult.Ok(results)
        } catch (e: Exception) {
            ToolResult.Err("search failed: ${e.message}")
        }
    }

    private fun hitToJson(hit: MemoryHit): JsonObject = buildJsonObject {
        put("docPath", JsonPrimitive(hit.docPath))
        put("chunkIdx", JsonPrimitive(hit.chunkIdx))
        put("snippet", JsonPrimitive(hit.snippet))
        put("score", JsonPrimitive(hit.score))
        put("source", JsonPrimitive(hit.source.name))
    }
}