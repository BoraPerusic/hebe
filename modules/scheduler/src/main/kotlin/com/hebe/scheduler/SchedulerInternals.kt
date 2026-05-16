@file:Suppress("MatchingDeclarationName", "NewLineAtEndOfFile")

package com.hebe.scheduler

import com.hebe.api.ApprovalGate
import com.hebe.api.ApprovalStatus
import com.hebe.api.LlmProvider
import com.hebe.api.MemoryStore
import com.hebe.api.Observer
import com.hebe.api.Tool
import com.hebe.core.compaction.PreemptivePruner
import com.hebe.core.cost.CostGuard
import com.hebe.tools.dispatch.ToolDispatcher
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.JsonObject

data class Services(
    val memory: MemoryStore,
    val dispatcher: ToolDispatcher,
    val llmProvider: LlmProvider,
    val costGuard: CostGuard,
    val compactor: PreemptivePruner,
    val observer: Observer,
)

object DenyAllApprovalGate : ApprovalGate {
    override fun requestIfNeeded(
        tool: Tool,
        args: JsonObject,
        turnId: String,
        channel: String,
        threadExtId: String?,
    ) = flowOf(ApprovalStatus.Denied)

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
