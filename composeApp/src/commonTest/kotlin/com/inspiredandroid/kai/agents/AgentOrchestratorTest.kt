package com.inspiredandroid.kai.agents

import com.inspiredandroid.kai.data.AppSettings
import com.inspiredandroid.kai.tools.ExecToolResult
import com.inspiredandroid.kai.tools.ToolResult
import com.inspiredandroid.kai.tools.ToolRuntime
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class AgentOrchestratorTest {
    @Test
    fun `workspace edit completes only after passing test and diff evidence`() = runTest {
        val llm = ScriptedLlm(
            action("fs.write", mapOf("sandboxId" to "s", "path" to "/workspace/a.kt"), "write"),
            action("terminal.exec", mapOf("sandboxId" to "s", "command" to "./gradlew test"), "test"),
            action("git.diff", mapOf("sandboxId" to "s"), "diff"),
            final("verified"),
        )
        val orchestrator = orchestrator(llm) { tool, _ ->
            when (tool) {
                "terminal.exec" -> ToolResult.Success(ExecToolResult(0, "tests passed", ""))
                else -> ToolResult.Success(message = "ok")
            }
        }
        val id = orchestrator.startRun(config(), "change the project")
        advanceUntilIdle()

        val run = orchestrator.runs.value.getValue(id)
        assertEquals(RunStatus.Completed, run.status)
        assertTrue(run.checkpoint.verification.testsProven)
        assertTrue(run.checkpoint.verification.diffObserved)
        assertEquals(AgentPhase.Completed, run.phase)
    }

    @Test
    fun `model claim without tool evidence fails delivery`() = runTest {
        val orchestrator = orchestrator(ScriptedLlm(final("done"))) { _, _ -> ToolResult.Success() }
        val id = orchestrator.startRun(config(), "do work")
        advanceUntilIdle()
        val run = orchestrator.runs.value.getValue(id)
        assertEquals(RunStatus.Failed, run.status)
        assertTrue(run.steps.any { it.title.contains("unverified") })
    }

    @Test
    fun `stream handle without exit code cannot prove completion`() = runTest {
        val llm = ScriptedLlm(
            action("terminal.exec_stream", mapOf("sandboxId" to "s", "command" to "long-task"), "start"),
            final("done"),
        )
        val orchestrator = orchestrator(llm) { _, _ -> ToolResult.Success(mapOf("handleId" to "h1")) }
        val id = orchestrator.startRun(config(), "run task")
        advanceUntilIdle()
        assertEquals(RunStatus.Failed, orchestrator.runs.value.getValue(id).status)
        assertEquals(0, orchestrator.runs.value.getValue(id).checkpoint.verification.successfulCommands)
    }

    @Test
    fun `provider deltas are forwarded to activity stream`() = runTest {
        val responses = mutableListOf(
            action("fs.read", mapOf("sandboxId" to "s", "path" to "/workspace"), "inspect"),
            final("done"),
        )
        val llm = object : AgentOrchestrator.LlmDelegate {
            override suspend fun complete(request: AgentOrchestrator.LlmCompletionRequest) = error("streaming expected")
            override suspend fun completeStreaming(
                request: AgentOrchestrator.LlmCompletionRequest,
                onDelta: suspend (String) -> Unit,
            ): AgentOrchestrator.LlmCompletionResponse {
                onDelta("live chunk")
                return responses.removeAt(0)
            }
        }
        val orchestrator = orchestrator(llm) { _, _ -> ToolResult.Success(message = "read") }
        val events = mutableListOf<OrchestratorActivityEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { orchestrator.activity.collect { events += it } }
        orchestrator.startRun(config(), "inspect")
        advanceUntilIdle()
        assertTrue(events.any { it.type == OrchestratorActivityEvent.Type.ModelDelta && it.detail == "live chunk" })
    }

    @Test
    fun `prompt arguments and tool output are redacted before persistence`() = runTest {
        val secret = "sk-abcdefghijklmnopqrstuvwxyz123456"
        val llm = ScriptedLlm(
            action("fs.read", mapOf("sandboxId" to "s", "path" to "/workspace"), "inspect", """{"authorization":"Bearer $secret"}"""),
            final("done"),
        )
        val orchestrator = orchestrator(llm) { _, _ -> ToolResult.Success(message = "token=$secret") }
        val id = orchestrator.startRun(config(), "use token=$secret")
        advanceUntilIdle()
        val run = orchestrator.runs.value.getValue(id)
        val persisted = run.prompt + run.steps.joinToString { "${it.detail} ${it.toolResultSummary}" } +
            run.checkpoint.verification.lastStdout + run.checkpoint.verification.lastStderr
        assertFalse(persisted.contains(secret))
        assertTrue(persisted.contains("REDACTED"))
    }

    @Test
    fun `nonzero terminal exit is failure then repaired test can prove result`() = runTest {
        val llm = ScriptedLlm(
            action("fs.write", mapOf("sandboxId" to "s", "path" to "/workspace/a.kt"), "write"),
            action("terminal.exec", mapOf("sandboxId" to "s", "command" to "./gradlew test"), "test1"),
            action("terminal.exec", mapOf("sandboxId" to "s", "command" to "./gradlew test"), "test2"),
            action("git.diff", mapOf("sandboxId" to "s"), "diff"),
            final("fixed"),
        )
        var tests = 0
        val orchestrator = orchestrator(llm) { tool, _ ->
            if (tool == "terminal.exec") {
                tests++
                if (tests == 1) ToolResult.Success(ExecToolResult(1, "", "compile error"))
                else ToolResult.Success(ExecToolResult(0, "tests passed", ""))
            } else ToolResult.Success()
        }
        val id = orchestrator.startRun(config(), "repair")
        advanceUntilIdle()
        val run = orchestrator.runs.value.getValue(id)
        assertEquals(RunStatus.Completed, run.status)
        assertEquals(1, run.checkpoint.verification.failedCommands)
        assertEquals(true, run.checkpoint.verification.lastTestPassed)
        assertTrue(run.steps.any { it.status == StepStatus.Failed && it.toolResultSummary?.contains("exitCode=1") == true })
    }

    @Test
    fun `retryable tool failure is retried within configured bound`() = runTest {
        val llm = ScriptedLlm(action("fs.read", mapOf("sandboxId" to "s", "path" to "/workspace/a"), "read"), final("done"))
        var calls = 0
        val orchestrator = orchestrator(llm) { _, _ ->
            calls++
            if (calls == 1) ToolResult.Failure("temporary", retryable = true) else ToolResult.Success(message = "contents")
        }
        val id = orchestrator.startRun(config(maxRetriesPerAction = 1), "inspect")
        advanceUntilIdle()
        assertEquals(2, calls)
        assertEquals(RunStatus.Completed, orchestrator.runs.value.getValue(id).status)
    }

    @Test
    fun `identical action loop is stopped`() = runTest {
        val repeated = action("fs.read", mapOf("sandboxId" to "s", "path" to "/workspace/a"), "same")
        val orchestrator = orchestrator(ScriptedLlm(repeated, repeated, repeated)) { _, _ -> ToolResult.Success() }
        val id = orchestrator.startRun(config(maxRepeatedAction = 2), "loop")
        advanceUntilIdle()
        val run = orchestrator.runs.value.getValue(id)
        assertEquals(RunStatus.Failed, run.status)
        assertTrue(run.steps.any { it.title.contains("Loop detected") })
    }

    @Test
    fun `cancellation cancels live coroutine and records terminal state`() = runTest {
        val llm = object : AgentOrchestrator.LlmDelegate {
            override suspend fun complete(request: AgentOrchestrator.LlmCompletionRequest): AgentOrchestrator.LlmCompletionResponse {
                awaitCancellation()
            }
        }
        val orchestrator = orchestrator(llm) { _, _ -> ToolResult.Success() }
        val id = orchestrator.startRun(config(), "wait")
        runCurrent()
        assertTrue(orchestrator.cancelRun(id))
        advanceUntilIdle()
        assertEquals(RunStatus.Cancelled, orchestrator.runs.value.getValue(id).status)
        assertFalse(orchestrator.cancelRun(id))
    }

    @Test
    fun `package install waits for explicit approval even in autonomous mode`() = runTest {
        val call = action(
            "terminal.exec",
            mapOf("sandboxId" to "s", "command" to "apt install jq"),
            "install",
            """{"command":"apt install jq"}""",
        )
        val orchestrator = orchestrator(ScriptedLlm(call, final("installed")), ApprovalMode.Autonomous) { _, _ ->
            ToolResult.Success(ExecToolResult(0, "installed", ""))
        }
        val id = orchestrator.startRun(config(), "install jq")
        runCurrent()
        val pending = orchestrator.pendingApprovals.value.singleOrNull()
        assertNotNull(pending)
        assertEquals(ToolRisk.PackageInstall, pending.toolRisk)
        orchestrator.approve(pending.id)
        advanceUntilIdle()
        assertEquals(RunStatus.Completed, orchestrator.runs.value.getValue(id).status)
    }

    @Test
    fun `cost budget stops before executing proposed tool`() = runTest {
        val response = action("fs.read", mapOf("sandboxId" to "s", "path" to "/workspace"), "read")
            .copy(usage = AgentOrchestrator.LlmUsage(estimatedCostUsd = 2.0))
        var called = false
        val orchestrator = orchestrator(ScriptedLlm(response)) { _, _ -> called = true; ToolResult.Success() }
        val id = orchestrator.startRun(config(maxEstimatedCostUsd = 1.0), "expensive")
        advanceUntilIdle()
        assertFalse(called)
        assertEquals(RunStatus.Failed, orchestrator.runs.value.getValue(id).status)
    }

    @Test
    fun `time budget cancels provider work but records failed budget outcome`() = runTest {
        val llm = object : AgentOrchestrator.LlmDelegate {
            override suspend fun complete(request: AgentOrchestrator.LlmCompletionRequest): AgentOrchestrator.LlmCompletionResponse {
                delay(10_000)
                return final("late")
            }
        }
        val runtime = ToolRuntime(scope = this)
        val orchestrator = AgentOrchestrator(
            toolRuntime = runtime,
            approvalEngine = ApprovalEngine { runtime.availableToolIds().toSet() },
            runStore = AgentRunStore(AppSettings(MapSettings())),
            approvalMode = { ApprovalMode.Balanced },
            llm = llm,
            scope = this,
            recoveryPolicy = RecoveryPolicy(retryDelay = 0.milliseconds),
            callTool = { _, _ -> ToolResult.Success() },
        )
        val id = orchestrator.startRun(config().copy(maxDurationMs = 100), "time limit")
        advanceUntilIdle()
        val run = orchestrator.runs.value.getValue(id)
        assertEquals(RunStatus.Failed, run.status)
        assertTrue(run.steps.any { it.title.contains("Time budget exceeded") })
    }

    @Test
    fun `persisted active run becomes paused and resumes from checkpoint`() = runTest {
        val settings = AppSettings(MapSettings())
        val store = AgentRunStore(settings)
        val saved = AgentRun(
            id = "persisted",
            agentId = "coding",
            agentName = "Coding",
            prompt = "inspect",
            status = RunStatus.Running,
            phase = AgentPhase.Observing,
            checkpoint = AgentCheckpoint(phase = AgentPhase.Observing, completedSteps = 1),
        )
        store.saveRuns(listOf(saved))
        store.savePending(listOf(PendingApproval("old", saved.id, "coding", "step", "fs.read", "fs.read", ToolRisk.SafeRead, "{}", "old")))
        val runtime = ToolRuntime(scope = this)
        val orchestrator = AgentOrchestrator(
            toolRuntime = runtime,
            approvalEngine = ApprovalEngine { runtime.availableToolIds().toSet() },
            runStore = store,
            approvalMode = { ApprovalMode.Balanced },
            llm = ScriptedLlm(
                action("fs.read", mapOf("sandboxId" to "s", "path" to "/workspace"), "re-observe"),
                final("observed"),
            ),
            scope = this,
            recoveryPolicy = RecoveryPolicy(retryDelay = 0.milliseconds),
            callTool = { _, _ -> ToolResult.Success(message = "observed") },
        )
        assertEquals(RunStatus.Paused, orchestrator.runs.value.getValue(saved.id).status)
        assertTrue(orchestrator.resumeRun(saved.id, config()))
        advanceUntilIdle()
        assertEquals(RunStatus.Completed, orchestrator.runs.value.getValue(saved.id).status)
        assertTrue(orchestrator.pendingApprovals.value.isEmpty())
        assertTrue(orchestrator.runs.value.getValue(saved.id).checkpoint.completedSteps >= 3)
    }

    private fun TestScope.orchestrator(
        llm: AgentOrchestrator.LlmDelegate,
        mode: ApprovalMode = ApprovalMode.Balanced,
        call: suspend (String, Map<String, Any?>) -> ToolResult,
    ): AgentOrchestrator {
        val runtime = ToolRuntime(scope = this)
        return AgentOrchestrator(
            toolRuntime = runtime,
            approvalEngine = ApprovalEngine { runtime.availableToolIds().toSet() },
            runStore = AgentRunStore(AppSettings(MapSettings())),
            approvalMode = { mode },
            llm = llm,
            scope = this,
            recoveryPolicy = RecoveryPolicy(retryDelay = 0.milliseconds),
            callTool = call,
        )
    }

    private fun config(
        maxRetriesPerAction: Int = 1,
        maxRepeatedAction: Int = 3,
        maxEstimatedCostUsd: Double = 5.0,
    ) = AgentConfig(
        id = "coding",
        name = "Coding",
        maxSteps = 30,
        maxDurationMs = 60_000,
        maxRetriesPerAction = maxRetriesPerAction,
        maxRepeatedAction = maxRepeatedAction,
        maxEstimatedCostUsd = maxEstimatedCostUsd,
    )

    private class ScriptedLlm(vararg responses: AgentOrchestrator.LlmCompletionResponse) : AgentOrchestrator.LlmDelegate {
        private val remaining = responses.toMutableList()
        override suspend fun complete(request: AgentOrchestrator.LlmCompletionRequest): AgentOrchestrator.LlmCompletionResponse =
            remaining.removeAt(0)
    }

    private fun action(
        tool: String,
        args: Map<String, Any?>,
        content: String,
        json: String = args.toString(),
    ) = AgentOrchestrator.LlmCompletionResponse(
        isFinalAnswer = false,
        content = content,
        toolCalls = listOf(AgentOrchestrator.LlmToolCall(tool, args, json)),
    )

    private fun final(content: String) = AgentOrchestrator.LlmCompletionResponse(true, content)
}
