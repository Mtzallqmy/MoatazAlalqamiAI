package com.inspiredandroid.kai.agents

import kotlinx.serialization.Serializable

/**
 * Agent definitions — each agent owns a purpose, a preferred routing profile,
 * and a default approval posture. Users can edit overrides per agent later.
 */
enum class AgentKind(val displayName: String, val description: String) {
    Supervisor("Supervisor", "Orchestrates sub-agents and multi-step runs"),
    Coding("Coding", "Reads, edits, and tests code inside the Linux sandbox"),
    Research("Research", "Gathers and synthesizes information from multiple sources"),
    Memory("Memory", "Maintains long-term memory and project context"),
    Custom("Custom", "User-defined agent with custom prompt and tools"),
}

/**
 * Agent configuration. Agents are the entities visible in the Agents tab.
 */
@Serializable
data class AgentConfig(
    val id: String,
    val kind: AgentKind = AgentKind.Coding,
    val name: String,
    val systemPrompt: String = "",
    val allowedToolIds: List<String> = emptyList(), // empty = all
    val blockedToolIds: List<String> = emptyList(),
    val autoApproveLowRisk: Boolean = false,
    val maxSteps: Int = 200,
    val routingProfileId: String = "", // "" → global profile
    val projectId: String? = null,
)

/**
 * Risk classification for every executable tool call. Approval decisions are
 * made against this classification — never against raw strings.
 */
enum class ToolRisk {
    /** Reads only, no side effects. */
    SafeRead,
    /** Low-risk writes: scratch files, conversation-internal data. */
    LocalWrite,
    /** Network reads or writes that do not affect third-party systems. */
    NetworkWrite,
    /** Anything destructive or irreversible: deletions, pushes, payments. */
    Dangerous,
}

/**
 * One step of an agent run — persisted so the Activity timeline can render it
 * after restarts.
 */
@Serializable
data class AgentStep(
    val id: String,
    val runId: String,
    val kind: StepKind,
    val title: String,
    val detail: String = "",
    val status: StepStatus = StepStatus.Queued,
    val toolId: String? = null,
    val toolArgsJson: String? = null,
    val toolResultSummary: String? = null,
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
    val durationMs: Long? = null,
)

enum class StepKind {
    Plan,
    Thought,
    ToolCall,
    CodeEdit,
    Command,
    GitOp,
    ApprovalRequest,
    Error,
    Summary,
    FileArtifact,
}

enum class StepStatus {
    Queued,
    Running,
    Done,
    Failed,
    WaitingApproval,
    Rejected,
    Cancelled,
}

/**
 * An agent run: a supervised sequence of [AgentStep] entries with an overall
 * status and cost/usage summary.
 *
 * Cancellation contract (any code that executes a run MUST honor it):
 * - A run is launched on a cancellable coroutine [Job]; user cancellation
 *   calls `cancel()` on that Job — it must NEVER be awaited with an
 *   uncancellable context.
 * - `CancellationException` must never be swallowed, wrapped, or converted
 *   into a generic network/agent error — it must propagate.
 * - After cancellation the run status MUST end up in [RunStatus.Cancelled]
 *   (never stuck in Running); the same applies per-step: cancelled steps end
 *   in [StepStatus.Cancelled] with a finishedAt timestamp.
 * - A new run always starts in [RunStatus.Queued] and only moves to Running
 *   once its executor coroutine has actually begun — an executor must never
 *   mark the run Running before it owns a cancellable Job.
 * - A crash (non-cancellation Throwable) transitions to [RunStatus.Failed] —
 *   with an Error step — so the UI never shows a live run whose executor is
 *   already dead.
 */
@Serializable
data class AgentRun(
    val id: String,
    val agentId: String,
    val agentName: String,
    val projectId: String? = null,
    val prompt: String,
    val status: RunStatus = RunStatus.Queued,
    val steps: List<AgentStep> = emptyList(),
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
    val totalInputTokens: Long = 0,
    val totalOutputTokens: Long = 0,
    val estimatedCostUsd: Double = 0.0,
)

enum class RunStatus {
    Queued,
    Running,
    Paused,
    WaitingApproval,
    Completed,
    Failed,
    Cancelled,
}
