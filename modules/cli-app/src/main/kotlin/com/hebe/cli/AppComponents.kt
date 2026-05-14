@file:Suppress("TooGenericExceptionCaught")

package com.hebe.cli

import com.hebe.config.HebeConfig
import com.hebe.config.SecretStoreProvider
import com.hebe.plugins.install.InstallResult
import com.hebe.plugins.oci.OciClient
import com.hebe.plugins.signature.SignatureVerifier
import com.hebe.security.policy.PolicyChain
import com.hebe.tools.dispatch.ToolDispatcher
import com.hebe.tools.dispatch.ToolRegistry
import java.nio.file.Path
import org.slf4j.Logger

class AppComponents(
    private val config: HebeConfig,
    private val workspaceRoot: Path,
    private val secretStore: SecretStoreProvider,
    private val log: Logger,
) {
    private val validators = PolicyChain.standard(config, workspaceRoot)
    private val pluginsDir: Path = Path.of(System.getProperty("user.home"), ".hebe", "plugins")

    data class DispatchDeps(
        val registry: ToolRegistry,
        val approvalGate: com.hebe.api.ApprovalGate,
        val memory: com.hebe.api.MemoryStore,
        val observer: com.hebe.api.Observer,
        val leakDetector: com.hebe.api.LeakDetector,
        val receipts: com.hebe.api.Receipts,
    )

    fun createToolDispatcher(deps: DispatchDeps): ToolDispatcher =
        ToolDispatcher(
            registry = deps.registry,
            validators = validators,
            approvalGate = deps.approvalGate,
            memory = deps.memory,
            observer = deps.observer,
            leakDetector = deps.leakDetector,
            receipts = deps.receipts,
        )

    suspend fun autoPullPlugins() {
        val autoPull = config.plugins.autoPull
        if (autoPull.isEmpty()) return

        log.info("Auto-pulling {} plugin(s)", autoPull.size)
        val registryHost =
            config.plugins.registry.takeIf { it.isNotBlank() }
                ?: "ghcr.io"

        for (ref in autoPull) {
            try {
                val fullRef = if (ref.contains("/")) ref else "$registryHost/$ref"
                val result = runInstall(fullRef)
                when (result) {
                    is InstallResult.Ok -> {
                        log.info("Auto-pulled plugin {} to {}", result.name, result.extractDir)
                    }

                    is InstallResult.Error -> {
                        log.warn("Auto-pulled failed for {}: {}", ref, result.message)
                    }
                }
            } catch (e: Exception) {
                log.warn("Auto-pulled threw for {}: {}", ref, e.message)
            }
        }
    }

    private suspend fun runInstall(ref: String): InstallResult {
        val verifier =
            SignatureVerifier(
                signatureMode = config.security.pluginSignatureMode,
                trustedPublisherKeys = config.plugins.publisherKeys,
                log = log,
            )
        val ociClient =
            OciClient(
                registry = registryHost,
                secretStore = secretStore,
                log = log,
            )
        val installFlow =
            com.hebe.plugins.install.InstallFlow(
                ociClient = ociClient,
                signatureVerifier = verifier,
                pluginsDir = pluginsDir,
                log = log,
            )
        return installFlow.install(ref)
    }

    private val registryHost: String
        get() = config.plugins.registry.takeIf { it.isNotBlank() } ?: "ghcr.io"
}
