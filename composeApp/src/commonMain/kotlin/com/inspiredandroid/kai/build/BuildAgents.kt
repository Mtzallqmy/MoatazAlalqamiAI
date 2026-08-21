package com.inspiredandroid.kai.build

import androidx.compose.runtime.Immutable
import com.inspiredandroid.kai.cli.CliCategory
import com.inspiredandroid.kai.cli.CliDefinition
import com.inspiredandroid.kai.cli.CliRegistry
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * Compatibility view of agent-category definitions from [CliRegistry]. New
 * developer tools are registered there, not added to terminal or build code.
 *
 * Install locations (guest paths under `/root`):
 * - Claude → `~/.local/bin/claude` (versioned under `~/.local/share/claude/versions/`)
 * - Grok → `~/.grok/bin/grok`
 * - OpenCode → `~/.opencode/bin/opencode`
 *
 * Proot injects all three bin dirs into PATH for probes/installers. Login
 * shells rebuild PATH from profile files, so Moataz Runtime also writes a
 * profile.d snippet and launches agents by absolute path.
 */
@Immutable
data class BuildAgent(
    val definition: CliDefinition,
    val autoInstall: Boolean = false,
) {
    val id: String get() = definition.id
    val title: String get() = definition.displayName
    val binary: String get() = definition.executable
}

object BuildAgents {
    val registry = CliRegistry()
    val all: ImmutableList<BuildAgent> = persistentListOf(
        *registry.all().filter { it.category == CliCategory.Agent }
            .map { BuildAgent(it, autoInstall = it.id == "opencode") }
            .toTypedArray(),
    )

    /** Agents installed automatically with Debian — no user selection needed. */
    val autoInstallAgents: ImmutableList<BuildAgent> = persistentListOf(*all.filter { it.autoInstall }.toTypedArray())

    fun get(id: String?): BuildAgent? = all.firstOrNull { it.id == id }
}
