package com.hebe.core.delegate

import com.hebe.api.Channel
import com.hebe.api.ChatMessage
import com.hebe.api.ChatRequest
import com.hebe.api.ChatRole
import com.hebe.api.ConversationMessage
import com.hebe.api.LlmProvider
import com.hebe.api.LoopConfig
import com.hebe.api.LoopOutcome
import com.hebe.api.LoopSignal
import com.hebe.api.MemoryStore
import com.hebe.api.ParsedToolCall
import com.hebe.api.Reasoning
import com.hebe.api.ReasoningContext
import com.hebe.api.RespondOutput
import com.hebe.api.StreamEvent
import com.hebe.api.TextAction
import com.hebe.api.ToolContext
import com.hebe.api.ToolResult
import com.hebe.core.compaction.PreemptivePruner
import com.hebe.core.cost.CostGuard
import com.hebe.core.loop.LoopDelegate
import com.hebe.core.loop.runAgenticLoop
import com.hebe.tools.dispatch.DispatchOutcome
import com.hebe.tools.dispatch.ToolDispatcher
import java.util.UUID
import kotlin.time.Clock
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory

class ChatDelegate(
    private val sessionId: String,
    private val channel: Channel,
    private val memory: MemoryStore,
    private val dispatcher: ToolDispatcher,
    private val llmProvider: LlmProvider,
    private val costGuard: CostGuard,
    private val compactor: PreemptivePruner,
    private val observer: com.hebe.api.Observer,
    private val systemPrompt: String,
    private val toolsProvider: suspend (userMessage: String) -> List<com.hebe.api.ToolSpec>,
    private val modelName: String,
    private val sessionMutex: Mutex,
) : LoopDelegate {
    private val logger = LoggerFactory.getLogger(javaClass)

    private var estop: Boolean = false
    private var cancel: Boolean = false
    private var draftBuffer = StringBuilder()

    private var compactedHistory: List<ConversationMessage>? = null
    private var lastTokensIn: Int = 0
    private var lastTokensOut: Int = 0
    private var lastCallStartMs: Long = 0
    private var lastTurnId: String = ""

    override suspend fun checkSignals(): LoopSignal {
        if (estop) return LoopSignal.Estop
        if (cancel) return LoopSignal.Cancel
        return LoopSignal.Continue
    }

    override suspend fun beforeLlmCall(
        ctx: ReasoningContext,
        iter: Int,
    ): LoopOutcome? {
        val costCheck = costGuard.checkAllowed(ctx.turnId)
        return when (costCheck) {
            is CostGuard.CheckResult.DenyDaily -> LoopOutcome.Failure("daily budget exceeded: \$${costCheck.spentUsd}")
            is CostGuard.CheckResult.DenyPerTurn -> LoopOutcome.Failure("per-turn token cap exceeded")
            CostGuard.CheckResult.Allow -> {
                val history = memory.loadContext(sessionId)
                val pruneResult = compactor.prune(history, ctx.turnId)
                if (pruneResult.compacted) {
                    logger.debug("compaction ran at iter={}", iter)
                    compactedHistory = pruneResult.messages
                }
                null
            }
        }
    }

    override suspend fun callLlm(
        reasoning: Reasoning,
        ctx: ReasoningContext,
    ): RespondOutput {
        draftBuffer.clear()
        lastCallStartMs = System.currentTimeMillis()
        lastTurnId = ctx.turnId

        val history = compactedHistory ?: memory.loadContext(sessionId)
        compactedHistory = null

        val chatMessages =
            history.map { convMsg ->
                when (convMsg.role) {
                    ChatRole.User -> ChatMessage.User(convMsg.content)
                    ChatRole.Assistant -> ChatMessage.Assistant(convMsg.content, convMsg.toolCalls)
                    ChatRole.Tool ->
                        ChatMessage.ToolResult(
                            convMsg.toolCalls.firstOrNull()?.id ?: "",
                            convMsg.content,
                        )
                    ChatRole.System -> ChatMessage.System(convMsg.content)
                }
            }
        val request =
            ChatRequest(
                model = modelName,
                systemPrompt = reasoning.systemPrompt,
                messages = chatMessages,
                tools = toolsProvider(reasoning.latestUserMessage),
                temperature = 0.7,
                maxTokens = null,
                stream = true,
            )

        val events = llmProvider.chat(request).toList()
        val textParts = mutableListOf<String>()
        val toolCalls = mutableListOf<ParsedToolCall>()

        for (event in events) {
            when (event) {
                is StreamEvent.TextDelta -> {
                    textParts.add(event.text)
                    draftBuffer.append(event.text)
                }
                is StreamEvent.ToolCall -> toolCalls.add(event.call)
                is StreamEvent.TokenUsage -> {
                    lastTokensIn = event.input
                    lastTokensOut = event.output
                }
                StreamEvent.Done -> { /* done */ }
                is StreamEvent.Error -> logger.error("LLM error: {}", event.cause)
            }
        }

        val fullText = textParts.joinToString("")

        if (toolCalls.isNotEmpty()) {
            return RespondOutput.WithToolCalls(toolCalls)
        }
        return RespondOutput.TextOnly(fullText)
    }

    override suspend fun handleTextResponse(text: String): TextAction {
        memory.appendMessage(
            sessionId,
            ConversationMessage(
                id = UUID.randomUUID(),
                role = ChatRole.Assistant,
                content = text,
                toolCalls = emptyList(),
                ts = Clock.System.now(),
            ),
        )
        return TextAction.FinishWith
    }

    override suspend fun executeToolCalls(
        calls: List<ParsedToolCall>,
        ctx: ReasoningContext,
    ): LoopOutcome? {
        val toolCtx = toToolContext(ctx)
        for (call in calls) {
            val outcome = dispatcher.dispatch(call, toolCtx)
            when (outcome) {
                is DispatchOutcome.Result -> {
                    val result = outcome.result
                    val content =
                        when (result) {
                            is ToolResult.Ok -> result.content.toString()
                            is ToolResult.Err -> "ERROR: ${result.message}"
                            is ToolResult.NeedsApproval -> "NEEDS_APPROVAL: ${result.prompt}"
                        }
                    memory.appendMessage(
                        sessionId,
                        ConversationMessage(
                            id = UUID.randomUUID(),
                            role = ChatRole.Tool,
                            content = content,
                            toolCalls = listOf(call),
                            ts = Clock.System.now(),
                        ),
                    )
                }
            }
        }
        return null
    }

    private fun toToolContext(ctx: ReasoningContext): ToolContext =
        object : ToolContext {
            override val sessionId: String = ctx.sessionId
            override val turnId: String = ctx.turnId
            override val userId: String = ctx.userId
            override val requestor: Channel = ctx.requestor
            override val workspace: com.hebe.api.workspace.WorkspacePath = ctx.workspace
            override val approvalGate: com.hebe.api.ApprovalGate = ctx.approvalGate
            override val observer: com.hebe.api.Observer = ctx.observer
            override val secretLookup: com.hebe.api.SecretLookup = ctx.secretLookup
        }

    override suspend fun afterIteration(iter: Int) {
        val durationMs = System.currentTimeMillis() - lastCallStartMs
        if (lastTurnId.isNotEmpty()) {
            costGuard.recordCall(
                turnId = lastTurnId,
                model = modelName,
                tokensIn = lastTokensIn,
                tokensOut = lastTokensOut,
                costMicrosUsd = null,
                durationMs = durationMs,
            )
        }
    }

    fun signalEstop() {
        estop = true
    }

    fun signalCancel() {
        cancel = true
    }

    suspend fun run(
        reasoning: Reasoning,
        ctx: ReasoningContext,
        config: LoopConfig,
    ): LoopOutcome =
        sessionMutex.withLock {
            cancel = false
            runAgenticLoop(this, reasoning, ctx, config)
        }
}
