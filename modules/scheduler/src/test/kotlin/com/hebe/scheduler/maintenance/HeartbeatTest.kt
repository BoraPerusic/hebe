@file:Suppress("EmptyFunctionBlock", "MaxLineLength", "NewLineAtEndOfFile")

package com.hebe.scheduler.maintenance

import com.hebe.scheduler.Services
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest

private fun noopMemoryStore() = object : com.hebe.api.MemoryStore {
    override suspend fun search(query: String, k: Int, scope: com.hebe.api.MemoryScope, categories: Set<com.hebe.api.MemoryCategory>?) = emptyList<com.hebe.api.MemoryHit>()
    override suspend fun loadContext(conversationId: String, limit: Int) = emptyList<com.hebe.api.ConversationMessage>()
    override suspend fun appendMessage(conversationId: String, msg: com.hebe.api.ConversationMessage) {}
    override suspend fun appendDoc(path: String, content: String, scope: com.hebe.api.MemoryScope, category: com.hebe.api.MemoryCategory) {}
    override suspend fun readDoc(path: String): String? = null
    override suspend fun listDocs(prefix: String): List<String> = emptyList()
    override suspend fun systemPrompt(isGroup: Boolean): String = ""
    override suspend fun snapshot(): com.hebe.api.MemorySnapshot = com.hebe.api.MemorySnapshot(0, 0, 0)
}

private fun noopLlmProvider(response: String) = object : com.hebe.api.LlmProvider {
    override suspend fun chat(req: com.hebe.api.ChatRequest) = kotlinx.coroutines.flow.flowOf(
        com.hebe.api.StreamEvent.TextDelta(response),
        com.hebe.api.StreamEvent.Done,
    )
    override fun capabilities() = com.hebe.api.ProviderCapabilities(streaming = false, toolUse = false, multimodal = false, maxContextTokens = 0)
}

private fun noopApprovalGate() = object : com.hebe.api.ApprovalGate {
    override fun requestIfNeeded(tool: com.hebe.api.Tool, args: kotlinx.serialization.json.JsonObject, turnId: String, channel: String, threadExtId: String?) = kotlinx.coroutines.flow.flowOf(com.hebe.api.ApprovalStatus.Denied)
    override suspend fun awaitApproval(tool: com.hebe.api.Tool, args: kotlinx.serialization.json.JsonObject, turnId: String, channel: String, threadExtId: String?) = false
    override fun resolve(approvalId: String, approved: Boolean) = false
}

private fun noopObserver() = object : com.hebe.api.Observer {
    override fun event(e: com.hebe.api.ObserverEvent) {}
    override fun span(name: String, attrs: Map<String, Any>): com.hebe.api.Span = noopSpan()
}

private fun noopSpan() = object : com.hebe.api.Span {
    override fun setAttribute(key: String, value: Any) {}
    override fun recordError(t: Throwable) {}
    override fun close() {}
}

private fun noopLeakDetector() = object : com.hebe.api.LeakDetector {
    override fun scan(result: com.hebe.api.ToolResult) = result
}

private fun noopReceipts() = object : com.hebe.api.Receipts {
    override suspend fun append(partial: com.hebe.api.PartialReceipt): Long = 0L
}

private fun createFakeDispatcher(memory: com.hebe.api.MemoryStore): com.hebe.tools.dispatch.ToolDispatcher {
    return com.hebe.tools.dispatch.ToolDispatcher(
        registry = com.hebe.tools.dispatch.ToolRegistry(),
        validators = emptyList(),
        approvalGate = noopApprovalGate(),
        memory = memory,
        observer = noopObserver(),
        leakDetector = noopLeakDetector(),
        receipts = noopReceipts(),
    )
}

private fun noopCostGuard() = mockk<com.hebe.core.cost.CostGuard>(relaxed = true).also {
    coEvery { it.checkAllowed(any(), any()) } returns com.hebe.core.cost.CostGuard.CheckResult.Allow
}

private fun noopCompactor(): com.hebe.core.compaction.PreemptivePruner {
    val compactorLlm = mockk<com.hebe.api.LlmProvider>()
    every { compactorLlm.capabilities() } returns com.hebe.api.ProviderCapabilities(streaming = true, toolUse = false, multimodal = false, maxContextTokens = 128_000)
    val workspaceFs = mockk<com.hebe.memory.workspace.WorkspaceFs>(relaxed = true)
    val config = com.hebe.config.HebeConfig.default()
    return com.hebe.core.compaction.PreemptivePruner(
        com.hebe.core.compaction.Compactor(compactorLlm, workspaceFs, config)
    )
}

class HeartbeatTest : StringSpec({
    "heartbeat runs without error" {
        runTest {
            val fakeMemory = noopMemoryStore()
            val fakeLlm = noopLlmProvider("OK")
            val fakeDispatcher = createFakeDispatcher(fakeMemory)
            var notified = false
            val notifyChannel = object : Heartbeat.NotifyChannel {
                override suspend fun notify(title: String, body: String) { notified = true }
            }

            val heartbeat = Heartbeat(
                services = Services(
                    memory = fakeMemory,
                    dispatcher = fakeDispatcher,
                    llmProvider = fakeLlm,
                    costGuard = noopCostGuard(),
                    compactor = noopCompactor(),
                    observer = noopObserver(),
                ),
                modelName = "test",
                systemPrompt = "",
                notifyChannel = notifyChannel,
                heartbeatFilePath = "/nonexistent/HEARTBEAT.md",
            )

            val result = heartbeat.run()
            result.isSuccess shouldBe true
            notified shouldBe false
        }
    }

    "non-OK response triggers notification" {
        runTest {
            val tmp = kotlin.io.path.createTempFile("HEARTBEAT", ".md")
            tmp.toFile().writeText("- Check disk space")
            try {
                val fakeMemory = noopMemoryStore()
                val fakeLlm = noopLlmProvider("Disk space is low")
                val fakeDispatcher = createFakeDispatcher(fakeMemory)
                var notified = false
                val notifyChannel = object : Heartbeat.NotifyChannel {
                    override suspend fun notify(title: String, body: String) { notified = true }
                }

val heartbeat = Heartbeat(
                services = Services(
                    memory = fakeMemory,
                    dispatcher = fakeDispatcher,
                    llmProvider = fakeLlm,
                    costGuard = noopCostGuard(),
                    compactor = noopCompactor(),
                    observer = noopObserver(),
                ),
                modelName = "test",
                systemPrompt = "",
                notifyChannel = notifyChannel,
                heartbeatFilePath = tmp.toString(),
            )

                val result = heartbeat.run()
                result.isSuccess shouldBe true
                notified shouldBe true
            } finally {
                tmp.toFile().delete()
            }
        }
    }
})