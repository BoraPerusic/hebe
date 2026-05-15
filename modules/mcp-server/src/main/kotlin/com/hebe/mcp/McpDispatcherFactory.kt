package com.hebe.mcp

import com.hebe.api.ApprovalGate
import com.hebe.api.ApprovalStatus
import com.hebe.api.LeakDetector
import com.hebe.api.Observer
import com.hebe.api.ObserverEvent
import com.hebe.api.PartialReceipt
import com.hebe.api.Receipts
import com.hebe.api.Span
import com.hebe.api.Tool
import com.hebe.api.ToolResult
import com.hebe.api.Validator
import com.hebe.tools.dispatch.ToolDispatcher
import com.hebe.tools.dispatch.ToolRegistry
import kotlinx.serialization.json.JsonObject

object McpDispatcherFactory {
    private object DenyAllApprovalGate : ApprovalGate {
        override fun requestIfNeeded(
            tool: Tool,
            args: JsonObject,
            turnId: String,
            channel: String,
            threadExtId: String?,
        ) = kotlinx.coroutines.flow.flowOf(ApprovalStatus.Denied)

        override suspend fun awaitApproval(
            tool: Tool,
            args: JsonObject,
            turnId: String,
            channel: String,
            threadExtId: String?,
        ): Boolean = false

        override fun resolve(
            approvalId: String,
            approved: Boolean,
        ): Boolean = false
    }

    private object NoopObserver : Observer {
        override fun event(e: ObserverEvent) {}

        override fun span(
            name: String,
            attrs: Map<String, Any>,
        ): Span = NoopSpan
    }

    private object NoopSpan : Span {
        override fun setAttribute(
            key: String,
            value: Any,
        ) {}

        override fun recordError(t: Throwable) {}

        override fun close() {}
    }

    private object NoopLeakDetector : LeakDetector {
        override fun scan(result: ToolResult): ToolResult = result
    }

    private object NoopReceipts : Receipts {
        override suspend fun append(partial: PartialReceipt): Long = 0L
    }

    private object NoopMemoryStore : com.hebe.api.MemoryStore {
        override suspend fun appendMessage(
            conversationId: String,
            msg: com.hebe.api.ConversationMessage,
        ) {}

        override suspend fun loadContext(
            conversationId: String,
            limit: Int,
        ): List<com.hebe.api.ConversationMessage> = emptyList()

        override suspend fun search(
            query: String,
            k: Int,
            scope: com.hebe.api.MemoryScope,
            categories: Set<com.hebe.api.MemoryCategory>?,
        ): List<com.hebe.api.MemoryHit> = emptyList()

        override suspend fun appendDoc(
            path: String,
            content: String,
            scope: com.hebe.api.MemoryScope,
            category: com.hebe.api.MemoryCategory,
        ) {}

        override suspend fun readDoc(path: String): String? = null

        override suspend fun listDocs(prefix: String): List<String> = emptyList()

        override suspend fun systemPrompt(isGroup: Boolean): String = ""

        override suspend fun snapshot(): com.hebe.api.MemorySnapshot = com.hebe.api.MemorySnapshot(0, 0, 0)
    }

    fun createLightweightDispatcher(
        registry: ToolRegistry,
        validators: List<Validator>,
        receipts: Receipts,
    ): ToolDispatcher =
        ToolDispatcher(
            registry = registry,
            validators = validators,
            approvalGate = DenyAllApprovalGate,
            memory = NoopMemoryStore,
            observer = NoopObserver,
            leakDetector = NoopLeakDetector,
            receipts = receipts,
        )
}
