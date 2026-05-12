package com.hebe.core.loop

import com.hebe.api.LoopConfig
import com.hebe.api.LoopOutcome
import com.hebe.api.LoopSignal
import com.hebe.api.ParsedToolCall
import com.hebe.api.Reasoning
import com.hebe.api.ReasoningContext
import com.hebe.api.RespondOutput
import com.hebe.api.TextAction
import org.slf4j.LoggerFactory

suspend fun runAgenticLoop(
    delegate: LoopDelegate,
    reasoning: Reasoning,
    ctx: ReasoningContext,
    config: LoopConfig,
): LoopOutcome {
    val logger = LoggerFactory.getLogger("com.hebe.core.loop.LoopDriver")

    for (iter in 0 until config.maxIterations) {
        val signal = delegate.checkSignals()
        if (signal != LoopSignal.Continue) {
            logger.debug("loop stopped by signal={} at iter={}", signal, iter)
            return LoopOutcome.Stopped
        }

        val beforeOutcome = delegate.beforeLlmCall(ctx, iter)
        if (beforeOutcome != null) {
            logger.debug("loop stopped by beforeLlmCall outcome={} at iter={}", beforeOutcome, iter)
            return beforeOutcome
        }

        val output = delegate.callLlm(reasoning, ctx)

        when (output) {
            is RespondOutput.TextOnly -> {
                when (delegate.handleTextResponse(output.text)) {
                    TextAction.FinishWith -> return LoopOutcome.Response(output.text)
                    TextAction.ContinueLoop -> { /* continue */ }
                }
            }
            is RespondOutput.WithToolCalls -> {
                val toolOutcome = delegate.executeToolCalls(output.calls, ctx)
                if (toolOutcome != null) {
                    logger.debug("loop stopped by executeToolCalls outcome={} at iter={}", toolOutcome, iter)
                    return toolOutcome
                }
            }
        }

        delegate.afterIteration(iter)
    }

    logger.debug("loop stopped: max iterations ({}) reached", config.maxIterations)
    return LoopOutcome.MaxIterations
}

interface LoopDelegate {
    suspend fun checkSignals(): LoopSignal

    suspend fun beforeLlmCall(
        ctx: ReasoningContext,
        iter: Int,
    ): LoopOutcome?

    suspend fun callLlm(
        reasoning: Reasoning,
        ctx: ReasoningContext,
    ): RespondOutput

    suspend fun handleTextResponse(text: String): TextAction

    suspend fun executeToolCalls(
        calls: List<ParsedToolCall>,
        ctx: ReasoningContext,
    ): LoopOutcome?

    suspend fun afterIteration(iter: Int)
}
