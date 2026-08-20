package com.inspiredandroid.kai.terminal.config

import com.inspiredandroid.kai.runtime.MoatazRuntimeContract
import kotlinx.serialization.Serializable

@Serializable
data class TerminalProfile(
    val id: String,
    val title: String,
    val command: String,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val cwd: String = MoatazRuntimeContract.workspaceRoot,
    val icon: String? = null,
    val isCustom: Boolean = false,
) {
    init {
        require(id.isNotBlank())
        require(title.isNotBlank())
        require(command.isNotBlank())
        require(cwd.startsWith('/'))
    }
}

@Serializable
data class TerminalProfileStore(
    val schemaVersion: Int = CURRENT_SCHEMA,
    val customProfiles: List<TerminalProfile> = emptyList(),
) {
    init { require(schemaVersion in 1..CURRENT_SCHEMA) }

    companion object { const val CURRENT_SCHEMA = 1 }
}

object TerminalProfiles {
    val builtIn = listOf(
        TerminalProfile("shell", "Shell", "bash"),
        TerminalProfile("opencode", "OpenCode", "opencode"),
        TerminalProfile("claude-code", "Claude Code", "claude"),
        TerminalProfile("grok", "Grok", "grok"),
        TerminalProfile("python", "Python", "python3"),
        TerminalProfile("node", "Node", "node"),
        TerminalProfile("git", "Git", "bash", listOf("-lc", "git status; exec bash")),
    )
}
