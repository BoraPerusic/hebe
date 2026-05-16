package com.hebe.scheduler

import com.hebe.api.ApprovalGate
import com.hebe.api.Channel
import com.hebe.api.ChatRole
import com.hebe.api.ConversationMessage
import com.hebe.api.LlmProvider
import com.hebe.api.LoopConfig
import com.hebe.api.LoopOutcome
import com.hebe.api.MemoryStore
import com.hebe.api.Observer
import com.hebe.api.Reasoning
import com.hebe.api.ReasoningContext
import com.hebe.api.SecretLookup
import com.hebe.api.ToolSpec
import com.hebe.api.workspace.WorkspacePath
import com.hebe.core.compaction.PreemptivePruner
import com.hebe.core.cost.CostGuard
import com.hebe.core.delegate.JobDelegate
import com.hebe.tools.dispatch.ToolDispatcher
import java.util.UUID
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.slf4j.LoggerFactory

@Suppress("LongParameterList", "EmptyFunctionBlock", "TooGenericExceptionCaught")
class JobRunner(
    private val repo: JobRepo,
    private val memory: MemoryStore,
    private val dispatcher: ToolDispatcher,
    private val llmProvider: LlmProvider,
    private val costGuard: CostGuard,
    private val compactor: PreemptivePruner,
    private val observer: Observer,
    private val modelName: String,
    private val systemPrompt: String,
    private val tools: List<ToolSpec>,
    private val maintenanceHandlers: Map<String, suspend (job: Job) -> Boolean> = emptyMap(),
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    suspend fun run(job: Job): Boolean =
        try {
            when (job.kind) {
                "routine" -> runRoutine(job)
                "adhoc" -> runAdhoc(job)
                "maintenance" -> runMaintenance(job)
                else -> {
                    logger.warn("job={} unknown kind={}", job.id, job.kind)
                    true
                }
            }
        } catch (e: Exception) {
            logger.error("job={} run failed: {}", job.id, e.message, e)
            false
        }

    private suspend fun runMaintenance(job: Job): Boolean {
        val taskName =
            job.payload()["task"] ?: run {
                logger.warn("job={} maintenance missing task", job.id)
                return false
            }
        val handler =
            maintenanceHandlers[taskName] ?: run {
                logger.warn("job={} maintenance unknown task={}", job.id, taskName)
                return false
            }
        return handler(job)
    }

    private suspend fun runRoutine(job: Job): Boolean {
        val payload = job.payload()
        val routineId =
            payload["routine_id"] ?: run {
                logger.warn("job={} routine missing routine_id", job.id)
                return false
            }

        val routine =
            repo.loadRoutine(routineId) ?: run {
                logger.warn("job={} routine {} not found", job.id, routineId)
                return false
            }

        logger.info("job={} running routine {} ({})", job.id, routineId, routine.name)

        val bodyPrompt = buildBodyPrompt(routine.bodyKind, routine.bodyRef, routine.bodyJson)
        return runTurn(job.id, bodyPrompt)
    }

    private suspend fun runAdhoc(job: Job): Boolean {
        val payload = job.payload()
        val prompt =
            payload["prompt"] ?: run {
                logger.warn("job={} adhoc missing prompt", job.id)
                return false
            }

        logger.info("job={} running adhoc", job.id)
        return runTurn(job.id, prompt)
    }

    private suspend fun runTurn(
        jobId: String,
        prompt: String,
    ): Boolean {
        val delegate =
            JobDelegate(
                jobId = jobId,
                memory = memory,
                dispatcher = dispatcher,
                llmProvider = llmProvider,
                costGuard = costGuard,
                compactor = compactor,
                observer = observer,
                systemPrompt = systemPrompt,
                tools = tools,
                modelName = modelName,
            )

        val ctx = makeReasoningContext(jobId)
        val reasoning =
            object : Reasoning {
                override val systemPrompt: String = this@JobRunner.systemPrompt
                override val activeSkills: List<String> = emptyList()
                override val latestUserMessage: String = prompt
            }

        memory.appendMessage(
            "job:$jobId",
            ConversationMessage(
                id = UUID.randomUUID(),
                role = ChatRole.User,
                content = prompt,
                toolCalls = emptyList(),
                ts = Clock.System.now(),
            ),
        )

        val outcome = delegate.run(reasoning, ctx, LoopConfig())
        return outcome is LoopOutcome.Response || outcome is LoopOutcome.Stopped
    }

    private fun makeReasoningContext(jobId: String): ReasoningContext =
        object : ReasoningContext {
            override val sessionId: String = "job:$jobId"
            override val turnId: String = UUID.randomUUID().toString()
            override val userId: String = "scheduler"
            override val requestor: Channel =
                object : Channel {
                    override val name: String = "scheduler"

                    override suspend fun start(scope: CoroutineScope): Flow<com.hebe.api.IncomingMessage> = flow { }

                    @Suppress("EmptyFunctionBlock")
                    override suspend fun reply(
                        ctx: com.hebe.api.ReplyContext,
                        msg: com.hebe.api.OutboundMessage,
                    ) {}

                    override suspend fun healthCheck(): com.hebe.api.ChannelHealth = com.hebe.api.ChannelHealth.Up

                    @Suppress("EmptyFunctionBlock")
                    override suspend fun shutdown() {}
                }
            override val workspace: WorkspacePath = WorkspacePath("scheduler")
            override val approvalGate: ApprovalGate = DenyAllApprovalGate
            override val observer: Observer = this@JobRunner.observer
            override val secretLookup: SecretLookup =
                object : SecretLookup {
                    override fun secret(name: String): String? = null
                }
        }

    private fun buildBodyPrompt(
        bodyKind: String,
        bodyRef: String,
        bodyJson: String?,
    ): String =
        when (bodyKind) {
            "tool" -> "Run tool: $bodyRef with args: ${bodyJson ?: "{}"}"
            "skill" -> "Run skill: $bodyRef with params: ${bodyJson ?: "{}"}"
            else -> bodyRef
        }
}
