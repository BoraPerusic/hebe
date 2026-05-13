package com.hebe.cli

import com.hebe.config.HebeConfig
import com.hebe.security.policy.PolicyChain
import com.hebe.tools.dispatch.ToolDispatcher
import com.hebe.tools.dispatch.ToolRegistry
import java.nio.file.Path

class AppComponents(
    private val config: HebeConfig,
    private val workspaceRoot: Path,
) {
    private val validators = PolicyChain.standard(config, workspaceRoot)

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
}
