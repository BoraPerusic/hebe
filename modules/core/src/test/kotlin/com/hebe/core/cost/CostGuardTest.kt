package com.hebe.core.cost

import com.hebe.api.Observer
import com.hebe.config.CostSection
import com.hebe.config.HebeConfig
import com.hebe.memory.db.DbFactory
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CostGuardTest {
    private val observer = mockk<Observer>(relaxed = true)
    private val config = mockk<HebeConfig>(relaxed = true).also {
        io.mockk.every { it.cost } returns CostSection(dailyUsdCap = 1.0, perTurnTokenCap = 1000)
    }

    private fun makeGuard(dailyUsdCap: Double = 1.0, perTurnTokenCap: Int = 1000): CostGuard {
        val db = DbFactory.openInMemory()
        val cfg = mockk<HebeConfig>(relaxed = true)
        io.mockk.every { cfg.cost } returns CostSection(
            dailyUsdCap = dailyUsdCap,
            perTurnTokenCap = perTurnTokenCap,
        )
        return CostGuard(db.dataSource, cfg, observer)
    }

    @Test
    fun `checkAllowed allows when no spend`() = runTest {
        val guard = makeGuard()
        val result = guard.checkAllowed("turn1")
        assertEquals(CostGuard.CheckResult.Allow, result)
    }

    @Test
    fun `checkAllowed denies per-turn when tokens exceed cap`() = runTest {
        val guard = makeGuard(perTurnTokenCap = 100)
        val result = guard.checkAllowed("turn1", usedTokens = 101)
        assertTrue(result is CostGuard.CheckResult.DenyPerTurn)
    }

    @Test
    fun `checkAllowed denies daily when spend exceeds cap`() = runTest {
        val guard = makeGuard(dailyUsdCap = 0.000001)
        guard.recordCall(
            turnId = "t1",
            model = "gpt-4",
            tokensIn = 100,
            tokensOut = 50,
            costMicrosUsd = 1000L,
            durationMs = 100L,
        )
        val result = guard.checkAllowed("turn2")
        assertTrue(result is CostGuard.CheckResult.DenyDaily)
    }

    @Test
    fun `recordCall persists and is queryable via daily spend`() = runTest {
        val guard = makeGuard(dailyUsdCap = 100.0)
        guard.recordCall(
            turnId = "t1",
            model = "gpt-4o",
            tokensIn = 200,
            tokensOut = 100,
            costMicrosUsd = 500_000L,
            durationMs = 250L,
        )
        val result = guard.checkAllowed("t2")
        assertEquals(CostGuard.CheckResult.Allow, result)
    }
}
