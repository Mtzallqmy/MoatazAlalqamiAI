package com.inspiredandroid.kai.agents

import com.inspiredandroid.kai.gateway.AiRequestOutcome
import com.inspiredandroid.kai.brand.AssistantIdentity
import com.inspiredandroid.kai.tools.ToolRiskLevel
import com.inspiredandroid.kai.tools.ToolResult
import com.inspiredandroid.kai.tools.ToolRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * The agent orchestrator — the loop that turns one user request into a
 * supervised, multi-step agent run:
 *
 *   prompt → think (LLM) → plan tool calls → approval gate → execute tools →
 *   observe results → think again → … → finish/timeout/error/recover
 *
 * Built on the platform contracts, never the raw implementations:
 * - `ToolRuntime` for the 23 sandbox tools (local or remote backend agnostic)
 * - `ApprovalEngine` for the Safe/Balanced/Autonomous gate
 * - `AgentRunStore` for persistence of runs, steps and pending approvals
 * - an injected LLM delegate (normally the AI Gateway's executor) so the
 *   orchestrator stays commonMain-clean
 *
 * Failure-recovery policy: up to [RecoveryPolicy.maxConsecutiveFailures]
 * consecutive tool failures trigger an explicit recovery-think step that asks
 * the LLM to diagnose and retry with a different approach; beyond that the
 * run finishes in [RunStatus.Failed] with the error surfaced in the activity
 * timeline instead of being retried into a burn rate.
 */
class AgentOrchestrator(
    private val toolRuntime: ToolRuntime,
    private val approvalEngine: ApprovalEngine,
    private val runStore: AgentRunStore,
    private val approvalMode: () -> ApprovalMode,
    private val llm: LlmDelegate,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    /** Streams activity events (tool calls, approvals, errors) to the UI. */
    private val _activity = MutableSharedFlow<OrchestratorActivityEvent>(extraBufferCapacity = 256)
    val activity: SharedFlow<OrchestratorActivityEvent> = _activity.asSharedFlow()

    /** In-flight runs by id. */
    private val _runs = MutableStateFlow<Map<String, AgentRun>>(emptyMap())
    val runs: StateFlow<Map<String, AgentRun>> = _runs.asStateFlow()

    /** Pending approvals awaiting a human decision. */
    private val _pending = MutableStateFlow<List<PendingApproval>>(runStore.loadPending())
    val pendingApprovals: StateFlow<List<PendingApproval>> = _pending.asStateFlow()

    private val decisions = mutableMapOf<String, ApprovalDecision>()

    val recoveryPolicy: RecoveryPolicy = RecoveryPolicy.DEFAULT

    fun startRun(agentConfig: AgentConfig, prompt: String, projectContext: String = "") {
        val run = AgentRun(
            id = newId(),
            agentId = agentConfig.id,
            agentName = agentConfig.name,
            projectId = agentConfig.projectId,
            prompt = prompt,
        )
        _runs.update { it + (run.id to run) }
        scope.launch { runLoop(run, agentConfig, projectContext) }
    }

    fun approve(id: String) = resolveApproval(id, ApprovalDecision.AutoApproved)

    fun reject(id: String) = resolveApproval(id, ApprovalDecision.Blocked("Rejected by user"))

    private fun resolveApproval(id: String, decision: ApprovalDecision) {
        val pending = _pending.value.find { it.id == id }
        if (pending != null) {
            ApprovalAuditLog.record(
                toolId = pending.toolName,
                toolRisk = pending.toolRisk.name,
                argsSummary = pending.argsSummary,
                verdict = if (decision is ApprovalDecision.Blocked) ApprovalAuditLog.Verdict.Rejected else ApprovalAuditLog.Verdict.Approved,
            )
        }
        decisions[id] = decision
        _pending.update { pendingList -> pendingList.filterNot { it.id == id } }
        runStore.savePending(_pending.value)
    }

    private suspend fun runLoop(run: AgentRun, config: AgentConfig, projectContext: String) {
        try {
            _runs.update { it + (run.id to run.copy(status = RunStatus.Running)) }
            emitActivity(run.id, OrchestratorActivityEvent.Type.Started, run.prompt)

            val context = buildContext(projectContext)
            var messageHistory = mutableListOf<LlmMessage>(LlmMessage(role = "system", content = buildSystemPrompt(config)))
            var remainingSteps = config.maxSteps.coerceIn(1, 1000)
            var consecutiveFailures = 0

            while (remainingSteps > 0 && _runs.value[run.id]?.status == RunStatus.Running) {
                remainingSteps--

                // 1. Think: ask the LLM for the next step given the full history.
                val response = try {
                    llm.complete(LlmCompletionRequest(messages = messageHistory, tools = toolRuntime.availableToolIds()))
                } catch (ce: CancellationException) {
                    finishRun(run.id, RunStatus.Cancelled)
                    throw ce
                } catch (e: Exception) {
                    appendFailureStep(run.id, StepKind.Error, "LLM unavailable: ${e.message ?: e::class.simpleName}", "")
                    emitActivity(run.id, OrchestratorActivityEvent.Type.LlmError, e.message ?: "LLM call failed")
                    if (recoveryPolicy.isRetriableLlmError(e)) {
                        delay(recoveryPolicy.retryDelay)
                        continue
                    }
                    finishRun(run.id, RunStatus.Failed)
                    break
                }

                if (response.isFinalAnswer) {
                    appendStep(run.id, StepKind.Summary, "Completed", response.content)
                    emitActivity(run.id, OrchestratorActivityEvent.Type.Finished, response.content.take(200))
                    finishRun(run.id, RunStatus.Completed)
                    break
                }

                // 2. Gate: approval before any tool call.
                val pendingTools = response.toolCalls.filter { isAllowed(config, it.toolId) }
                if (response.toolCalls.isNotEmpty() && pendingTools.isEmpty()) {
                    appendStep(run.id, StepKind.ApprovalRequest, "All tool calls blocked by agent policy", "")
                    emitActivity(run.id, OrchestratorActivityEvent.Type.Blocked, "Tool calls not allowed for this agent")
                    finishRun(run.id, RunStatus.Failed)
                    break
                }

                // 3. Execute each approved tool call.
                for (call in pendingTools) {
                    val decision = approvalEngine.decide(
                        toolId = call.toolId,
                        risk = mapRisk(toolRuntime.riskLevelFor(call.toolId)),
                        mode = approvalMode(),
                        argsJson = call.argsJson,
                    )
                    when (decision) {
                        is ApprovalDecision.AutoApproved -> {
                            ApprovalAuditLog.record(
                                toolId = call.toolId,
                                toolRisk = toolRuntime.riskLevelFor(call.toolId).name,
                                argsSummary = call.argsJson.orEmpty().take(120),
                                verdict = ApprovalAuditLog.Verdict.AutoApproved,
                            )
                            executeToolCall(run, config, call)
                        }
                        is ApprovalDecision.Blocked -> {
                            ApprovalAuditLog.record(
                                toolId = call.toolId,
                                toolRisk = toolRuntime.riskLevelFor(call.toolId).name,
                                argsSummary = call.argsJson.orEmpty().take(120),
                                verdict = ApprovalAuditLog.Verdict.Blocked,
                                note = decision.reason,
                            )
                            appendStep(run.id, StepKind.Error, "Blocked: ${decision.reason}", "")
                            consecutiveFailures++
                        }
                        is ApprovalDecision.NeedsApproval -> {
                            val approvalId = newId()
                            val stepId = appendStep(run.id, StepKind.ApprovalRequest, "Waiting approval: ${call.toolId}", call.argsJson ?: "")
                            val pending = PendingApproval(
                                id = approvalId,
                                runId = run.id,
                                agentId = config.id,
                                stepId = stepId,
                                toolId = call.toolId,
                                toolName = call.toolId,
                                toolRisk = mapRisk(toolRuntime.riskLevelFor(call.toolId)),
                                argsSummary = (call.argsJson ?: "").take(500),
                                explanation = decision.reason,
                            )
                            _pending.update { (it + pending).takeLast(50) }
                            runStore.savePending(_pending.value)
                            _runs.update {
                                it + (run.id to (it[run.id] ?: run).copy(status = RunStatus.WaitingApproval))
                            }
                            emitActivity(run.id, OrchestratorActivityEvent.Type.WaitingApproval, "${call.toolId}: ${decision.reason}")

                            var wait = 0
                            while (wait < APPROVAL_TIMEOUT_MINUTES * 6 && decisions[approvalId] == null) {
                                delay(10_000L)
                                wait++
                                if (_runs.value[run.id]?.status == RunStatus.Cancelled) return
                            }
                            val taken = decisions.remove(approvalId) ?: ApprovalDecision.Blocked("Approval timed out")
                            when (taken) {
                                is ApprovalDecision.AutoApproved -> executeToolCall(run, config, call)
                                is ApprovalDecision.Blocked -> {
                                    appendFailureStep(run.id, StepKind.ApprovalRequest, "Rejected: ${taken.reason}", "")
                                    consecutiveFailures++
                                }
                                else -> {}
                            }
                        }
                    }
                }

                if (consecutiveFailures >= recoveryPolicy.maxConsecutiveFailures) {
                    appendStep(run.id, StepKind.Error, "Too many consecutive failures (${consecutiveFailures}) — run ended by recovery policy", "")
                    finishRun(run.id, RunStatus.Failed)
                    break
                }

                // 4. Feed observation back and continue the loop.
                messageHistory += response.toHistoryMessages()
            }

            if (remainingSteps <= 0 && _runs.value[run.id]?.status == RunStatus.Running) {
                appendStep(run.id, StepKind.Summary, "Step budget exhausted", "Run stopped after ${config.maxSteps} steps")
                emitActivity(run.id, OrchestratorActivityEvent.Type.BudgetExhausted, "${config.maxSteps} steps")
                finishRun(run.id, RunStatus.Failed)
            }
        } catch (ce: CancellationException) {
            finishRun(run.id, RunStatus.Cancelled)
            throw ce
        }
    }

    private suspend fun executeToolCall(run: AgentRun, config: AgentConfig, call: LlmToolCall) {
        val stepId = appendStep(run.id, StepKind.ToolCall, call.toolId, call.argsJson ?: "", StepStatus.Running)
        emitActivity(run.id, OrchestratorActivityEvent.Type.ToolCall, "${call.toolId}: ${(call.argsJson ?: "").take(120)}")
        try {
            val result = toolRuntime.call(call.toolId, call.args)
            when (result) {
                is ToolResult.Success -> {
                    updateStep(run.id, stepId, StepStatus.Done, result.message)
                    emitActivity(run.id, OrchestratorActivityEvent.Type.ToolSuccess, call.toolId)
                }
                is ToolResult.Failure -> {
                    updateStep(run.id, stepId, StepStatus.Failed, result.error)
                    emitActivity(run.id, OrchestratorActivityEvent.Type.ToolFailure, "${call.toolId}: ${result.error}")
                }
            }
        } catch (ce: CancellationException) {
            updateStep(run.id, stepId, StepStatus.Cancelled, "Cancelled by user")
            throw ce
        } catch (e: Exception) {
            updateStep(run.id, stepId, StepStatus.Failed, e.message ?: e::class.simpleName ?: "error")
            emitActivity(run.id, OrchestratorActivityEvent.Type.ToolFailure, "${call.toolId}: ${e.message ?: "error"}")
        }
    }

    private fun finishRun(runId: String, status: RunStatus) {
        _runs.update {
            val run = it[runId] ?: return@update it
            it + (runId to run.copy(status = status, finishedAt = currentTime()))
        }
        // Browser session cleanup — every terminal state closes the run's session.
        toolRuntime.browserDispatcher?.let { dispatcher ->
            scope.launch { dispatcher.cleanupRun(runId) }
        }
    }

    // ---------- Helpers ----------

    private fun buildContext(projectContext: String): Map<String, String> =
        if (projectContext.isBlank()) emptyMap() else mapOf("project" to projectContext)

    private fun buildSystemPrompt(config: AgentConfig): String = buildString {
        appendLine(config.systemPrompt.ifBlank { SYSTEM_PROMPT })
        appendLine("Available tools: ${toolRuntime.availableToolIds().joinToString(", ")}")
        appendLine("Risk policy: ${approvalMode().name} — auto-approve per-mode; destructive operations always require approval.")
    }

    private fun isAllowed(config: AgentConfig, toolId: String): Boolean {
        if (config.allowedToolIds.isNotEmpty() && toolId !in config.allowedToolIds) return false
        if (toolId in config.blockedToolIds) return false
        return toolId in toolRuntime.availableToolIds()
    }

    private fun mapRisk(level: ToolRiskLevel): ToolRisk = when (level) {
        ToolRiskLevel.READ_ONLY -> ToolRisk.SafeRead
        ToolRiskLevel.WORKSPACE_WRITE, ToolRiskLevel.PACKAGE_INSTALL -> ToolRisk.LocalWrite
        ToolRiskLevel.NETWORK, ToolRiskLevel.PROCESS_CONTROL -> ToolRisk.NetworkWrite
        ToolRiskLevel.GIT_WRITE -> ToolRisk.NetworkWrite
        ToolRiskLevel.SECRET_ACCESS, ToolRiskLevel.DESTRUCTIVE -> ToolRisk.Dangerous
    }

    private fun appendStep(runId: String, kind: StepKind, title: String, detail: String, status: StepStatus = StepStatus.Done): String {
        val step = AgentStep(id = newId(), runId = runId, kind = kind, title = title, detail = detail, status = status, finishedAt = currentTime())
        _runs.update {
            val run = it[runId] ?: return@update it
            it + (runId to run.copy(steps = run.steps + step))
        }
        return step.id
    }

    private fun updateStep(runId: String, stepId: String, status: StepStatus, resultSummary: String?) {
        _runs.update {
            val run = it[runId] ?: return@update it
            val idx = run.steps.indexOfFirst { it.id == stepId }
            if (idx == -1) return@update it
            val old = run.steps[idx]
            val updated = old.copy(status = status, toolResultSummary = resultSummary, finishedAt = currentTime(), durationMs = currentTime() - old.startedAt)
            it + (runId to run.copy(steps = run.steps.toMutableList().also { l -> l[idx] = updated }))
        }
    }

    private fun appendFailureStep(runId: String, kind: StepKind, title: String, detail: String) {
        appendStep(runId, kind, title, detail, StepStatus.Failed)
    }

    private fun emitActivity(runId: String, type: OrchestratorActivityEvent.Type, detail: String) {
        scope.launch { _activity.emit(OrchestratorActivityEvent(runId, type, detail)) }
    }

    private fun currentTime(): Long = System.currentTimeMillis()
    private fun newId(): String = (currentTime().toString(36) + kotlin.random.Random.nextLong().toString(36)).take(12)

    // ---------- LLM delegate (injected; AI Gateway provides the real one) ----------

    interface LlmDelegate {
        suspend fun complete(request: LlmCompletionRequest): LlmCompletionResponse
    }

    data class LlmCompletionRequest(
        val messages: List<LlmMessage>,
        val tools: List<String>,
    )

    data class LlmMessage(val role: String, val content: String)

    data class LlmCompletionResponse(
        /** Final textual answer — no more tool calls. */
        val isFinalAnswer: Boolean,
        val content: String,
        val toolCalls: List<LlmToolCall> = emptyList(),
    ) {
        fun toHistoryMessages(): LlmMessage = LlmMessage(
            role = "assistant",
            content = if (isFinalAnswer) content else toolCalls.joinToString("\n") { "${it.toolId}: ${it.argsJson ?: ""}" },
        )
    }

    data class LlmToolCall(
        val toolId: String,
        val args: Map<String, Any?>,
        val argsJson: String? = null,
    )

    companion object {
        private const val APPROVAL_TIMEOUT_MINUTES = 30

        val SYSTEM_PROMPT: String = """
            You are ${AssistantIdentity.Default.systemIdentity}, running as a developer agent inside Debian 13 Trixie arm64 in Moataz Runtime on the user's Android device.
            The canonical project root is /workspace.
            Prefer reading before editing. Break tasks into small verifiable steps: write files, install dependencies,
            build, run the dev server, and expose its port so the user can preview it. After every command, inspect the
            output; if a build or test fails, diagnose the error, apply a fix, and retry. When done, summarize what was
            created, which ports are exposed, and what the user can preview.
        """.trimIndent()
    }
}

/** Recovery policy — how many failures before the run gives up. */
data class RecoveryPolicy(
    val maxConsecutiveFailures: Int = 3,
    val retryDelay: Duration = 2.minutes,
) {
    fun isRetriableLlmError(e: Exception): Boolean =
        e.message?.contains("rate", ignoreCase = true) == true ||
            e.message?.contains("timeout", ignoreCase = true) == true

    companion object {
        val DEFAULT = RecoveryPolicy()
    }
}

/** Activity timeline event emitted by the orchestrator. */
data class OrchestratorActivityEvent(
    val runId: String,
    val type: Type,
    val detail: String,
) {
    enum class Type {
        Started,
        LlmError,
        WaitingApproval,
        Blocked,
        ToolCall,
        ToolSuccess,
        ToolFailure,
        BudgetExhausted,
        Finished,
    }
}

/** Exposed tool set for the LLM (kept simple — full function schemas live in a later phase). */
fun ToolRuntime.availableToolIds(): List<String> = listOf(
    "terminal.exec", "terminal.exec_stream", "terminal.input", "terminal.cancel",
    "fs.list", "fs.read", "fs.write", "fs.patch", "fs.move", "fs.delete", "fs.search",
    "git.status", "git.diff", "git.log", "git.branch", "git.checkout", "git.commit",
    "process.list", "process.kill",
    "port.open", "port.close", "preview.open",
    "sandbox.info", "sandbox.snapshot",
    "browser.open", "browser.read", "browser.click", "browser.type",
    "browser.back", "browser.extract", "browser.close",
    "analyze_file",
)
