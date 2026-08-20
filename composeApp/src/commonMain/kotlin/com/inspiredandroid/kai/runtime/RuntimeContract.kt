package com.inspiredandroid.kai.runtime

import kotlinx.serialization.Serializable

object MoatazRuntimeContract {
    const val distro = "debian"
    const val versionMajor = 13
    const val codename = "trixie"
    const val architecture = "arm64"
    const val workspaceRoot = "/workspace"
    const val legacyProjectsRoot = "/root/projects"

    val requiredCli = listOf(
        "bash", "sh", "git", "curl", "wget", "tar", "xz", "python3",
        "ps", "pgrep", "pkill", "jq", "rg", "ssh", "rsync", "file", "sha256sum",
    )

    const val requiredEmbeddedAgent = "opencode"
}

@Serializable
data class RootfsAssetPart(
    val name: String,
    val sha256: String,
    val sizeBytes: Long,
)

@Serializable
data class RootfsManifest(
    val schemaVersion: Int,
    val distro: String,
    val version: String,
    val codename: String,
    val architecture: String,
    val buildId: String,
    val sha256: String,
    val requiredCli: List<String>,
    val createdAt: String,
    val assetParts: List<RootfsAssetPart> = emptyList(),
    val embeddedCli: Map<String, String> = emptyMap(),
) {
    fun isProductionRuntime(): Boolean =
        schemaVersion == 2 &&
            distro == MoatazRuntimeContract.distro &&
            version.substringBefore('.').toIntOrNull() == MoatazRuntimeContract.versionMajor &&
            codename == MoatazRuntimeContract.codename &&
            architecture == MoatazRuntimeContract.architecture &&
            sha256.matches(Regex("[0-9a-f]{64}")) &&
            requiredCli.containsAll(MoatazRuntimeContract.requiredCli) &&
            assetParts.isNotEmpty() &&
            assetParts.all { it.name.isNotBlank() && it.sha256.matches(Regex("[0-9a-f]{64}")) && it.sizeBytes > 0 } &&
            !embeddedCli[MoatazRuntimeContract.requiredEmbeddedAgent].isNullOrBlank()
}

data class OsRelease(
    val id: String?,
    val versionId: String?,
    val codename: String?,
) {
    val versionMajor: Int? get() = versionId?.substringBefore('.')?.toIntOrNull()

    companion object {
        fun parse(content: String): OsRelease {
            val values = content.lineSequence().mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith('#')) return@mapNotNull null
                val separator = trimmed.indexOf('=')
                if (separator <= 0) null else trimmed.substring(0, separator) to
                    trimmed.substring(separator + 1).trim().trim('"', '\'')
            }.toMap()
            return OsRelease(values["ID"], values["VERSION_ID"], values["VERSION_CODENAME"])
        }
    }
}

sealed interface EnvironmentIssue {
    val code: String
    val detail: String
    val repairable: Boolean

    data class MissingNative(override val detail: String) : EnvironmentIssue {
        override val code = "missing_native"
        override val repairable = true
    }
    data class WrongArchitecture(override val detail: String) : EnvironmentIssue {
        override val code = "wrong_architecture"
        override val repairable = false
    }
    data class WrongDistro(override val detail: String) : EnvironmentIssue {
        override val code = "wrong_distro"
        override val repairable = true
    }
    data class OldDebianVersion(override val detail: String) : EnvironmentIssue {
        override val code = "old_debian_version"
        override val repairable = true
    }
    data class BrokenShell(override val detail: String) : EnvironmentIssue {
        override val code = "broken_shell"
        override val repairable = true
    }
    data class MissingCli(val executable: String, override val detail: String) : EnvironmentIssue {
        override val code = "missing_cli"
        override val repairable = true
    }
    data class WorkspaceMountMissing(override val detail: String) : EnvironmentIssue {
        override val code = "workspace_mount_missing"
        override val repairable = true
    }
    data class PtyUnavailable(override val detail: String) : EnvironmentIssue {
        override val code = "pty_unavailable"
        override val repairable = true
    }
    data class BootProbeFailed(override val detail: String) : EnvironmentIssue {
        override val code = "boot_probe_failed"
        override val repairable = true
    }
    data class AgentBinaryBroken(override val detail: String) : EnvironmentIssue {
        override val code = "agent_binary_broken"
        override val repairable = true
    }
}

enum class EnvironmentHealthStatus { Healthy, Degraded, Broken }

data class EnvironmentHealth(val issues: List<EnvironmentIssue>) {
    val status: EnvironmentHealthStatus = when {
        issues.isEmpty() -> EnvironmentHealthStatus.Healthy
        issues.all { it.repairable } -> EnvironmentHealthStatus.Degraded
        else -> EnvironmentHealthStatus.Broken
    }
    val isReady: Boolean get() = issues.isEmpty()
}

object RuntimeReadinessGate {
    fun <T> commit(health: EnvironmentHealth, marker: T, writeMarker: (T) -> Unit): T {
        check(health.isReady) {
            health.issues.joinToString(prefix = "Runtime health check failed: ") { "${it.code}: ${it.detail}" }
        }
        writeMarker(marker)
        return marker
    }
}

sealed interface EnvironmentRepairAction {
    data class InstallPackages(val packages: List<String>) : EnvironmentRepairAction
    data object RepairShellAndUsrMerge : EnvironmentRepairAction
    data object RestoreWorkspaceMounts : EnvironmentRepairAction
    data object RestoreNativeRuntime : EnvironmentRepairAction
    data object ReinstallRuntimePreservingProjects : EnvironmentRepairAction
}

data class EnvironmentRepairPlan(
    val actions: List<EnvironmentRepairAction>,
    val requiresReinstall: Boolean,
)

object EnvironmentRepairPlanner {
    fun plan(health: EnvironmentHealth): EnvironmentRepairPlan {
        val actions = buildList {
            val missingPackages = health.issues.filterIsInstance<EnvironmentIssue.MissingCli>()
                .map { executableToPackage(it.executable) }.distinct()
            if (missingPackages.isNotEmpty()) add(EnvironmentRepairAction.InstallPackages(missingPackages))
            if (health.issues.any { it is EnvironmentIssue.BrokenShell }) add(EnvironmentRepairAction.RepairShellAndUsrMerge)
            if (health.issues.any { it is EnvironmentIssue.WorkspaceMountMissing }) add(EnvironmentRepairAction.RestoreWorkspaceMounts)
            if (health.issues.any { it is EnvironmentIssue.MissingNative }) add(EnvironmentRepairAction.RestoreNativeRuntime)
            if (health.issues.any { it is EnvironmentIssue.AgentBinaryBroken }) {
                add(EnvironmentRepairAction.ReinstallRuntimePreservingProjects)
            }
            if (health.issues.any {
                    it is EnvironmentIssue.WrongArchitecture ||
                        it is EnvironmentIssue.WrongDistro ||
                        it is EnvironmentIssue.OldDebianVersion
                }
            ) add(EnvironmentRepairAction.ReinstallRuntimePreservingProjects)
        }
        return EnvironmentRepairPlan(
            actions = actions,
            requiresReinstall = actions.any { it is EnvironmentRepairAction.ReinstallRuntimePreservingProjects },
        )
    }

    private fun executableToPackage(executable: String): String = when (executable) {
        "xz" -> "xz-utils"
        "rg" -> "ripgrep"
        "ssh" -> "openssh-client"
        "ps", "pgrep", "pkill" -> "procps"
        "sha256sum" -> "coreutils"
        "sh" -> "dash"
        else -> executable
    }
}

fun validateRuntimeIdentity(osRelease: OsRelease, architecture: String): List<EnvironmentIssue> = buildList {
    if (architecture.trim() != MoatazRuntimeContract.architecture) {
        add(EnvironmentIssue.WrongArchitecture("Expected arm64, got ${architecture.trim().ifEmpty { "unknown" }}"))
    }
    if (osRelease.id != MoatazRuntimeContract.distro) {
        add(EnvironmentIssue.WrongDistro("Expected Debian, got ${osRelease.id ?: "unknown"}"))
    } else if (osRelease.versionMajor != MoatazRuntimeContract.versionMajor) {
        add(EnvironmentIssue.OldDebianVersion("Expected Debian 13, got ${osRelease.versionId ?: "unknown"}"))
    }
}
