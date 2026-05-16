@file:Suppress(
    "LongParameterList",
    "TooGenericExceptionCaught",
    "MagicNumber",
    "NestedBlockDepth",
)

package com.hebe.cli

import com.hebe.api.Channel
import com.hebe.api.ChannelHealth
import com.hebe.api.IncomingMessage
import com.hebe.api.MemoryStore
import com.hebe.api.Observer
import com.hebe.api.OutboundMessage
import com.hebe.api.ReplyContext
import com.hebe.api.SecretLookup
import com.hebe.api.ToolSpec
import com.hebe.channels.ChannelManagerImpl
import com.hebe.channels.telegram.TelegramChannel
import com.hebe.channels.web.WebChannel
import com.hebe.config.HebeConfig
import com.hebe.config.SecretStoreProvider
import com.hebe.core.agent.HebeAgent
import com.hebe.core.compaction.Compactor
import com.hebe.core.compaction.PreemptivePruner
import com.hebe.core.cost.CostGuard
import com.hebe.core.submission.SubmissionParser
import com.hebe.memory.db.DbFactory
import com.hebe.memory.workspace.WorkspaceFs
import com.hebe.memory.workspace.WorkspaceSeeder
import com.hebe.plugins.HebePluginManager
import com.hebe.providers.openai.HttpClientFactory
import com.hebe.providers.openai.OpenAiCompatProvider
import com.hebe.security.approval.ApprovalGate
import com.hebe.security.approval.PendingApprovalsRepo
import com.hebe.security.policy.LeakDetector
import com.hebe.security.policy.PolicyChain
import com.hebe.security.receipts.Receipts
import com.hebe.security.receipts.SigningKey
import com.hebe.tools.builtin.ask.AskUserTool
import com.hebe.tools.builtin.file.FileSystemAppendTool
import com.hebe.tools.builtin.file.FileSystemGlobTool
import com.hebe.tools.builtin.file.FileSystemListTool
import com.hebe.tools.builtin.file.FileSystemReadTool
import com.hebe.tools.builtin.file.FileSystemWriteTool
import com.hebe.tools.builtin.http.HttpTool
import com.hebe.tools.builtin.shell.ShellTool
import com.hebe.tools.dispatch.ToolDispatcher
import com.hebe.tools.dispatch.ToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.slf4j.Logger
import java.nio.file.Path

object AgentFactory {

    data class AgentComponents(
        val agent: HebeAgent,
        val dispatcher: ToolDispatcher,
        val channelManager: ChannelManagerImpl,
        val webChannel: WebChannel,
        val scheduler: SchedulerFacade?,
        val mcpClientManager: McpClientManagerFacade?,
        val shutdown: suspend () -> Unit,
    )

    interface SchedulerFacade {
        fun start(scope: CoroutineScope)
    }

    interface McpClientManagerFacade {
        suspend fun disconnectAll()
    }

    fun build(
        config: HebeConfig,
        secretStore: SecretStoreProvider,
        workspaceRoot: Path,
        observer: Observer,
        log: Logger,
    ): AgentComponents {
        val receiptsDir = workspaceRoot.resolve("receipts")
        val pluginsDir = workspaceRoot.resolve("plugins")
        val dbPath = workspaceRoot.resolve("hebe.db")

        // ── Infrastructure ────────────────────────────────────────────────

        val signingKey = runBlocking { SigningKey.bootstrap(secretStore) }
        val receipts = Receipts(receiptsDir, signingKey)

        val memoryDb = DbFactory.open(dbPath, observer)

        val workspaceFs = WorkspaceFs(workspaceRoot)
        WorkspaceSeeder.seedIfMissing(workspaceFs, workspaceRoot)

        // ── LLM provider ─────────────────────────────────────────────────

        val llmApiKey =
            runBlocking {
                secretStore.get(config.llm.apiKeySecret)?.let { String(it, Charsets.UTF_8) } ?: ""
            }

        val httpClient = HttpClientFactory.create(llmApiKey)

        val llmProvider: com.hebe.api.LlmProvider =
            OpenAiCompatProvider(
                baseUrl = config.llm.baseUrl,
                defaultModel = config.llm.defaultModel,
                httpClient = httpClient,
            )

        // ── Memory store (placeholder - SqliteMemoryStore needs more deps) ──
        // TODO: properly construct SqliteMemoryStore with EmbeddingProvider + HygieneScanner
        val memoryStore: MemoryStore = MemoryStorePlaceholder()

        // ── Tool stack ────────────────────────────────────────────────────

        val registry = ToolRegistry()
        registerBuiltinTools(registry, workspaceRoot)

        val validators = PolicyChain.standard(config, workspaceRoot)

        val pendingApprovalsRepo = PendingApprovalsRepo(memoryDb.dataSource)
        val approvalGate: ApprovalGate = ApprovalGate(pendingApprovalsRepo)

        val leakDetector = LeakDetector()

        val dispatcher =
            ToolDispatcher(
                registry = registry,
                validators = validators,
                approvalGate = approvalGate,
                memory = memoryStore,
                observer = observer,
                leakDetector = leakDetector,
                receipts = receipts,
            )

        // ── Cost guard ─────────────────────────────────────────────────
        val costGuard = CostGuard(memoryDb.dataSource, config, observer)

        // ── Compactor ─────────────────────────────────────────────────
        val compactorInstance = Compactor(llmProvider, workspaceFs, config)
        val compactor = PreemptivePruner(compactorInstance)

        // ── Channels ──────────────────────────────────────────────────────

        val webChannel = WebChannel()

        val telegramChannel: TelegramChannel? =
            if (config.channels.telegram.enabled) {
                val botToken =
                    runBlocking {
                        secretStore.get(config.channels.telegram.botTokenSecret)
                            ?.let { String(it, Charsets.UTF_8) }
                            ?: ""
                    }
                if (botToken.isNotEmpty()) {
                    TelegramChannel(
                        botToken = botToken,
                        operatorTelegramId = config.channels.telegram.operatorTelegramId,
                    )
                } else {
                    log.warn("Telegram channel enabled but bot token not found in secret store")
                    null
                }
            } else {
                null
            }

        // ── Build agent ───────────────────────────────────────────────────

        val secretLookup = buildSecretLookup(secretStore)
        val systemPrompt = "You are Hebe, a local AI agent. Running with autonomy level: ${config.autonomy.level.name}."

        val agent =
            HebeAgent(
                sessionManager = com.hebe.core.agent.SessionManager(),
                submissionParser = SubmissionParser(),
                channel = dummyChannel,
                memory = memoryStore,
                dispatcher = dispatcher,
                llmProvider = llmProvider,
                costGuard = costGuard,
                compactor = compactor,
                hooks = com.hebe.core.hooks.HookRunner(),
                observer = observer,
                approvalGate = approvalGate,
                secretLookup = secretLookup,
                secretStore = secretStore,
                systemPrompt = systemPrompt,
                toolsProvider = { _ -> emptyList() },
                activeSkills = emptyList(),
            )

        val channelManager = ChannelManagerImpl(agent, observer)

        // ── Plugin loading ────────────────────────────────────────────────

        val pluginManager = HebePluginManager(pluginsDir)
        if (pluginManager.plugins.isNotEmpty()) {
            pluginManager.loadPlugins()
            pluginManager.startPlugins()
        }

        // ── Shutdown ───────────────────────────────────────────────────────

        val shutdown: suspend () -> Unit = {
            log.info("shutting down agent components")
            runBlocking {
                channelManager.shutdown()
                memoryDb.close()
                pluginManager.stopPlugins()
                pluginManager.unloadPlugins()
                httpClient.close()
            }
        }

        return AgentComponents(
            agent = agent,
            dispatcher = dispatcher,
            channelManager = channelManager,
            webChannel = webChannel,
            scheduler = null,
            mcpClientManager = null,
            shutdown = shutdown,
        )
    }

    private fun registerBuiltinTools(
        registry: ToolRegistry,
        workspaceRoot: Path,
    ) {
        registry.register(FileSystemReadTool(workspaceRoot))
        registry.register(FileSystemWriteTool(workspaceRoot))
        registry.register(FileSystemAppendTool(workspaceRoot))
        registry.register(FileSystemListTool(workspaceRoot))
        registry.register(FileSystemGlobTool(workspaceRoot))
        registry.register(ShellTool(workspaceRoot))
        registry.register(HttpTool(buildSecretLookupForBuiltin(secretStoreProviderForTools())))
        registry.register(AskUserTool())
    }

    private fun buildSecretLookup(secretStore: SecretStoreProvider): SecretLookup =
        object : SecretLookup {
            override fun secret(name: String): String? =
                runBlocking { secretStore.get(name)?.let { String(it, Charsets.UTF_8) } }
        }

    private fun buildSecretLookupForBuiltin(secretStore: SecretStoreProvider): com.hebe.api.SecretLookup =
        object : com.hebe.api.SecretLookup {
            override fun secret(name: String): String? =
                runBlocking { secretStore.get(name)?.let { String(it, Charsets.UTF_8) } }
        }

    private fun secretStoreProviderForTools(): SecretStoreProvider =
        object : SecretStoreProvider {
            override suspend fun get(key: String): ByteArray? = null
            override suspend fun set(key: String, value: ByteArray) {}
            override suspend fun delete(key: String): Boolean = false
            override suspend fun list(): List<String> = emptyList()
        }

    private val dummyChannel: Channel =
        object : Channel {
            override val name: String = "agent-factory"
            override suspend fun start(scope: CoroutineScope): Flow<IncomingMessage> = flowOf()
            override suspend fun reply(ctx: ReplyContext, msg: OutboundMessage) {}
            override fun supportsDraftUpdates(): Boolean = false
            override suspend fun updateDraft(ctx: ReplyContext, partial: String) {}
            override suspend fun broadcast(userId: String, msg: OutboundMessage) {}
            override suspend fun healthCheck(): ChannelHealth = ChannelHealth.Up
            override suspend fun shutdown() {}
        }

    private class MemoryStorePlaceholder : MemoryStore {
        override suspend fun appendMessage(conversationId: String, msg: com.hebe.api.ConversationMessage) {}
        override suspend fun loadContext(conversationId: String, limit: Int): List<com.hebe.api.ConversationMessage> = emptyList()
        override suspend fun search(query: String, k: Int, scope: com.hebe.api.MemoryScope, categories: Set<com.hebe.api.MemoryCategory>?): List<com.hebe.api.MemoryHit> = emptyList()
        override suspend fun appendDoc(path: String, content: String, scope: com.hebe.api.MemoryScope, category: com.hebe.api.MemoryCategory) {}
        override suspend fun readDoc(path: String): String? = null
        override suspend fun listDocs(prefix: String): List<String> = emptyList()
        override suspend fun systemPrompt(isGroup: Boolean): String = ""
        override suspend fun snapshot(): com.hebe.api.MemorySnapshot = com.hebe.api.MemorySnapshot(0, 0, 0)
    }
}