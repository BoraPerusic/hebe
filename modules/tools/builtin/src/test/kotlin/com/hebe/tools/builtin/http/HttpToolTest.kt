package com.hebe.tools.builtin.http

import com.hebe.api.ToolContext
import com.hebe.api.ToolResult
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class HttpToolTest {
    @Test
    fun `http missing method returns Err`() {
        val secretLookup = mockk<com.hebe.api.SecretLookup>()
        val tool = HttpTool(secretLookup)

        val args =
            buildJsonObject {
                put("url", kotlinx.serialization.json.JsonPrimitive("https://example.com"))
            }
        val ctx = mockk<ToolContext>()

        val result = runBlocking { tool.invoke(args, ctx) }

        Assertions.assertTrue(result is ToolResult.Err)
        Assertions.assertTrue((result as ToolResult.Err).message.contains("missing required argument"))
    }

    @Test
    fun `http missing url returns Err`() {
        val secretLookup = mockk<com.hebe.api.SecretLookup>()
        val tool = HttpTool(secretLookup)

        val args =
            buildJsonObject {
                put("method", kotlinx.serialization.json.JsonPrimitive("GET"))
            }
        val ctx = mockk<ToolContext>()

        val result = runBlocking { tool.invoke(args, ctx) }

        Assertions.assertTrue(result is ToolResult.Err)
        Assertions.assertTrue((result as ToolResult.Err).message.contains("missing required argument"))
    }

    @Test
    fun `http unsupported method returns Err`() {
        val secretLookup = mockk<com.hebe.api.SecretLookup>()
        val tool = HttpTool(secretLookup)

        val args =
            buildJsonObject {
                put("method", kotlinx.serialization.json.JsonPrimitive("INVALID"))
                put("url", kotlinx.serialization.json.JsonPrimitive("https://example.com"))
            }
        val ctx = mockk<ToolContext>()

        val result = runBlocking { tool.invoke(args, ctx) }

        Assertions.assertTrue(result is ToolResult.Err)
        Assertions.assertTrue((result as ToolResult.Err).message.contains("unsupported HTTP method"))
    }
}
