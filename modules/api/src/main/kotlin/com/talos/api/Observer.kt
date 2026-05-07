package com.talos.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

interface Observer {
    fun event(e: ObserverEvent)

    fun span(
        name: String,
        attrs: Map<String, Any> = emptyMap(),
    ): Span
}

interface Span : AutoCloseable {
    fun setAttribute(
        key: String,
        value: Any,
    )

    fun recordError(t: Throwable)
}

@Serializable
sealed class ObserverEvent {
    @Serializable
    @SerialName("turn_start")
    data class TurnStart(
        val sessionId: String,
        val turnId: String,
    ) : ObserverEvent()

    @Serializable
    @SerialName("turn_end")
    data class TurnEnd(
        val sessionId: String,
        val turnId: String,
        val outcome: String,
    ) : ObserverEvent()

    @Serializable
    @SerialName("tool_dispatched")
    data class ToolDispatched(
        val turnId: String,
        val tool: String,
        val durationMs: Long,
        val ok: Boolean,
    ) : ObserverEvent()

    @Serializable
    @SerialName("llm_call")
    data class LlmCall(
        val turnId: String,
        val tokensIn: Int,
        val tokensOut: Int,
        val ms: Long,
    ) : ObserverEvent()

    @Serializable
    @SerialName("approval_requested")
    data class ApprovalRequested(
        val turnId: String,
        val tool: String,
    ) : ObserverEvent()

    @Serializable
    @SerialName("approval_resolved")
    data class ApprovalResolved(
        val turnId: String,
        val approved: Boolean,
    ) : ObserverEvent()
}
