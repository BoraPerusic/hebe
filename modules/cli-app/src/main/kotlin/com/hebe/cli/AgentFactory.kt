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
import com.hebe.memory.embeddings.CachedEmbeddingProvider
import com.hebe.memory.embeddings.OpenAiCompatEmbeddingProvider
import com.hebe.memory.hygiene.HygieneScanner
import com.hebe.plugins.HebePluginManager
import com.hebe.plugins.Lifecycle
import com.hebe.plugins.PluginRegistrationStore
import com.hebe.plugins.host.HostFactory
import com.hebe.plugins.signature.SignatureVerifier
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
import com.hebe.tools.builtin.git.GitTool
import com.hebe.tools.builtin.http.HttpTool
import com.hebe.tools.builtin.memory.MemoryReadTool
import com.hebe.tools.builtin.memory.MemorySearchTool
import com.hebe.tools.builtin.memory.MemoryWriteTool
import com.hebe.tools.builtin.schedule.ScheduleTool
import com.hebe.tools.builtin.search.WebSearchTool
import com.hebe.tools.builtin.shell.ShellTool
import com.hebe.tools.dispatch.ToolDispatcher
import com.hebe.tools.dispatch.ToolRegistry
import com.hebe.tools.mcp.McpClientManager
import com.hebe.memory.SqliteMemoryStore
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.pf4j.DefaultPluginManager
import org.slf4j.Logger

object AgentFactory {
    data class AgentComponents(
        val agent: HebeAgent,
        val dispatcher: ToolDispatcher,
        val channelManager: ChannelManagerImpl,
        val webChannel: WebChannel,
        val telegramChannel: TelegramChannel?,
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

        // ── Memory store ────────────────────────────────────────────────────

        val embeddingProvider =
            CachedEmbeddingProvider(
                OpenAiCompatEmbeddingProvider(
                    client = httpClient,
                    baseUrl = config.llm.baseUrl,
                    apiKey = llmApiKey,
                    model = config.llm.embeddingModel.ifEmpty { "text-embedding-3-small" },
                    dim = config.llm.embeddingDim,
                ),
            )
        val hygieneScanner = HygieneScanner()
        val memoryStore: MemoryStore =
            SqliteMemoryStore(
                db = memoryDb,
                workspaceFs = workspaceFs,
                embeddings = embeddingProvider,
                hygieneScanner = hygieneScanner,
                observer = observer,
            )

        // ── Tool stack ────────────────────────────────────────────────────

        val registry = ToolRegistry()
        val secretLookup = buildSecretLookup(secretStore)
        registerBuiltinTools(registry, workspaceFs, secretLookup, memoryStore)

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
                        secretStore
                            .get(config.channels.telegram.botTokenSecret)
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

        val secretLookupForAgent = buildSecretLookup(secretStore)
        val systemPrompt = "You are Hebe, a local AI agent. Running with autonomy level: ${config.autonomy.level.name}."

        val agentToolsProvider = registry::list

        val agent =
            HebeAgent(
                sessionManager =
                    com.hebe.core.agent
                        .SessionManager(),
                submissionParser = SubmissionParser,
                channel = dummyChannel,
                memory = memoryStore,
                dispatcher = dispatcher,
                llmProvider = llmProvider,
                costGuard = costGuard,
                compactor = compactor,
                hooks =
                    com.hebe.core.hooks
                        .HookRunner(),
                observer = observer,
                approvalGate = approvalGate,
                secretLookup = secretLookupForAgent,
                secretStore = secretStore,
                systemPrompt = systemPrompt,
                toolsProvider = { _ -> agentToolsProvider().map { it.spec } },
                activeSkills = emptyList(),
            )

        val channelManager = ChannelManagerImpl(agent, observer)

        // ── Plugin loading ────────────────────────────────────────────────

        val pluginStore = PluginRegistrationStore()
        val pluginRegistryWrapper =
            object : Lifecycle.ToolRegistryWrapper {
                override fun register(
                    name: String,
                    tool: com.hebe.api.Tool,
                ) {
                    registry.register(tool)
                }

                override fun unregister(name: String) {
                    registry.unregister(name)
                }
            }
        val hostFactory =
            HostFactory(
                secretResolver = { name -> runBlocking { secretStore.get(name)?.let { String(it, Charsets.UTF_8) } } },
                observer = observer,
                logger = log,
            )
        val signatureVerifier =
            SignatureVerifier(
                signatureMode = config.security.pluginSignatureMode,
                trustedPublisherKeys = config.plugins.publisherKeys,
                log = log,
            )
        // DefaultPluginManager() breaks the Lifecycle↔HebePluginManager circular dependency;
        // its stopPlugin is only reached on plugin startup failures (error path, caught).
        val pluginLifecycle =
            Lifecycle(
                pluginManager = DefaultPluginManager(),
                pluginDir = pluginsDir,
                toolRegistry = pluginRegistryWrapper,
                hostFactory = hostFactory,
                signatureVerifier = signatureVerifier,
                observer = observer,
                pluginStore = pluginStore,
                secretResolver = { name -> runBlocking { secretStore.get(name)?.let { String(it, Charsets.UTF_8) } } },
                log = log,
            )
        val pluginManager = HebePluginManager(pluginsDir, pluginLifecycle)
        pluginManager.loadPlugins()
        pluginManager.startPlugins()

        // ── Scheduler ─────────────────────────────────────────────────────

        val jobRepo = com.hebe.scheduler.JobRepo(memoryDb)
        val routinesEngine = com.hebe.scheduler.RoutinesEngine(jobRepo)
        val jobRunner =
            com.hebe.scheduler.JobRunner(
                repo = jobRepo,
                memory = memoryStore,
                dispatcher = dispatcher,
                llmProvider = llmProvider,
                costGuard = costGuard,
                compactor = compactor,
                observer = observer,
                modelName = config.llm.defaultModel,
                systemPrompt = systemPrompt,
                tools = registry.list().map { it.spec },
            )
        val schedulerImpl = com.hebe.scheduler.Scheduler(jobRepo, jobRunner, routinesEngine)
        val schedulerFacade =
            object : SchedulerFacade {
                override fun start(scope: CoroutineScope) {
                    schedulerImpl.start(scope)
                }
            }

        // ── MCP client ─────────────────────────────────────────────────────

        val mcpClientManagerImpl =
            McpClientManager(
                registry = registry,
                secretLookup = secretLookup,
            )
        runBlocking {
            if (config.mcp.client.servers.isNotEmpty()) {
                mcpClientManagerImpl.connect(config.mcp.client.servers)
            }
        }
        val mcpClientManagerFacade =
            object : McpClientManagerFacade {
                override suspend fun disconnectAll() {
                    mcpClientManagerImpl.disconnectAll()
                }
            }

        // ── Shutdown ───────────────────────────────────────────────────────

        val shutdown: suspend () -> Unit = {
            log.info("shutting down agent components")
            runBlocking {
                channelManager.shutdown()
                mcpClientManagerImpl.disconnectAll()
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
            telegramChannel = telegramChannel,
            scheduler = schedulerFacade,
            mcpClientManager = mcpClientManagerFacade,
            shutdown = shutdown,
        )
    }

    private fun registerBuiltinTools(
        registry: ToolRegistry,
        workspaceFs: WorkspaceFs,
        secretLookup: com.hebe.api.SecretLookup,
        memoryStore: MemoryStore,
    ) {
        registry.register(FileSystemReadTool(workspaceFs))
        registry.register(FileSystemWriteTool(workspaceFs))
        registry.register(FileSystemAppendTool(workspaceFs))
        registry.register(FileSystemListTool(workspaceFs))
        registry.register(FileSystemGlobTool(workspaceFs))
        registry.register(ShellTool(workspaceFs.workspaceRoot))
        registry.register(HttpTool(secretLookup))
        registry.register(AskUserTool())
        registry.register(WebSearchTool(secretLookup))
        registry.register(MemoryReadTool(memoryStore))
        registry.register(MemoryWriteTool(memoryStore))
        registry.register(MemorySearchTool(memoryStore))
        registry.register(ScheduleTool())
        registry.register(GitTool(workspaceFs.workspaceRoot))
    }

    private fun buildSecretLookup(secretStore: SecretStoreProvider): com.hebe.api.SecretLookup =
        object : com.hebe.api.SecretLookup {
            override fun secret(name: String): String? = runBlocking { secretStore.get(name)?.let { String(it, Charsets.UTF_8) } }
        }

    private val dummyChannel: Channel =
        object : Channel {
            override val name: String = "agent-factory"

            override suspend fun start(scope: CoroutineScope): Flow<IncomingMessage> = flowOf()

            override suspend fun reply(
                ctx: ReplyContext,
                msg: OutboundMessage,
            ) {}

            override fun supportsDraftUpdates(): Boolean = false

            override suspend fun updateDraft(
                ctx: ReplyContext,
                partial: String,
            ) {}

            override suspend fun broadcast(
                userId: String,
                msg: OutboundMessage,
            ) {}

            override suspend fun healthCheck(): ChannelHealth = ChannelHealth.Up

            override suspend fun shutdown() {}
        }
}
