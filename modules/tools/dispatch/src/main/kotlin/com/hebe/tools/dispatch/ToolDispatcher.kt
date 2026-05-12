package com.hebe.tools.dispatch

import com.hebe.api.ChatRole
import com.hebe.api.ConversationMessage
import com.hebe.api.MemoryStore
import com.hebe.api.Observer
import com.hebe.api.ObserverEvent
import com.hebe.api.ParsedToolCall
import com.hebe.api.Span
import com.hebe.api.Tool
import com.hebe.api.ToolContext
import com.hebe.api.ToolResult
import com.hebe.security.approval.ApprovalGate
import java.util.UUID
import kotlin.time.Clock
import org.slf4j.LoggerFactory

class ToolDispatcher(
    private val registry: ToolRegistry,
    private val validators: List<Validator>,
    private val approvalGate: ApprovalGate,
    private val memory: MemoryStore,
    private val observer: Observer,
    private val leakDetector: LeakDetector,
    private val receipts: Receipts,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val loopDetector = LoopDetector()

    suspend fun dispatch(
        call: ParsedToolCall,
        ctx: ToolContext,
    ): DispatchOutcome {
        val tool =
            registry.get(call.name)
                ?: return DispatchOutcome.Result(
                    ToolResult.Err("unknown tool: ${call.name}"),
                )

        val startMs = System.currentTimeMillis()
        val span = observer.span("dispatch.${call.name}")

        try {
            span.use {
                val loopCount = loopDetector.fingerprint(ctx.turnId, call)
                logger.debug(
                    "dispatching tool={} turnId={} loopCount={}",
                    call.name,
                    ctx.turnId,
                    loopCount,
                )

                val validationResult = runValidators(call, tool, ctx)
                when (validationResult) {
                    is ValidationResult.Deny -> {
                        val result = ToolResult.Err("policy: ${validationResult.reason}")
                        writeReceiptAndMemory(call, result, ctx, span, tool, startMs)
                        return DispatchOutcome.Result(result)
                    }
                    is ValidationResult.RequireApproval -> {
                        val approved =
                            approvalGate.awaitApproval(
                                tool = tool,
                                args = call.args,
                                turnId = ctx.turnId,
                                channel = ctx.requestor.name,
                            )
                        if (!approved) {
                            val result = ToolResult.Err("denied")
                            writeReceiptAndMemory(call, result, ctx, span, tool, startMs)
                            return DispatchOutcome.Result(result)
                        }
                    }
                    ValidationResult.Allow -> { /* continue */ }
                }

                if (loopDetector.shouldForceText(ctx.turnId, call)) {
                    logger.warn(
                        "loop detector forced text for turnId={} tool={}",
                        ctx.turnId,
                        call.name,
                    )
                    val result =
                        ToolResult.Err(
                            "[Loop detector] Repeated identical call; switching to text mode",
                        )
                    writeReceiptAndMemory(call, result, ctx, span, tool, startMs)
                    return DispatchOutcome.Result(result)
                }

                val raw =
                    runCatching {
                        tool.invoke(call.args, ctx)
                    }.getOrElse { ex ->
                        ToolResult.Err("tool exception: ${ex.message}")
                    }

                val scanned = leakDetector.scan(raw)
                writeReceiptAndMemory(call, scanned, ctx, span, tool, startMs)
                return DispatchOutcome.Result(scanned)
            }
        } catch (e: Exception) {
            logger.error("dispatch exception for tool={}", call.name, e)
            return DispatchOutcome.Result(
                ToolResult.Err("dispatch exception: ${e.message}"),
            )
        }
    }

    private suspend fun runValidators(
        call: ParsedToolCall,
        tool: Tool,
        ctx: ToolContext,
    ): ValidationResult {
        var result: ValidationResult = ValidationResult.Allow
        for (validator in validators) {
            result = validator.validate(call, tool, ctx)
            if (result !is ValidationResult.Allow) break
        }
        return result
    }

    private suspend fun writeReceiptAndMemory(
        call: ParsedToolCall,
        result: ToolResult,
        ctx: ToolContext,
        span: Span,
        tool: Tool,
        startMs: Long,
    ) {
        val durationMs = System.currentTimeMillis() - startMs
        receipts.append(
            PartialReceipt(
                sessionId = ctx.sessionId,
                turnId = ctx.turnId,
                tool = call.name,
                argsRedacted = call.args.toString(),
                risk = tool.risk.name,
                durationMs = durationMs,
                ok = result is ToolResult.Ok,
            ),
        )
        memory.appendMessage(
            ctx.sessionId,
            ConversationMessage(
                id = UUID.randomUUID(),
                role = ChatRole.Tool,
                content = serializeResult(result),
                toolCalls = listOf(call),
                ts = Clock.System.now(),
            ),
        )
        observer.event(
            ObserverEvent.ToolDispatched(
                ctx.turnId,
                call.name,
                durationMs,
                ok = result is ToolResult.Ok,
            ),
        )
    }

    private fun serializeResult(result: ToolResult): String =
        when (result) {
            is ToolResult.Ok -> result.content.toString()
            is ToolResult.Err -> "ERROR: ${result.message}"
            is ToolResult.NeedsApproval -> "NEEDS_APPROVAL: ${result.prompt}"
        }
}

data class PartialReceipt(
    val sessionId: String,
    val turnId: String,
    val tool: String,
    val argsRedacted: String,
    val risk: String,
    val durationMs: Long,
    val ok: Boolean,
)

interface LeakDetector {
    fun scan(result: ToolResult): ToolResult
}

interface Receipts {
    suspend fun append(partial: PartialReceipt): Long
}
