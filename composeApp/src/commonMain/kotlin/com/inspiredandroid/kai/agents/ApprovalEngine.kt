package com.inspiredandroid.kai.agents

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * User's overall approval posture for automated tool execution.
 */
enum class ApprovalMode {
    /** Ask before every tool call. */
    Safe,
    /** Auto-approve SafeRead and LocalWrite; ask for NetworkWrite/Dangerous. */
    Balanced,
    /** Auto-approve SafeRead, LocalWrite, and NetworkWrite; ask for Dangerous only. */
    Autonomous,
}

/**
 * Decision returned by the [ApprovalEngine] for one pending tool call.
 */
sealed class ApprovalDecision {
    data object AutoApproved : ApprovalDecision()
    data class NeedsApproval(val reason: String) : ApprovalDecision()
    data class Blocked(val reason: String) : ApprovalDecision()
}

/**
 * A pending approval request persisted until the user decides.
 */
@Serializable
data class PendingApproval(
    val id: String,
    val runId: String,
    val agentId: String,
    val stepId: String,
    val toolId: String,
    val toolName: String,
    val toolRisk: ToolRisk,
    val argsSummary: String,
    val explanation: String,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * Supervised approval engine (sections 5 & 41 of the prompt).
 *
 * Rules:
 * - A tool call with an unknown id is NEVER executed — it becomes a
 *   [ApprovalDecision.NeedsApproval] with an explicit unknown-tool reason.
 * - Malicious or malformed tool output can never change the permission
 *   policy; the policy is user configuration, read-only during runs.
 * - Destructive git operations (`reset --hard`, `clean -fd`, `push --force`)
 *   are always Dangerous and always require explicit approval regardless of
 *   the current mode.
 */
class ApprovalEngine(
    private val knownToolIds: () -> Set<String>,
) {
    fun decide(
        toolId: String,
        risk: ToolRisk,
        mode: ApprovalMode,
        argsJson: String?,
    ): ApprovalDecision {
        // Unknown tools are always gated behind a human review — this is the
        // anti-injection core rule (section 34 / 40).
        if (toolId !in knownToolIds()) {
            return ApprovalDecision.NeedsApproval("Unknown tool '$toolId' requires review")
        }

        val gitDestructive = isDestructiveGit(argsJson)
        if (gitDestructive != null) {
            return ApprovalDecision.NeedsApproval(gitDestructive)
        }

        return when (mode) {
            ApprovalMode.Safe -> ApprovalDecision.NeedsApproval("Safe mode asks before every action")
            ApprovalMode.Balanced -> when (risk) {
                ToolRisk.SafeRead, ToolRisk.LocalWrite -> ApprovalDecision.AutoApproved
                ToolRisk.NetworkWrite, ToolRisk.Dangerous -> ApprovalDecision.NeedsApproval(
                    "${risk.name} action requires approval in Balanced mode",
                )
            }
            ApprovalMode.Autonomous -> when (risk) {
                ToolRisk.SafeRead, ToolRisk.LocalWrite, ToolRisk.NetworkWrite -> ApprovalDecision.AutoApproved
                ToolRisk.Dangerous -> ApprovalDecision.NeedsApproval("Dangerous action requires explicit approval")
            }
        }
    }

    /** Never blocks; purely documents which actions are forbidden outright. */
    fun isForbidden(argsJson: String?): Boolean {
        return isDestructiveGit(argsJson) != null
    }

    fun forcedDenyReason(argsJson: String?): String? = isDestructiveGit(argsJson)?.let { "Forbidden without explicit approval: $it" }

    private fun isDestructiveGit(argsJson: String?): String? {
        if (argsJson == null) return null
        val lower = argsJson.lowercase()
        return when {
            lower.contains("reset --hard") || lower.contains("reset --hard") -> "git reset --hard is forbidden without explicit approval"
            lower.contains("git clean -fd") || lower.contains("clean -fd") -> "git clean -fd is forbidden without explicit approval"
            lower.contains("--force") && lower.contains("push") -> "force push is forbidden without explicit approval"
            else -> null
        }
    }
}

/**
 * Run store: persists agent runs and pending approvals to settings.
 */
class AgentRunStore(private val appSettings: com.inspiredandroid.kai.data.AppSettings) {
    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true }

    fun loadRuns(): List<AgentRun> = runCatching {
        val raw = appSettings.settings.getStringOrNull(KEY_RUNS)
        if (raw.isNullOrBlank()) emptyList() else json.decodeFromString<List<AgentRun>>(raw)
    }.getOrDefault(emptyList())

    fun saveRuns(runs: List<AgentRun>) {
        runCatching {
            appSettings.settings.putString(KEY_RUNS, json.encodeToString(runs.takeLast(MAX_RUNS)))
        }
    }

    fun loadPending(): List<PendingApproval> = runCatching {
        val raw = appSettings.settings.getStringOrNull(KEY_PENDING)
        if (raw.isNullOrBlank()) emptyList() else json.decodeFromString<List<PendingApproval>>(raw)
    }.getOrDefault(emptyList())

    fun savePending(list: List<PendingApproval>) {
        runCatching {
            appSettings.settings.putString(KEY_PENDING, json.encodeToString(list))
        }
    }

    fun loadAgents(): List<AgentConfig> = runCatching {
        val raw = appSettings.settings.getStringOrNull(KEY_AGENTS)
        if (raw.isNullOrBlank()) defaultAgents() else json.decodeFromString<List<AgentConfig>>(raw)
    }.getOrDefault(defaultAgents())

    fun saveAgents(agents: List<AgentConfig>) {
        runCatching {
            appSettings.settings.putString(KEY_AGENTS, json.encodeToString(agents))
        }
    }

    private fun defaultAgents(): List<AgentConfig> = AgentKind.entries.map { kind ->
        AgentConfig(id = kind.name.lowercase(), kind = kind, name = kind.displayName)
    }

    companion object {
        private const val KEY_RUNS = "agent_runs_v1"
        private const val KEY_PENDING = "agent_pending_approvals_v1"
        private const val KEY_AGENTS = "agent_configs_v1"
        private const val MAX_RUNS = 200
    }
}
