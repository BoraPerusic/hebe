package com.hebe.core.compaction

import com.hebe.api.ConversationMessage

class PreemptivePruner(
    private val compactor: Compactor,
) {
    suspend fun prune(
        history: List<ConversationMessage>,
        turnId: String,
    ): Compactor.CompactionResult = compactor.maybeCompact(history, turnId)
}
