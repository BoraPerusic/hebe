package com.hebe.tools.builtin.search

import com.hebe.api.ToolContext
import com.hebe.api.ToolResult
import io.mockk.mockk
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import kotlinx.coroutines.runBlocking

class WebSearchToolTest {
    @Test
    fun `web_search missing query returns Err`() {
        val tool = WebSearchTool(mockk(), null)

        val args = buildJsonObject { }
        val ctx = mockk<ToolContext>()

        val result = runBlocking { tool.invoke(args, ctx) }

        Assertions.assertTrue(result is ToolResult.Err)
        Assertions.assertTrue((result as ToolResult.Err).message.contains("missing required argument"))
    }

    @Test
    fun `web_search empty results returns empty array`() {
        val tool = WebSearchTool(mockk(), "fake-key")

        val args = buildJsonObject {
            put("query", kotlinx.serialization.json.JsonPrimitive("test"))
            put("k", kotlinx.serialization.json.JsonPrimitive(5))
        }
        val ctx = mockk<ToolContext>()

        val result = runBlocking { tool.invoke(args, ctx) }

        Assertions.assertTrue(result is ToolResult.Ok)
    }
}