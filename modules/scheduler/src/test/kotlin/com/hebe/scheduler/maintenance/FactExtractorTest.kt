@file:Suppress("MaxLineLength", "NewLineAtEndOfFile")

package com.hebe.scheduler.maintenance

import com.hebe.memory.db.DbFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.sql.Timestamp

private fun fakeLlmProvider() = object : com.hebe.api.LlmProvider {
    override suspend fun chat(req: com.hebe.api.ChatRequest) = kotlinx.coroutines.flow.flowOf(
        com.hebe.api.StreamEvent.TextDelta("""[{"fact":"The user prefers dark mode","confidence":0.95,"turn_id":"abc","date":"2025-06-15"}]"""),
        com.hebe.api.StreamEvent.Done,
    )
    override fun capabilities() = com.hebe.api.ProviderCapabilities(streaming = false, toolUse = false, multimodal = false, maxContextTokens = 0)
}

private fun emptyLlmProvider() = object : com.hebe.api.LlmProvider {
    override suspend fun chat(req: com.hebe.api.ChatRequest) = kotlinx.coroutines.flow.flowOf(
        com.hebe.api.StreamEvent.TextDelta("[]"),
        com.hebe.api.StreamEvent.Done,
    )
    override fun capabilities() = com.hebe.api.ProviderCapabilities(streaming = false, toolUse = false, multimodal = false, maxContextTokens = 0)
}

private fun insertAssistantMessage(db: com.hebe.memory.db.Db, id: String, content: String) {
    db.dataSource.connection.use { conn ->
        conn.prepareStatement("INSERT INTO conversations(id, channel, user_id, started_at) VALUES ('conv-fact-test', 'test', 'testuser', ?)").use { ps ->
            ps.setTimestamp(1, Timestamp(System.currentTimeMillis()))
            ps.executeUpdate()
        }
        conn.prepareStatement("INSERT INTO messages(id, conversation_id, role, content, ts) VALUES (?, 'conv-fact-test', 'assistant', ?, ?)").use { ps ->
            ps.setString(1, id)
            ps.setString(2, content)
            ps.setTimestamp(3, Timestamp(System.currentTimeMillis() - 1800_000))
            ps.executeUpdate()
        }
    }
}

class FactExtractorTest : StringSpec({
    "extractFacts returns empty when no assistant messages" {
        runTest {
            val db = DbFactory.openInMemory()
            val extractor = FactExtractor(db, fakeLlmProvider())
            val result = extractor.run()
            result.isSuccess shouldBe true
            result.getOrNull() shouldBe 0
            db.close()
        }
    }

    "fact extractor skips empty facts" {
        runTest {
            val db = DbFactory.openInMemory()
            val extractor = FactExtractor(db, emptyLlmProvider())
            val result = extractor.run()
            result.isSuccess shouldBe true
            db.close()
        }
    }

    "run with messages succeeds" {
        runTest {
            val db = DbFactory.openInMemory()
            insertAssistantMessage(db, "user-prefers-coffee", "I always drink coffee in the morning")
            val extractor = FactExtractor(db, fakeLlmProvider())
            val result = extractor.run()
            result.isSuccess shouldBe true
            db.close()
        }
    }

    "run with no messages succeeds with zero" {
        runTest {
            val db = DbFactory.openInMemory()
            val extractor = FactExtractor(db, fakeLlmProvider())
            val result = extractor.run()
            result.isSuccess shouldBe true
            result.getOrNull() shouldBe 0
            db.close()
        }
    }
})