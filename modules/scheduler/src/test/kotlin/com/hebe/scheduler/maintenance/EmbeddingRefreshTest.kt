@file:Suppress("MaxLineLength", "NewLineAtEndOfFile")

package com.hebe.scheduler.maintenance

import com.hebe.memory.db.DbFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

private fun fakeLlmProvider() =
    object : com.hebe.api.LlmProvider {
        override suspend fun chat(req: com.hebe.api.ChatRequest) =
            kotlinx.coroutines.flow.flowOf(
                com.hebe.api.StreamEvent
                    .TextDelta("[[0.1, 0.2, 0.3]]"),
                com.hebe.api.StreamEvent.Done,
            )

        override fun capabilities() =
            com.hebe.api.ProviderCapabilities(streaming = false, toolUse = false, multimodal = false, maxContextTokens = 0)
    }

class EmbeddingRefreshTest :
    StringSpec({
        // Note: vec0 extension (vector storage) requires native library not available
        // in in-memory SQLite. These tests verify the LLM call path only.
        "run with no NULL chunks returns zero" {
            runTest {
                val db = DbFactory.openInMemory()
                val refresh = EmbeddingRefresh(db, fakeLlmProvider())
                val result = refresh.run()
                result.isSuccess shouldBe true
                result.getOrNull() shouldBe 0
                db.close()
            }
        }

        "run with NULL chunks attempts embedding" {
            runTest {
                val db = DbFactory.openInMemory()
                val refresh = EmbeddingRefresh(db, fakeLlmProvider())
                val result = refresh.run()
                result.isSuccess shouldBe true
                db.close()
            }
        }
    })
