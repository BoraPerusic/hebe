package com.hebe.core.agent

import com.hebe.api.ApprovalGate
import com.hebe.api.Channel
import com.hebe.api.HandleOutcome
import com.hebe.api.IncomingMessage
import com.hebe.api.LlmProvider
import com.hebe.api.LoopConfig
import com.hebe.api.LoopOutcome
import com.hebe.api.MemoryStore
import com.hebe.api.Observer
import com.hebe.api.OutboundMessage
import com.hebe.api.Reasoning
import com.hebe.api.ReasoningContext
import com.hebe.api.SecretLookup
import com.hebe.api.SlashCommand
import com.hebe.api.Submission
import com.hebe.api.ToolSpec
import com.hebe.api.workspace.WorkspacePath
import com.hebe.config.SecretStoreProvider
import com.hebe.core.compaction.PreemptivePruner
import com.hebe.core.cost.CostGuard
import com.hebe.core.delegate.ChatDelegate
import com.hebe.core.hooks.HookRunner
import com.hebe.core.submission.SubmissionParser
import com.hebe.tools.dispatch.ToolDispatcher
import java.util.UUID
import org.slf4j.LoggerFactory

class HebeAgent(
    private val sessionManager: SessionManager,
    private val submissionParser: SubmissionParser,
    private val channel: Channel,
    private val memory: MemoryStore,
    private val dispatcher: ToolDispatcher,
    private val llmProvider: LlmProvider,
    private val costGuard: CostGuard,
    private val compactor: PreemptivePruner,
    private val hooks: HookRunner,
    private val observer: Observer,
    private val approvalGate: ApprovalGate,
    private val secretLookup: SecretLookup,
    private val secretStore: SecretStoreProvider,
    private val systemPrompt: String,
    private val tools: List<ToolSpec>,
    private val activeSkills: List<String>,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val modelName: String by lazy { llmProvider.capabilities().defaultModel.ifEmpty { "default" } }

    suspend fun handleMessage(msg: IncomingMessage): HandleOutcome {
        val inboundMsg = hooks.runBeforeInbound(msg) ?: return HandleOutcome.NoResponse("suppressed by hook")

        val submission = submissionParser.parse(inboundMsg)

        val outcome =
            when (submission) {
                is Submission.UserInput -> handleUserInput(submission, inboundMsg)
                is Submission.SystemCommand -> handleSystemCommand(submission, inboundMsg)
                is Submission.Approval -> handleApproval(submission)
                is Submission.AuthMode -> handleAuthMode(submission)
                is Submission.QuitCommand -> handleQuit()
            }

        val outbound =
            when (outcome) {
                is LoopOutcome.Response -> OutboundMessage(text = outcome.text)
                is LoopOutcome.MaxIterations -> OutboundMessage(text = "Max iterations reached")
                is LoopOutcome.Stopped -> OutboundMessage(text = "Stopped")
                is LoopOutcome.Failure -> OutboundMessage(text = "Error: ${outcome.message}")
                is LoopOutcome.NeedApproval -> {
                    OutboundMessage(
                        text = "Approval required",
                        approvalRequest = outcome.request,
                    )
                }
                is LoopOutcome.AuthPending -> {
                    OutboundMessage(text = "Auth pending: ${outcome.purpose}")
                }
            }
        val finalOutbound = hooks.runBeforeOutbound(outbound) ?: outbound
        return HandleOutcome.Done(finalOutbound)
    }

    private suspend fun handleUserInput(
        submission: Submission.UserInput,
        msg: IncomingMessage,
    ): LoopOutcome {
        val sessionId = msg.channel
        val sessionMutex = sessionManager.getOrCreate(sessionId)
        val turnId = UUID.randomUUID().toString()

        val delegate =
            ChatDelegate(
                sessionId = sessionId,
                channel = channel,
                memory = memory,
                dispatcher = dispatcher,
                llmProvider = llmProvider,
                costGuard = costGuard,
                compactor = compactor,
                observer = observer,
                systemPrompt = systemPrompt,
                tools = tools,
                modelName = modelName,
                sessionMutex = sessionMutex,
            )

        val reasoning =
            object : Reasoning {
                override val systemPrompt: String = this@HebeAgent.systemPrompt
                override val activeSkills: List<String> = this@HebeAgent.activeSkills
            }

        val ctx =
            object : ReasoningContext {
                override val sessionId: String = sessionId
                override val turnId: String = turnId
                override val userId: String = msg.userId
                override val requestor: Channel = channel
                override val workspace: WorkspacePath = WorkspacePath(".")
                override val approvalGate: ApprovalGate = this@HebeAgent.approvalGate
                override val observer: Observer = this@HebeAgent.observer
                override val secretLookup: SecretLookup = this@HebeAgent.secretLookup
            }

        return delegate.run(reasoning, ctx, LoopConfig())
    }

    private suspend fun handleSystemCommand(
        submission: Submission.SystemCommand,
        msg: IncomingMessage,
    ): LoopOutcome =
        when (submission.command) {
            is SlashCommand.Compact -> {
                val sessionId = msg.channel
                val history = memory.loadContext(sessionId)
                compactor.prune(history, UUID.randomUUID().toString())
                LoopOutcome.Response("Compaction complete")
            }
            SlashCommand.Status -> LoopOutcome.Response("Status: running")
            SlashCommand.Help -> LoopOutcome.Response("Available commands: /compact, /status, /help, /skills, /quit")
            is SlashCommand.SkillList -> LoopOutcome.Response("Skills: ${activeSkills.joinToString(", ")}")
        }

    private suspend fun handleApproval(submission: Submission.Approval): LoopOutcome {
        val resolved = approvalGate.resolve(submission.approvalId, submission.approved)
        val msg =
            if (resolved) "Approval ${submission.approvalId} resolved: ${submission.approved}" else "Approval not found"
        return LoopOutcome.Response(msg)
    }

    private suspend fun handleAuthMode(submission: Submission.AuthMode): LoopOutcome {
        return try {
            secretStore.set(submission.purpose, submission.secret.toByteArray(Charsets.UTF_8))
            logger.debug("credential stored for purpose={}", submission.purpose)
            LoopOutcome.Response("[credential stored]")
        } catch (e: Exception) {
            logger.error("failed to store credential for purpose={}", submission.purpose, e)
            LoopOutcome.Failure("failed to store credential: ${e.message}")
        }
    }

    private fun handleQuit(): LoopOutcome = LoopOutcome.Stopped
}
