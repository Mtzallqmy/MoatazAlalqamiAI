package com.inspiredandroid.kai.agents

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentRunExecutorTest {
    @Test
    fun `normal completion emits running completed and finished`() = runTest {
        val statuses = mutableListOf<RunStatus>()
        var finished = 0
        val run = sampleRun()
        AgentRunExecutor(this, { _, status, _ -> statuses += status }, { _, _ -> }, { finished++ })
            .run(run) { RunStatus.Completed }
        advanceUntilIdle()
        assertEquals(listOf(RunStatus.Running, RunStatus.Completed), statuses)
        assertEquals(1, finished)
    }

    @Test
    fun `cancellation emits cancelled and finishes exactly once`() = runTest {
        val statuses = mutableListOf<RunStatus>()
        var finished = 0
        val run = sampleRun()
        val job = AgentRunExecutor(this, { _, status, _ -> statuses += status }, { _, _ -> }, { finished++ })
            .run(run) { awaitCancellation() }
        runCurrent()
        job.cancel()
        advanceUntilIdle()
        assertEquals(listOf(RunStatus.Running, RunStatus.Cancelled), statuses)
        assertEquals(1, finished)
    }

    @Test
    fun `uncaught failure emits failed`() = runTest {
        val statuses = mutableListOf<RunStatus>()
        val run = sampleRun()
        AgentRunExecutor(this, { _, status, _ -> statuses += status }, { _, _ -> }, {})
            .run(run) { error("boom") }
        advanceUntilIdle()
        assertEquals(listOf(RunStatus.Running, RunStatus.Failed), statuses)
    }

    private fun sampleRun() = AgentRun("run", "agent", "Agent", prompt = "test")
}
