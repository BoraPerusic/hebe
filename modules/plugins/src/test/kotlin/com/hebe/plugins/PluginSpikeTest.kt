@file:Suppress(
    "TooGenericExceptionCaught",
    "MagicNumber",
    "UnusedPrivateProperty",
    "NewLineAtEndOfFile",
    "MaxLineLength",
    "EmptyFunctionBlock",
)

package com.hebe.plugins

import com.hebe.api.ApprovalGate
import com.hebe.api.ApprovalStatus
import com.hebe.api.Channel
import com.hebe.api.ChannelHealth
import com.hebe.api.IncomingMessage
import com.hebe.api.Observer
import com.hebe.api.ObserverEvent
import com.hebe.api.OutboundMessage
import com.hebe.api.ReplyContext
import com.hebe.api.SecretLookup
import com.hebe.api.Span
import com.hebe.api.Tool
import com.hebe.api.ToolContext
import com.hebe.api.ToolResult
import com.hebe.api.workspace.WorkspacePath
import com.hebe.plugins.host.HostFactory
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PluginSpikeTest {
    @Test
    fun `hello plugin loads from local jar and tool callable`() =
        runTest {
            val tempDir = Files.createTempDirectory("hebe-plugin-test")
            val pluginJar = tempDir.resolve("hello-plugin.jar")
            pluginJar.toFile().writeBytes(
                javaClass.getResourceAsStream("/hello-plugin.jar")!!.readBytes(),
            )

            val observer = noOpObserver()
            val lifecycle = NoOpLifecycle(tempDir, observer)
            val pm =
                PluginManager(
                    tempDir,
                    HostFactory({ null }, observer, org.slf4j.LoggerFactory.getLogger("test")),
                    { null },
                    lifecycle,
                )
            pm.start()

            try {
                val tools = pm.tools()
                assertTrue(tools.isNotEmpty(), "Expected at least one tool")
                assertTrue(
                    tools.any { it.spec.name == "hello:say_hello" },
                    "Expected namespaced hello:say_hello tool, got: ${tools.map { it.spec.name }}",
                )

                val tool = tools.first { it.spec.name == "hello:say_hello" }
                val result = tool.invoke(JsonObject(emptyMap()), mockToolContext())
                assertInstanceOf(ToolResult.Ok::class.java, result)
                val okResult = result as ToolResult.Ok
                assertTrue(
                    okResult.content.toString().contains("hello from plugin"),
                    "Expected greeting to contain 'hello from plugin', got: ${okResult.content}",
                )
            } finally {
                try {
                    pm.stop()
                } catch (_: Exception) {
                }
            }
        }

    @Test
    fun `plugin classloader cannot see comhebecore`() =
        runTest {
            val tempDir = Files.createTempDirectory("hebe-plugin-test")
            val pluginJar = tempDir.resolve("hello-plugin.jar")
            pluginJar.toFile().writeBytes(
                javaClass.getResourceAsStream("/hello-plugin.jar")!!.readBytes(),
            )

            val observer = noOpObserver()
            val lifecycle = NoOpLifecycle(tempDir, observer)
            val pm =
                PluginManager(
                    tempDir,
                    HostFactory({ null }, observer, org.slf4j.LoggerFactory.getLogger("test")),
                    { null },
                    lifecycle,
                )
            pm.start()

            try {
                val tools = pm.tools()
                assertTrue(tools.isNotEmpty(), "Expected at least one tool")

                val pluginClass = tools.first().javaClass
                val cl = pluginClass.classLoader

                val coreAccessDenied =
                    try {
                        Class.forName("com.hebe.core.HebeException", false, cl)
                        false
                    } catch (_: ClassNotFoundException) {
                        true
                    }
                assertTrue(coreAccessDenied, "Plugin classloader should NOT see com.hebe.core")

                val apiVisible =
                    try {
                        Class.forName("com.hebe.api.Tool", false, cl)
                        true
                    } catch (_: ClassNotFoundException) {
                        false
                    }
                assertTrue(apiVisible, "Plugin classloader SHOULD see com.hebe.api.Tool")
            } finally {
                try {
                    pm.stop()
                } catch (_: Exception) {
                }
            }
        }

    private fun noOpObserver(): Observer =
        object : Observer {
            override fun event(e: ObserverEvent) {}

            override fun span(
                name: String,
                attrs: Map<String, Any>,
            ): Span =
                object : Span {
                    override fun setAttribute(
                        key: String,
                        value: Any,
                    ) {}

                    override fun recordError(t: Throwable) {}

                    override fun close() {}
                }
        }

    private class NoOpLifecycle(
        pluginDir: java.nio.file.Path,
        observer: Observer,
    ) : Lifecycle(
            pluginManager =
                object : org.pf4j.PluginManager {
                    override fun getPlugins(): MutableList<org.pf4j.PluginWrapper> = mutableListOf()

                    override fun getPlugins(p0: org.pf4j.PluginState): MutableList<org.pf4j.PluginWrapper> = mutableListOf()

                    override fun getResolvedPlugins(): MutableList<org.pf4j.PluginWrapper> = mutableListOf()

                    override fun getUnresolvedPlugins(): MutableList<org.pf4j.PluginWrapper> = mutableListOf()

                    override fun getStartedPlugins(): MutableList<org.pf4j.PluginWrapper> = mutableListOf()

                    override fun getPlugin(pluginId: String): org.pf4j.PluginWrapper? = null

                    override fun loadPlugins() {}

                    override fun loadPlugin(pluginPath: java.nio.file.Path): String = ""

                    override fun startPlugins() {}

                    override fun startPlugin(pluginId: String): org.pf4j.PluginState = org.pf4j.PluginState.STOPPED

                    override fun stopPlugins() {}

                    override fun stopPlugin(pluginId: String): org.pf4j.PluginState = org.pf4j.PluginState.STOPPED

                    override fun unloadPlugins() {}

                    override fun unloadPlugin(pluginId: String): Boolean = false

                    override fun disablePlugin(pluginId: String): Boolean = false

                    override fun enablePlugin(pluginId: String): Boolean = false

                    override fun deletePlugin(pluginId: String): Boolean = false

                    override fun getPluginClassLoader(pluginId: String): ClassLoader = ClassLoader.getSystemClassLoader()

                    override fun getExtensionClasses(pluginId: String): MutableList<Class<*>> = mutableListOf()

                    override fun <T : Any> getExtensionClasses(p0: Class<T>): MutableList<Class<out T>> = mutableListOf()

                    override fun <T : Any> getExtensionClasses(
                        p0: Class<T>,
                        p1: String,
                    ): MutableList<Class<out T>> = mutableListOf()

                    override fun <T : Any> getExtensions(p0: Class<T>): MutableList<T> = mutableListOf()

                    override fun <T : Any> getExtensions(
                        p0: Class<T>,
                        p1: String,
                    ): MutableList<T> = mutableListOf()

                    override fun getExtensions(pluginId: String): MutableList<Any?> = mutableListOf()

                    override fun getExtensionClassNames(pluginId: String): MutableSet<String> = mutableSetOf()

                    override fun getExtensionFactory(): org.pf4j.ExtensionFactory = throw UnsupportedOperationException()

                    override fun getRuntimeMode(): org.pf4j.RuntimeMode = org.pf4j.RuntimeMode.DEVELOPMENT

                    override fun whichPlugin(p0: Class<*>): org.pf4j.PluginWrapper? = null

                    override fun addPluginStateListener(p0: org.pf4j.PluginStateListener) {}

                    override fun removePluginStateListener(p0: org.pf4j.PluginStateListener) {}

                    override fun setSystemVersion(p0: String) {}

                    override fun getSystemVersion(): String = "1.0.0"

                    override fun getPluginsRoot(): java.nio.file.Path = pluginDir

                    override fun getPluginsRoots(): MutableList<java.nio.file.Path> = mutableListOf(pluginDir)

                    override fun getVersionManager(): org.pf4j.VersionManager = throw UnsupportedOperationException()
                },
            pluginDir = pluginDir,
            toolRegistry =
                object : Lifecycle.ToolRegistryWrapper {
                    override fun register(
                        name: String,
                        tool: Tool,
                    ) {}

                    override fun unregister(name: String) {}
                },
            hostFactory = HostFactory({ null }, observer, org.slf4j.LoggerFactory.getLogger("test")),
            signatureVerifier =
                object : com.hebe.plugins.signature.SignatureVerifier(
                    signatureMode = com.hebe.config.PluginSignatureMode.DISABLED,
                    trustedPublisherKeys = emptyList(),
                    log = org.slf4j.LoggerFactory.getLogger("test"),
                ) {},
            observer = observer,
            pluginStore = PluginRegistrationStore(),
            secretResolver = { null },
            log = org.slf4j.LoggerFactory.getLogger("test"),
        ) {
        override fun afterStart(wrapper: org.pf4j.PluginWrapper) {}

        override fun beforeStop(wrapper: org.pf4j.PluginWrapper) {}
    }

    private fun mockToolContext() =
        object : ToolContext {
            override val sessionId: String = "test-session"
            override val turnId: String = "test-turn"
            override val userId: String = "test-user"
            override val requestor: Channel =
                object : Channel {
                    override val name: String = "test"

                    override suspend fun start(scope: CoroutineScope): Flow<IncomingMessage> = flowOf()

                    override suspend fun reply(
                        ctx: ReplyContext,
                        msg: OutboundMessage,
                    ) = Unit

                    override suspend fun healthCheck(): ChannelHealth = ChannelHealth.Up

                    override suspend fun shutdown() = Unit
                }
            override val workspace: WorkspacePath = WorkspacePath("tmp")
            override val approvalGate: ApprovalGate =
                object : ApprovalGate {
                    override fun requestIfNeeded(
                        tool: Tool,
                        args: JsonObject,
                        turnId: String,
                        channel: String,
                        threadExtId: String?,
                    ): Flow<ApprovalStatus> = flowOf(ApprovalStatus.Approved)

                    override suspend fun awaitApproval(
                        tool: Tool,
                        args: JsonObject,
                        turnId: String,
                        channel: String,
                        threadExtId: String?,
                    ) = true

                    override fun resolve(
                        approvalId: String,
                        approved: Boolean,
                    ) = false
                }
            override val observer: Observer =
                object : Observer {
                    override fun event(e: ObserverEvent) = Unit

                    override fun span(
                        name: String,
                        attrs: Map<String, Any>,
                    ): Span =
                        object : Span {
                            override fun setAttribute(
                                key: String,
                                value: Any,
                            ) = Unit

                            override fun recordError(t: Throwable) = Unit

                            override fun close() = Unit
                        }
                }
            override val secretLookup: SecretLookup =
                object : SecretLookup {
                    override fun secret(name: String): String? = null
                }
        }
}
