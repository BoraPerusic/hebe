package com.example

import com.hebe.api.RiskLevel
import com.hebe.api.Tool
import com.hebe.api.ToolContext
import com.hebe.api.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class SayHelloTool(private val host: com.hebe.plugin.api.PluginHost) : Tool {
    override val spec = com.hebe.api.ToolSpec(
        name = "say_hello",
        description = "Prints a greeting.",
        schema = JsonObject(mapOf("type" to JsonPrimitive("object"))),
    )

    override val risk = RiskLevel.Low

    override suspend fun invoke(args: JsonObject, ctx: ToolContext): ToolResult {
        return ToolResult.Ok(JsonPrimitive("hello from plugin (pluginId=${host.pluginId})"))
    }
}