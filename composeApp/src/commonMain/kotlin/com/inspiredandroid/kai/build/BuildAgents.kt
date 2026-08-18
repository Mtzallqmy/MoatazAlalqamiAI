package com.inspiredandroid.kai.build

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * The coding agents Kai Build can install on a fresh Debian. Each one ships a
 * self-contained installer, so the base system plus curl is all they need.
 *
 * Install locations (guest paths under `/root`):
 * - Claude → `~/.local/bin/claude` (versioned under `~/.local/share/claude/versions/`)
 * - Grok → `~/.grok/bin/grok`
 * - OpenCode → `~/.opencode/bin/opencode`
 *
 * Proot injects all three bin dirs into PATH for probes/installers. Login
 * shells rebuild PATH from profile files, so Kai Build also writes a
 * profile.d snippet and launches agents by absolute path.
 */
@Immutable
data class BuildAgent(
    val id: String,
    /** Product name — shown as-is, not translated. */
    val title: String,
    val binary: String,
    val installCommand: String,
    /**
     * When true the agent is installed automatically right after Debian,
     * without the user ticking it on the setup screen — plus the fallback
     * mirrors below when the primary download fails.
     */
    val autoInstall: Boolean = false,
    /**
     * Mirrors used when the primary [installCommand] fails (network blip,
     * regional outage). Each entry is a drop-in replacement for the leading
     * `curl -fsSL https://... | bash` line — the trailing `| bash` is supplied
     * by the runner. Entries are tried in order until one succeeds.
     */
    val fallbackUrls: List<String> = emptyList(),
)

object BuildAgents {

    val all: ImmutableList<BuildAgent> = persistentListOf(
        BuildAgent(
            id = "claude-code",
            title = "Claude Code",
            binary = "claude",
            installCommand = "curl -fsSL https://claude.ai/install.sh",
            fallbackUrls = listOf(
                // Official installer mirrored on jsDelivr (fast CDN, global POPs).
                "curl -fsSL https://cdn.jsdelivr.net/gh/anthropics/claude-code@main/install.sh",
            ),
        ),
        BuildAgent(
            id = "grok",
            title = "Grok",
            binary = "grok",
            installCommand = "curl -fsSL https://x.ai/cli/install.sh",
            fallbackUrls = listOf(
                "curl -fsSL https://cdn.jsdelivr.net/gh/xai-org/grok-cli@main/install.sh",
            ),
        ),
        BuildAgent(
            id = "opencode",
            title = "OpenCode",
            binary = "opencode",
            installCommand = "curl -fsSL https://opencode.ai/install",
            // Backup mirrors tried in order when the primary fails — the user
            // does not need to re-tick anything, the retry is automatic.
            fallbackUrls = listOf(
                "curl -fsSL https://cdn.jsdelivr.net/gh/opencode-ai/opencode@main/scripts/install.sh",
                "curl -fsSL https://raw.githubusercontent.com/opencode-ai/opencode/main/scripts/install.sh",
            ),
            autoInstall = true,
        ),
    )

    /** Agents installed automatically with Debian — no user selection needed. */
    val autoInstallAgents: ImmutableList<BuildAgent> = persistentListOf(*all.filter { it.autoInstall }.toTypedArray())

    fun get(id: String?): BuildAgent? = all.firstOrNull { it.id == id }
}
