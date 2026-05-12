@file:Suppress(
    "CyclomaticComplexMethod",
    "MagicNumber",
    "LoopWithTooManyJumpStatements",
    "LongMethod",
    "MaxLineLength",
)

package com.hebe.providers.openai

import com.hebe.api.ChatMessage
import com.hebe.api.ChatRequest
import com.hebe.api.LlmProvider
import com.hebe.api.ParsedToolCall
import com.hebe.api.ProviderCapabilities
import com.hebe.api.StreamEvent
import com.hebe.api.ToolChoice
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class OpenAiCompatProvider(
    private val baseUrl: String,
    private val defaultModel: String,
    private val httpClient: HttpClient,
    private val maxContextTokens: Int = 128_000,
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        },
) : LlmProvider {
    override suspend fun chat(req: ChatRequest): Flow<StreamEvent> =
        flow {
            val requestBody = buildChatRequest(req)
            val response =
                httpClient.post("$baseUrl/chat/completions") {
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                }

            if (!response.status.isSuccess()) {
                val status = response.status.value
                val retriable = status in 500..599 || status == 429
                emit(StreamEvent.Error("HTTP $status", retriable))
                return@flow
            }

            val channel = response.bodyAsChannel()
            val accumulatedToolCalls = mutableMapOf<Int, SseParser.AccumulatedToolCall>()
            var pendingUsage: StreamEvent.TokenUsage? = null

            while (true) {
                val line = channel.readUTF8Line() ?: break
                if (line.isEmpty()) continue

                val parts = SseParser.parseChunk(line, accumulatedToolCalls)
                for (part in parts) {
                    when (part) {
                        is SseParser.ParsedChunkPart.TextDelta -> emit(StreamEvent.TextDelta(part.text))
                        is SseParser.ParsedChunkPart.ToolCallReady -> {
                            val argsJson =
                                part.arguments
                                    .ifEmpty {
                                        "{}"
                                    }.let {
                                        try {
                                            json.parseToJsonElement(it).safeAsJsonObject()
                                        } catch (_: Exception) {
                                            JsonObject(emptyMap())
                                        }
                                    }
                            emit(StreamEvent.ToolCall(ParsedToolCall(part.id, part.name, argsJson)))
                        }
                        is SseParser.ParsedChunkPart.Done -> {
                            pendingUsage?.let { emit(it) }
                            emit(StreamEvent.Done)
                        }
                    }
                }

                if (line.contains("\"usage\"") && !line.contains("\"tool_calls\"")) {
                    val usageStart = line.indexOf("\"usage\"")
                    val usageJson = line.substring(usageStart)
                    try {
                        val usage = json.decodeFromString<ChatCompletionChunk>("{$usageJson}")
                        if (usage.usage != null) {
                            val cached = usage.usage.promptDetails?.cached ?: 0
                            pendingUsage =
                                StreamEvent.TokenUsage(
                                    input = usage.usage.prompt,
                                    output = usage.usage.completion,
                                    cached = cached,
                                )
                        }
                    } catch (_: Exception) {
                        // ignore parse errors for partial usage
                    }
                }
            }
        }

    override fun capabilities() =
        ProviderCapabilities(
            streaming = true,
            toolUse = true,
            multimodal = false,
            maxContextTokens = maxContextTokens,
            supportsPromptCaching = true,
            defaultModel = defaultModel,
        )

    private fun buildChatRequest(req: ChatRequest): ChatCompletionRequest {
        val messages = buildMessages(req)

        val tools =
            if (req.tools.isNotEmpty()) {
                req.tools.map { tool ->
                    WireTool(
                        function =
                            WireFunction(
                                name = tool.name,
                                description = tool.description,
                                parameters = tool.schema,
                            ),
                    )
                }
            } else {
                null
            }

        val toolChoice =
            when (val tc = req.toolChoice) {
                is ToolChoice.Auto -> JsonPrimitive("auto")
                is ToolChoice.None -> JsonPrimitive("none")
                is ToolChoice.Required -> JsonPrimitive("required")
                is ToolChoice.Specific ->
                    buildJsonObject {
                        put("type", JsonPrimitive("function"))
                        put("function", buildJsonObject { put("name", JsonPrimitive(tc.name)) })
                    }
            }

        val streamOptions =
            buildJsonObject {
                put("include_usage", JsonPrimitive(true))
            }

        return ChatCompletionRequest(
            model = req.model.ifEmpty { defaultModel },
            messages = messages,
            tools = tools,
            toolChoice = toolChoice,
            temperature = req.temperature,
            maxTokens = req.maxTokens,
            stream = true,
            streamOptions = streamOptions,
        )
    }

    private fun buildMessages(req: ChatRequest): List<WireMessage> {
        val msgs = mutableListOf<WireMessage>()

        msgs.add(WireMessage(role = "system", content = JsonPrimitive(req.systemPrompt)))

        for (msg in req.messages) {
            when (msg) {
                is ChatMessage.User -> {
                    msgs.add(WireMessage(role = "user", content = JsonPrimitive(msg.content)))
                }
                is ChatMessage.Assistant -> {
                    val toolCalls =
                        if (msg.toolCalls.isNotEmpty()) {
                            msg.toolCalls.map { tc ->
                                WireToolCall(
                                    id = tc.id,
                                    function =
                                        WireToolCallFn(
                                            name = tc.name,
                                            arguments = json.encodeToString(tc.args),
                                        ),
                                )
                            }
                        } else {
                            null
                        }
                    val content = if (msg.content.isNotEmpty()) JsonPrimitive(msg.content) else null
                    msgs.add(WireMessage(role = "assistant", content = content, toolCalls = toolCalls))
                }
                is ChatMessage.ToolResult -> {
                    msgs.add(
                        WireMessage(
                            role = "tool",
                            content = JsonPrimitive(msg.content),
                            toolCallId = msg.callId,
                        ),
                    )
                }
                is ChatMessage.System -> {
                    // Already added as the first message
                }
            }
        }

        return msgs
    }

    private fun Json.parseToJsonElement(text: String): JsonObject =
        kotlinx.serialization.json.Json
            .parseToJsonElement(text)
            .safeAsJsonObject()

    private fun kotlinx.serialization.json.JsonElement.safeAsJsonObject(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())
}
