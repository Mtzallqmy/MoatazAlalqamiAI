package com.inspiredandroid.kai.agents

import com.inspiredandroid.kai.brand.AssistantIdentity
import com.inspiredandroid.kai.runtime.RuntimeDiagnosticRedactor
import com.inspiredandroid.kai.tools.ExecToolResult
import com.inspiredandroid.kai.tools.ToolResult
import com.inspiredandroid.kai.tools.ToolRiskLevel
import com.inspiredandroid.kai.tools.ToolRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Provider-independent, evidence-driven execution loop for agent runs. */
class AgentOrchestrator(
    private val toolRuntime: ToolRuntime,
    private val approvalEngine: ApprovalEngine,
    private val runStore: AgentRunStore,
    private val approvalMode: () -> ApprovalMode,
    private val llm: LlmDelegate,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    val recoveryPolicy: RecoveryPolicy = RecoveryPolicy.DEFAULT,
    private val callTool: suspend (String, Map<String, Any?>) -> ToolResult = toolRuntime::call,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val _activity = MutableSharedFlow<OrchestratorActivityEvent>(extraBufferCapacity = 256)
    val activity: SharedFlow<OrchestratorActivityEvent> = _activity.asSharedFlow()

    private val restoredRuns = runStore.loadRuns().associateBy { it.id }.mapValues { (_, run) ->
        if (run.status in ACTIVE_STATUSES) run.copy(status = RunStatus.Paused) else run
    }
    private val _runs = MutableStateFlow(restoredRuns)
    val runs: StateFlow<Map<String, AgentRun>> = _runs.asStateFlow()
    private val _pending = MutableStateFlow<List<PendingApproval>>(runStore.loadPending())
    val pendingApprovals: StateFlow<List<PendingApproval>> = _pending.asStateFlow()

    private val approvalWaiters = mutableMapOf<String, CompletableDeferred<ApprovalDecision>>()
    private val jobs = mutableMapOf<String, Job>()
    private val executor = AgentRunExecutor(
        scope = scope,
        onStatusTransition = { run, status, finishedAt ->
            updateRun(run.id) { current ->
                current.copy(
                    status = status,
                    phase = status.toTerminalPhase(current.phase),
                    finishedAt = if (status in TERMINAL_STATUSES) finishedAt else current.finishedAt,
                )
            }
        },
        onStepUpdate = { _, _ -> },
        onRunFinished = { run -> jobs.remove(run.id) },
        onFailure = { run, failure ->
            appendFailureStep(run.id, StepKind.Error, "Agent run crashed", failure.message ?: failure::class.simpleName.orEmpty())
        },
    )

    fun startRun(agentConfig: AgentConfig, prompt: String, projectContext: String = ""): String {
        require(prompt.isNotBlank()) { "Agent prompt must not be blank" }
        val run = AgentRun(
            id = newId(),
            agentId = agentConfig.id,
            agentName = agentConfig.name,
            projectId = agentConfig.projectId,
            prompt = safe(prompt),
        )
        putRun(run)
        launch(run, agentConfig, projectContext, resume = false)
        return run.id
    }

    fun resumeRun(runId: String, agentConfig: AgentConfig, projectContext: String = ""): Boolean {
        val run = _runs.value[runId] ?: return false
        if (run.status !in setOf(RunStatus.Paused, RunStatus.Failed)) return false
        _pending.value.filter { it.runId == runId }.forEach { removePending(it.id) }
        updateRun(runId) { it.copy(status = RunStatus.Queued, finishedAt = null) }
        launch(_runs.value.getValue(runId), agentConfig, projectContext, resume = true)
        emitActivity(runId, OrchestratorActivityEvent.Type.Resumed, "Resumed from ${run.checkpoint.phase}")
        return true
    }

    fun cancelRun(runId: String): Boolean {
        val job = jobs[runId] ?: return false
        _pending.value.filter { it.runId == runId }.forEach { approvalWaiters[it.id]?.cancel() }
        job.cancel(CancellationException("Cancelled by user"))
        return true
    }

    fun approve(id: String) = resolveApproval(id, ApprovalDecision.AutoApproved)
    fun reject(id: String) = resolveApproval(id, ApprovalDecision.Blocked("Rejected by user"))

    private fun launch(run: AgentRun, config: AgentConfig, projectContext: String, resume: Boolean) {
        val job = executor.run(run) {
            try {
                withTimeout(config.maxDurationMs.coerceAtLeast(1L)) {
                    runLoop(run.id, config, projectContext, resume)
                }
            } catch (_: TimeoutCancellationException) {
                val reason = "Time budget exceeded: ${config.maxDurationMs} ms"
                appendFailureStep(run.id, StepKind.Error, reason, "")
                emitActivity(run.id, OrchestratorActivityEvent.Type.BudgetExhausted, reason)
                RunStatus.Failed
            }
        }
        jobs[run.id] = job
        job.invokeOnCompletion { if (jobs[run.id] === job) jobs.remove(run.id) }
    }

    private fun resolveApproval(id: String, decision: ApprovalDecision) {
        val pending = _pending.value.find { it.id == id } ?: return
        ApprovalAuditLog.record(
            toolId = pending.toolName,
            toolRisk = pending.toolRisk.name,
            argsSummary = safe(pending.argsSummary),
            verdict = if (decision is ApprovalDecision.Blocked) ApprovalAuditLog.Verdict.Rejected else ApprovalAuditLog.Verdict.Approved,
        )
        removePending(id)
        approvalWaiters.remove(id)?.complete(decision)
    }

    private suspend fun runLoop(runId: String, config: AgentConfig, projectContext: String, resume: Boolean): RunStatus {
        val restored = currentRun(runId)
        emitActivity(runId, OrchestratorActivityEvent.Type.Started, restored.prompt)
        if (!resume) transition(runId, AgentPhase.Request)
        val history = mutableListOf(
            LlmMessage("system", buildSystemPrompt(config)),
            LlmMessage("user", restored.prompt + contextSuffix(projectContext)),
        )
        if (resume) history += LlmMessage("system", checkpointSummary(restored))

        var checkpoint = restored.checkpoint
        var verification = checkpoint.verification
        var consecutiveFailures = checkpoint.consecutiveFailures
        val fingerprints = checkpoint.actionFingerprints.toMutableMap()

        while (checkpoint.completedSteps < config.maxSteps.coerceIn(1, 1000)) {
            if (currentRun(runId).estimatedCostUsd > config.maxEstimatedCostUsd) {
                val reason = "Cost budget exceeded: ${currentRun(runId).estimatedCostUsd} > ${config.maxEstimatedCostUsd} USD"
                appendFailureStep(runId, StepKind.Error, reason, "")
                emitActivity(runId, OrchestratorActivityEvent.Type.BudgetExhausted, reason)
                return RunStatus.Failed
            }
            val planningPhase = if (consecutiveFailures > 0) AgentPhase.Repairing else AgentPhase.Planning
            transition(runId, planningPhase)
            checkpoint = checkpoint.copy(phase = planningPhase, updatedAt = now())
            val response = completeWithRetry(runId, config, history) ?: return RunStatus.Failed
            applyUsage(runId, response.usage)
            if (currentRun(runId).estimatedCostUsd > config.maxEstimatedCostUsd) {
                val reason = "Cost budget exceeded after provider response: ${currentRun(runId).estimatedCostUsd} USD"
                appendFailureStep(runId, StepKind.Error, reason, "")
                emitActivity(runId, OrchestratorActivityEvent.Type.BudgetExhausted, reason)
                return RunStatus.Failed
            }
            checkpoint = checkpoint.copy(completedSteps = checkpoint.completedSteps + 1, updatedAt = now())
            saveCheckpoint(runId, checkpoint.copy(verification = verification))

            if (response.content.isNotBlank()) {
                appendStep(runId, StepKind.Plan, if (consecutiveFailures > 0) "Repair plan" else "Plan", safe(response.content))
            }
            if (response.isFinalAnswer) {
                transition(runId, AgentPhase.Delivering)
                if (!verification.deliveryProven) {
                    val reason = verificationFailure(verification)
                    appendFailureStep(runId, StepKind.Summary, "Delivery rejected: unverified result", reason)
                    emitActivity(runId, OrchestratorActivityEvent.Type.VerificationFailed, reason)
                    return RunStatus.Failed
                }
                appendStep(runId, StepKind.Summary, "Verified delivery", safe(response.content))
                transition(runId, AgentPhase.Completed)
                emitActivity(runId, OrchestratorActivityEvent.Type.Finished, safe(response.content))
                return RunStatus.Completed
            }

            val calls = response.toolCalls.filter { isAllowed(config, it.toolId) }
            if (response.toolCalls.isNotEmpty() && calls.isEmpty()) {
                appendFailureStep(runId, StepKind.ApprovalRequest, "All tool calls blocked by policy", "")
                emitActivity(runId, OrchestratorActivityEvent.Type.Blocked, "No proposed tool is allowed")
                return RunStatus.Failed
            }
            if (calls.isEmpty()) {
                consecutiveFailures++
                history += LlmMessage("tool", "No executable action was proposed; provide a concrete tool call.")
                if (consecutiveFailures >= recoveryPolicy.maxConsecutiveFailures) return RunStatus.Failed
                continue
            }

            for (call in calls) {
                val fingerprint = "${call.toolId}:${call.argsJson ?: call.args.toString()}"
                val repeats = (fingerprints[fingerprint] ?: 0) + 1
                fingerprints[fingerprint] = repeats
                if (repeats > config.maxRepeatedAction.coerceAtLeast(1)) {
                    val reason = "Loop detected: ${call.toolId} repeated $repeats times with identical arguments"
                    appendFailureStep(runId, StepKind.Error, reason, "")
                    emitActivity(runId, OrchestratorActivityEvent.Type.LoopDetected, reason)
                    return RunStatus.Failed
                }

                val risk = effectiveRisk(call)
                val decision = awaitDecision(runId, config, call, risk, approvalEngine.decide(call.toolId, risk, approvalMode(), call.argsJson))
                if (decision is ApprovalDecision.Blocked) {
                    consecutiveFailures++
                    appendFailureStep(runId, StepKind.ApprovalRequest, "Rejected: ${decision.reason}", "")
                    history += LlmMessage("tool", "${call.toolId} was not executed: ${decision.reason}")
                    continue
                }

                transition(runId, phaseFor(call))
                val execution = executeWithRetry(runId, config, call)
                transition(runId, AgentPhase.Observing)
                verification = verification.observe(call, execution)
                val observation = execution.observation()
                history += LlmMessage("assistant", safe(response.toHistoryText()))
                history += LlmMessage("tool", observation)
                emitActivity(runId, OrchestratorActivityEvent.Type.Observation, observation.take(1000))
                consecutiveFailures = if (execution.succeeded) 0 else consecutiveFailures + 1
                checkpoint = checkpoint.copy(
                    completedSteps = checkpoint.completedSteps + 1,
                    consecutiveFailures = consecutiveFailures,
                    actionFingerprints = fingerprints.toMap(),
                    verification = verification,
                    phase = currentRun(runId).phase,
                    updatedAt = now(),
                )
                saveCheckpoint(runId, checkpoint)
                if (checkpoint.completedSteps >= config.maxSteps) break
                if (consecutiveFailures >= recoveryPolicy.maxConsecutiveFailures) {
                    appendFailureStep(runId, StepKind.Error, "Recovery stopped after $consecutiveFailures failures", observation)
                    return RunStatus.Failed
                }
            }
        }
        appendFailureStep(runId, StepKind.Summary, "Step budget exhausted", "Stopped after ${config.maxSteps} steps")
        emitActivity(runId, OrchestratorActivityEvent.Type.BudgetExhausted, "${config.maxSteps} steps")
        return RunStatus.Failed
    }

    private suspend fun completeWithRetry(runId: String, config: AgentConfig, history: List<LlmMessage>): LlmCompletionResponse? {
        val request = LlmCompletionRequest(history, toolRuntime.availableToolIds())
        val attempts = config.maxRetriesPerAction.coerceIn(0, 5) + 1
        repeat(attempts) { attempt ->
            try {
                return llm.completeStreaming(request) { delta ->
                    emitActivity(runId, OrchestratorActivityEvent.Type.ModelDelta, safe(delta))
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                val retry = recoveryPolicy.isRetriableLlmError(e) && attempt + 1 < attempts
                emitActivity(runId, OrchestratorActivityEvent.Type.LlmError, e.message ?: "LLM call failed")
                if (!retry) {
                    appendFailureStep(runId, StepKind.Error, "LLM unavailable", e.message ?: "unknown error")
                    return null
                }
                emitActivity(runId, OrchestratorActivityEvent.Type.Retrying, "LLM retry ${attempt + 1}/$attempts")
                delay(recoveryPolicy.delayFor(attempt))
            }
        }
        return null
    }

    private suspend fun awaitDecision(
        runId: String,
        config: AgentConfig,
        call: LlmToolCall,
        risk: ToolRisk,
        decision: ApprovalDecision,
    ): ApprovalDecision = when (decision) {
        ApprovalDecision.AutoApproved -> {
            ApprovalAuditLog.record(call.toolId, risk.name, safe(call.argsJson.orEmpty()), ApprovalAuditLog.Verdict.AutoApproved)
            decision
        }
        is ApprovalDecision.Blocked -> decision
        is ApprovalDecision.NeedsApproval -> {
            val approvalId = newId()
            val stepId = appendStep(runId, StepKind.ApprovalRequest, "Waiting approval: ${call.toolId}", safe(call.argsJson.orEmpty()), StepStatus.WaitingApproval)
            val pending = PendingApproval(
                id = approvalId, runId = runId, agentId = currentRun(runId).agentId, stepId = stepId,
                toolId = call.toolId, toolName = call.toolId, toolRisk = risk,
                argsSummary = safe(call.argsJson.orEmpty()), explanation = safe(decision.reason),
            )
            val waiter = CompletableDeferred<ApprovalDecision>()
            approvalWaiters[approvalId] = waiter
            _pending.update { (it + pending).takeLast(50) }
            persistPending()
            updateRun(runId) { it.copy(status = RunStatus.WaitingApproval) }
            transition(runId, AgentPhase.AwaitingApproval)
            emitActivity(runId, OrchestratorActivityEvent.Type.WaitingApproval, "${call.toolId}: ${decision.reason}")
            try {
                withTimeoutOrNull(config.approvalTimeoutMs.coerceAtLeast(1L)) { waiter.await() }
                    ?: ApprovalDecision.Blocked("Approval timed out")
            } finally {
                approvalWaiters.remove(approvalId)
                removePending(approvalId)
                updateRun(runId) { it.copy(status = RunStatus.Running) }
            }
        }
    }

    private suspend fun executeWithRetry(runId: String, config: AgentConfig, call: LlmToolCall): ToolExecution {
        val stepId = appendStep(runId, StepKind.ToolCall, call.toolId, safe(call.argsJson.orEmpty()), StepStatus.Running)
        emitActivity(runId, OrchestratorActivityEvent.Type.ToolCall, "${call.toolId}: ${safe(call.argsJson.orEmpty())}")
        val attempts = config.maxRetriesPerAction.coerceIn(0, 5) + 1
        repeat(attempts) { attempt ->
            val execution = try {
                callTool(call.toolId, call.args).toExecution(call.toolId)
            } catch (ce: CancellationException) {
                updateStep(runId, stepId, StepStatus.Cancelled, "Cancelled by user")
                throw ce
            } catch (e: Exception) {
                ToolExecution(false, null, "", e.message ?: "tool error", retryable = false)
            }
            if (execution.succeeded) {
                updateStep(runId, stepId, StepStatus.Done, execution.observation())
                emitActivity(runId, OrchestratorActivityEvent.Type.ToolSuccess, call.toolId)
                return execution
            }
            if (!execution.retryable || attempt + 1 >= attempts) {
                updateStep(runId, stepId, StepStatus.Failed, execution.observation())
                emitActivity(runId, OrchestratorActivityEvent.Type.ToolFailure, "${call.toolId}: ${execution.stderr}")
                return execution
            }
            emitActivity(runId, OrchestratorActivityEvent.Type.Retrying, "${call.toolId} retry ${attempt + 1}/$attempts")
            delay(recoveryPolicy.delayFor(attempt))
        }
        error("unreachable")
    }

    private fun ToolResult.toExecution(toolId: String): ToolExecution = when (this) {
        is ToolResult.Failure -> ToolExecution(false, null, "", safe(error), retryable)
        is ToolResult.Success -> when (val payload = data) {
            is ExecToolResult -> ToolExecution(payload.exitCode == 0, payload.exitCode, safe(payload.stdout), safe(payload.stderr), false)
            else -> ToolExecution(true, null, safe(message), "", false, conclusive = toolId != "terminal.exec_stream")
        }
    }

    private fun RunVerification.observe(call: LlmToolCall, execution: ToolExecution): RunVerification {
        val test = isTestCall(call)
        return copy(
            successfulCommands = successfulCommands + if (execution.succeeded && execution.conclusive) 1 else 0,
            failedCommands = failedCommands + if (!execution.succeeded) 1 else 0,
            workspaceMutated = workspaceMutated || isMutation(call),
            testsAttempted = testsAttempted + if (test) 1 else 0,
            testsPassed = testsPassed + if (test && execution.succeeded && execution.exitCode == 0) 1 else 0,
            lastTestPassed = if (test) execution.succeeded && execution.exitCode == 0 else lastTestPassed,
            diffObserved = when {
                isMutation(call) && execution.succeeded -> false
                call.toolId == "git.diff" && execution.succeeded -> true
                else -> diffObserved
            },
            lastExitCode = execution.exitCode ?: lastExitCode,
            lastStdout = execution.stdout.takeLast(4000),
            lastStderr = execution.stderr.takeLast(4000),
        )
    }

    private fun effectiveRisk(call: LlmToolCall): ToolRisk {
        val static = mapRisk(toolRuntime.riskLevelFor(call.toolId))
        return ApprovalEngine.classifyCommandRisk(call.toolId, call.argsJson ?: call.args.toString(), static)
    }

    private fun phaseFor(call: LlmToolCall): AgentPhase = when {
        isTestCall(call) -> AgentPhase.Testing
        call.toolId == "git.diff" -> AgentPhase.Diffing
        else -> AgentPhase.Executing
    }

    private fun isTestCall(call: LlmToolCall): Boolean {
        if (call.toolId !in setOf("terminal.exec", "terminal.exec_stream")) return false
        val command = ((call.args["command"] as? String).orEmpty() + " " + call.argsJson.orEmpty()).lowercase()
        return TEST_PATTERNS.any(command::contains) ||
            (command.contains("gradlew") && (command.contains("test") || command.contains("check"))) ||
            (command.contains("npm") && command.contains("test")) ||
            (command.contains("python") && command.contains("unittest"))
    }

    private fun applyUsage(runId: String, usage: LlmUsage) = updateRun(runId) {
        it.copy(
            totalInputTokens = it.totalInputTokens + usage.inputTokens,
            totalOutputTokens = it.totalOutputTokens + usage.outputTokens,
            estimatedCostUsd = it.estimatedCostUsd + usage.estimatedCostUsd,
        )
    }

    private fun transition(runId: String, phase: AgentPhase) {
        updateRun(runId) { it.copy(phase = phase, checkpoint = it.checkpoint.copy(phase = phase, updatedAt = now())) }
        emitActivity(runId, OrchestratorActivityEvent.Type.PhaseChanged, phase.name, phase)
    }

    private fun appendStep(runId: String, kind: StepKind, title: String, detail: String, status: StepStatus = StepStatus.Done): String {
        val timestamp = now()
        val step = AgentStep(
            id = newId(), runId = runId, kind = kind, title = title, detail = safe(detail), status = status,
            startedAt = timestamp,
            finishedAt = if (status in setOf(StepStatus.Running, StepStatus.WaitingApproval)) null else timestamp,
        )
        updateRun(runId) { it.copy(steps = it.steps + step) }
        return step.id
    }

    private fun appendFailureStep(runId: String, kind: StepKind, title: String, detail: String) =
        appendStep(runId, kind, title, detail, StepStatus.Failed)

    private fun updateStep(runId: String, stepId: String, status: StepStatus, summary: String) = updateRun(runId) { run ->
        val timestamp = now()
        run.copy(steps = run.steps.map { step ->
            if (step.id != stepId) step else step.copy(
                status = status, toolResultSummary = safe(summary),
                finishedAt = timestamp, durationMs = timestamp - step.startedAt,
            )
        })
    }

    private fun saveCheckpoint(runId: String, checkpoint: AgentCheckpoint) =
        updateRun(runId) { it.copy(checkpoint = checkpoint, phase = checkpoint.phase) }

    private fun putRun(run: AgentRun) {
        _runs.update { it + (run.id to run) }
        persistRuns()
    }

    private fun updateRun(runId: String, transform: (AgentRun) -> AgentRun) {
        _runs.update { all -> all[runId]?.let { all + (runId to transform(it)) } ?: all }
        persistRuns()
    }

    private fun persistRuns() = runStore.saveRuns(_runs.value.values.sortedBy { it.startedAt })
    private fun persistPending() = runStore.savePending(_pending.value)
    private fun removePending(id: String) {
        _pending.update { it.filterNot { item -> item.id == id } }
        persistPending()
    }

    private fun emitActivity(
        runId: String,
        type: OrchestratorActivityEvent.Type,
        detail: String,
        phase: AgentPhase = _runs.value[runId]?.phase ?: AgentPhase.Request,
    ) {
        _activity.tryEmit(OrchestratorActivityEvent(runId, type, safe(detail), phase, now()))
    }

    private fun currentRun(runId: String): AgentRun = requireNotNull(_runs.value[runId]) { "Unknown run $runId" }
    private fun isMutation(call: LlmToolCall): Boolean {
        if (call.toolId in MUTATING_TOOLS) return true
        if (call.toolId !in setOf("terminal.exec", "terminal.exec_stream")) return false
        if (isTestCall(call)) return false
        val command = ((call.args["command"] as? String).orEmpty() + " " + call.argsJson.orEmpty()).lowercase()
        return MUTATING_COMMAND_PATTERNS.any(command::contains)
    }
    private fun isAllowed(config: AgentConfig, toolId: String): Boolean =
        (config.allowedToolIds.isEmpty() || toolId in config.allowedToolIds) &&
            toolId !in config.blockedToolIds && toolId in toolRuntime.availableToolIds()

    private fun mapRisk(level: ToolRiskLevel): ToolRisk = when (level) {
        ToolRiskLevel.READ_ONLY -> ToolRisk.SafeRead
        ToolRiskLevel.WORKSPACE_WRITE -> ToolRisk.WorkspaceWrite
        ToolRiskLevel.PACKAGE_INSTALL -> ToolRisk.PackageInstall
        ToolRiskLevel.NETWORK, ToolRiskLevel.PROCESS_CONTROL -> ToolRisk.Network
        ToolRiskLevel.GIT_WRITE -> ToolRisk.WorkspaceWrite
        ToolRiskLevel.SECRET_ACCESS, ToolRiskLevel.DESTRUCTIVE -> ToolRisk.Destructive
    }

    private fun buildSystemPrompt(config: AgentConfig): String = buildString {
        appendLine(config.systemPrompt.ifBlank { SYSTEM_PROMPT })
        appendLine("Available tools: ${toolRuntime.availableToolIds().joinToString(", ")}")
        appendLine("Final delivery requires observed tool results, passing tests after writes, and git.diff.")
    }

    private fun contextSuffix(context: String) = if (context.isBlank()) "" else "\nProject context:\n$context"
    private fun checkpointSummary(run: AgentRun) = buildString {
        appendLine("Resume checkpoint: ${run.checkpoint.phase}; completed steps=${run.checkpoint.completedSteps}.")
        run.steps.takeLast(20).forEach { appendLine("- ${it.status}: ${it.title}: ${it.toolResultSummary ?: it.detail}") }
        append("Re-observe the workspace; never assume an interrupted command completed.")
    }

    private fun verificationFailure(v: RunVerification): String = when {
        v.successfulCommands == 0 -> "No successful tool result was observed."
        v.workspaceMutated && !v.testsProven -> "Workspace changed but no passing test with exitCode=0 was observed."
        v.workspaceMutated && !v.diffObserved -> "Workspace changed but git.diff was not observed successfully."
        else -> "Execution evidence is incomplete."
    }

    private fun newId(): String = (now().toString(36) + kotlin.random.Random.nextLong().toString(36)).take(12)
    private fun safe(value: String): String = RuntimeDiagnosticRedactor.redact(value)

    interface LlmDelegate {
        suspend fun complete(request: LlmCompletionRequest): LlmCompletionResponse
        suspend fun completeStreaming(request: LlmCompletionRequest, onDelta: suspend (String) -> Unit): LlmCompletionResponse = complete(request)
    }

    data class LlmCompletionRequest(val messages: List<LlmMessage>, val tools: List<String>)
    data class LlmMessage(val role: String, val content: String)
    data class LlmUsage(val inputTokens: Long = 0, val outputTokens: Long = 0, val estimatedCostUsd: Double = 0.0)
    data class LlmCompletionResponse(
        val isFinalAnswer: Boolean,
        val content: String,
        val toolCalls: List<LlmToolCall> = emptyList(),
        val usage: LlmUsage = LlmUsage(),
    ) {
        fun toHistoryText(): String = if (isFinalAnswer) content else toolCalls.joinToString("\n") { "${it.toolId}: ${it.argsJson.orEmpty()}" }
    }

    data class LlmToolCall(val toolId: String, val args: Map<String, Any?>, val argsJson: String? = null)

    companion object {
        val SYSTEM_PROMPT: String = """
            You are ${AssistantIdentity.Default.systemIdentity}, a supervised developer agent in Moataz Runtime.
            The canonical project root is /workspace. Plan before acting and inspect stdout, stderr and exit codes.
            After edits, run relevant tests and inspect git diff. On failure, diagnose observed output, repair, and retest.
            Never claim success based on prose or an unobserved command.
        """.trimIndent()
        private val ACTIVE_STATUSES = setOf(RunStatus.Queued, RunStatus.Running, RunStatus.WaitingApproval)
        private val TERMINAL_STATUSES = setOf(RunStatus.Completed, RunStatus.Failed, RunStatus.Cancelled)
        private val MUTATING_TOOLS = setOf("fs.write", "fs.patch", "fs.move", "fs.delete", "git.checkout", "git.commit")
        private val MUTATING_COMMAND_PATTERNS = listOf(" >", "> ", "sed -i", "rm ", "mv ", "cp ", "touch ", "mkdir ", "git checkout", "git commit")
        private val TEST_PATTERNS = listOf("gradlew test", "gradle test", "npm test", "pnpm test", "yarn test", "pytest", "python -m unittest", "cargo test", "mvn test")
    }
}

private data class ToolExecution(
    val succeeded: Boolean,
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val retryable: Boolean,
    val conclusive: Boolean = true,
) {
    fun observation(): String = buildString {
        append("success=$succeeded")
        if (!conclusive) append(" completed=false")
        exitCode?.let { append(" exitCode=$it") }
        if (stdout.isNotBlank()) append("\nstdout:\n${stdout.takeLast(4000)}")
        if (stderr.isNotBlank()) append("\nstderr:\n${stderr.takeLast(4000)}")
    }
}

data class RecoveryPolicy(val maxConsecutiveFailures: Int = 3, val retryDelay: Duration = 2.seconds) {
    fun isRetriableLlmError(e: Exception): Boolean =
        listOf("rate", "timeout", "temporar", "unavailable", "429", "502", "503", "504")
            .any { e.message.orEmpty().contains(it, ignoreCase = true) }
    fun delayFor(attempt: Int): Duration = retryDelay * min(1 shl attempt.coerceIn(0, 6), 64)
    companion object { val DEFAULT = RecoveryPolicy() }
}

data class OrchestratorActivityEvent(
    val runId: String,
    val type: Type,
    val detail: String,
    val phase: AgentPhase = AgentPhase.Request,
    val timestamp: Long = System.currentTimeMillis(),
) {
    enum class Type {
        Started, Resumed, PhaseChanged, ModelDelta, LlmError, WaitingApproval, Blocked,
        ToolCall, ToolSuccess, ToolFailure, Observation, Retrying, LoopDetected,
        VerificationFailed, BudgetExhausted, Finished,
    }
}

fun ToolRuntime.availableToolIds(): List<String> = listOf(
    "terminal.exec", "terminal.exec_stream", "terminal.input", "terminal.cancel",
    "fs.list", "fs.read", "fs.write", "fs.patch", "fs.move", "fs.delete", "fs.search",
    "git.status", "git.diff", "git.log", "git.branch", "git.checkout", "git.commit",
    "process.list", "process.kill", "port.open", "port.close", "preview.open",
    "sandbox.info", "sandbox.snapshot", "browser.open", "browser.read", "browser.click",
    "browser.type", "browser.back", "browser.extract", "browser.close", "analyze_file",
)

private fun RunStatus.toTerminalPhase(current: AgentPhase): AgentPhase = when (this) {
    RunStatus.Completed -> AgentPhase.Completed
    RunStatus.Failed -> AgentPhase.Failed
    RunStatus.Cancelled -> AgentPhase.Cancelled
    else -> current
}
