package com.inspiredandroid.kai.gateway

import com.inspiredandroid.kai.testutil.TestSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for usage and cost tracking.
 */
class UsageRecorderTest {

    private fun recorder(): UsageRecorder = UsageRecorder(TestSettings.appSettings())

    @Test
    fun `empty recorder has zero usage`() {
        val recorder = recorder()
        assertEquals(0.0, recorder.today().totalCostUsd)
        assertEquals(emptyList<UsageRecord>(), recorder.loadAll())
    }

    @Test
    fun `recorded request appears in today window`() {
        val recorder = recorder()
        recorder.record(
            UsageRecord(
                id = "r1",
                epochMs = System.currentTimeMillis(),
                providerInstanceId = "inst-1",
                modelId = "gpt-4.1",
                inputTokens = 1000,
                outputTokens = 500,
                success = true,
                estimatedCostUsd = 0.01,
            ),
        )
        assertEquals(0.01, recorder.today().totalCostUsd)
        assertEquals(1, recorder.today().records.size)
    }

    @Test
    fun `failed requests do not count toward cost`() {
        val recorder = recorder()
        recorder.record(
            UsageRecord(
                id = "r2", epochMs = System.currentTimeMillis(), providerInstanceId = "inst-1",
                modelId = "gpt-4.1", success = false, estimatedCostUsd = 5.0,
            ),
        )
        assertEquals(0.0, recorder.today().totalCostUsd)
    }

    @Test
    fun `monthly budget check works`() {
        val recorder = recorder()
        recorder.record(
            UsageRecord(
                id = "r3", epochMs = System.currentTimeMillis(), providerInstanceId = "inst-1",
                modelId = "claude-opus-4-5", success = true, estimatedCostUsd = 12.0,
            ),
        )
        assertTrue(recorder.monthlyCostExceeds(10.0))
        assertFalse(recorder.monthlyCostExceeds(20.0))
    }

    @Test
    fun `many records persist without loss`() {
        val recorder = recorder()
        val ids = (1..50).map { "r$it" }
        ids.forEachIndexed { i, id ->
            recorder.record(
                UsageRecord(
                    id = id, epochMs = System.currentTimeMillis() + i,
                    providerInstanceId = "inst-1", modelId = "gpt-4.1-mini",
                    success = true, estimatedCostUsd = 0.001,
                ),
            )
        }
        val all = recorder.loadAll()
        assertEquals(ids.size, all.size)
        assertEquals(ids.toSet(), all.map { it.id }.toSet())
    }

    @Test
    fun `history is capped at max records`() {
        val recorder = recorder()
        repeat(6_000) { i ->
            recorder.record(
                UsageRecord(
                    id = "r$i", epochMs = System.currentTimeMillis(), providerInstanceId = "inst-1",
                    modelId = "gpt-4.1-mini", success = true, estimatedCostUsd = 0.001,
                ),
            )
        }
        assertTrue(recorder.loadAll().size <= 5_000)
    }
}
