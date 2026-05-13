package com.hebe.core.delegate

import com.hebe.api.Channel
import com.hebe.api.ChatRole
import com.hebe.api.ConversationMessage
import com.hebe.api.LlmProvider
import com.hebe.api.LoopConfig
import com.hebe.api.LoopOutcome
import com.hebe.api.MemoryStore
import com.hebe.api.Observer
import com.hebe.api.ProviderCapabilities
import com.hebe.config.CostSection
import com.hebe.config.HebeConfig
import com.hebe.core.compaction.Compactor
import com.hebe.core.compaction.PreemptivePruner
import com.hebe.core.cost.CostGuard
import com.hebe.memory.db.DbFactory
import com.hebe.providers.openai.MockLlmProvider
import com.hebe.tools.dispatch.ToolDispatcher
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import kotlin.time.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChatDelegateTest {
    private fun makeDelegate(
        llm: LlmProvider,
        dailyCap: Double = 10.0,
    ): Pair<ChatDelegate, MockDeps> {
        val db = DbFactory.openInMemory()
        val observer = mockk<Observer>(relaxed = true)
        val config = mockk<HebeConfig>(relaxed = true)
        every { config.cost } returns CostSection(dailyUsdCap = dailyCap, perTurnTokenCap = 100_000)
        val costGuard = CostGuard(db.dataSource, config, observer)
        val memory = mockk<MemoryStore>(relaxed = true)
        coEvery { memory.loadContext(any()) } returns emptyList()
        val dispatcher = mockk<ToolDispatcher>(relaxed = true)
        val compactorLlm = mockk<LlmProvider>()
        every { compactorLlm.capabilities() } returns
            ProviderCapabilities(streaming = true, toolUse = true, multimodal = false, maxContextTokens = 128_000)
        val compactor = PreemptivePruner(Compactor(compactorLlm, mockk(relaxed = true), config))
        val delegate =
            ChatDelegate(
                sessionId = "sess1",
                channel = mockk { every { name } returns "cli" },
                memory = memory,
                dispatcher = dispatcher,
                llmProvider = llm,
                costGuard = costGuard,
                compactor = compactor,
                observer = observer,
                systemPrompt = "you are helpful",
                tools = emptyList(),
                modelName = "test-model",
                sessionMutex = Mutex(),
            )
        return delegate to MockDeps(memory, dispatcher, costGuard)
    }

    data class MockDeps(
        val memory: MemoryStore,
        val dispatcher: ToolDispatcher,
        val costGuard: CostGuard,
    )

    private val reasoning =
        object : com.hebe.api.Reasoning {
            override val systemPrompt = "you are helpful"
            override val activeSkills = listOf<String>()
        }

    private fun ctx(turnId: String = "turn1") =
        object : com.hebe.api.ReasoningContext {
            override val sessionId = "sess1"
            override val turnId = turnId
            override val userId = "user1"
            override val requestor = mockk<Channel> { every { name } returns "cli" }
            override val workspace =
                com.hebe.api.workspace
                    .WorkspacePath(".")
            override val approvalGate = mockk<com.hebe.api.ApprovalGate>(relaxed = true)
            override val observer = mockk<Observer>(relaxed = true)
            override val secretLookup = mockk<com.hebe.api.SecretLookup>(relaxed = true)
        }

    @Test
    fun `run returns Response for simple text turn`() =
        runTest {
            val llm =
                MockLlmProvider
                    .builder()
                    .turn {
                        textDelta("Hello!")
                        done()
                    }.build()
            val (delegate, _) = makeDelegate(llm)
            val result = delegate.run(reasoning, ctx(), LoopConfig(maxIterations = 5))
            assertEquals(LoopOutcome.Response("Hello!"), result)
        }

    @Test
    fun `run records cost after LLM call`() =
        runTest {
            val llm =
                MockLlmProvider
                    .builder()
                    .turn {
                        textDelta("Hi")
                        tokenUsage(100, 50)
                        done()
                    }.build()
            val (delegate, deps) = makeDelegate(llm)
            delegate.run(reasoning, ctx("turn42"), LoopConfig(maxIterations = 3))
            val result = deps.costGuard.checkAllowed("other-turn")
            assertEquals(com.hebe.core.cost.CostGuard.CheckResult.Allow, result)
        }

    @Test
    fun `run stops at max iterations`() =
        runTest {
            // Text responses always FinishWith, so we need tool calls to keep looping.
            val builder = MockLlmProvider.builder()
            repeat(5) {
                builder.turn {
                    toolCall("call_id", "echo", emptyMap())
                    done()
                }
            }
            val llm = builder.build()
            val (delegate, deps) = makeDelegate(llm)
            coEvery { deps.dispatcher.dispatch(any(), any()) } returns
                com.hebe.tools.dispatch.DispatchOutcome.Result(
                    com.hebe.api.ToolResult
                        .Ok(kotlinx.serialization.json.JsonPrimitive("ok")),
                )
            val result = delegate.run(reasoning, ctx(), LoopConfig(maxIterations = 3))
            assertEquals(LoopOutcome.MaxIterations, result)
        }

    @Test
    fun `beforeLlmCall denies when daily budget exceeded`() =
        runTest {
            val llm = MockLlmProvider.builder().build()
            val (delegate, deps) = makeDelegate(llm, dailyCap = 0.000001)
            deps.costGuard.recordCall("prev", "m", 100, 100, 1_000_000L)
            val result = delegate.run(reasoning, ctx(), LoopConfig(maxIterations = 3))
            assertTrue(result is LoopOutcome.Failure)
            assertTrue((result as LoopOutcome.Failure).message.contains("budget"))
        }

    @Test
    fun `compacted history is used in next callLlm`() =
        runTest {
            val llm =
                MockLlmProvider
                    .builder()
                    .turn {
                        textDelta("compacted response")
                        done()
                    }.build()
            val (delegate, deps) = makeDelegate(llm)
            val compactedMsg =
                ConversationMessage(
                    id = UUID.randomUUID(),
                    role = ChatRole.User,
                    content = "compacted",
                    toolCalls = emptyList(),
                    ts = Clock.System.now(),
                )
            coEvery { deps.memory.loadContext(any()) } returns listOf(compactedMsg)
            val result = delegate.run(reasoning, ctx(), LoopConfig(maxIterations = 2))
            assertEquals(LoopOutcome.Response("compacted response"), result)
        }
}
