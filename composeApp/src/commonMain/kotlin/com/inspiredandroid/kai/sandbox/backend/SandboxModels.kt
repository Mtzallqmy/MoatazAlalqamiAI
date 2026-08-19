package com.inspiredandroid.kai.sandbox.backend

import com.inspiredandroid.kai.linux.LinuxDistro
import kotlin.time.Duration

// ---------- Lifecycle ----------

/** Lifecycle states from the platform target architecture. */
enum class SandboxLifecycle {
    CREATING,
    BOOTING,
    READY,
    BUSY,
    STOPPED,
    PAUSED,
    ERROR,
    DESTROYING,
    DESTROYED,
    ;

    val isActive: Boolean get() = this == READY || this == BUSY
    val isTerminal: Boolean get() = this == DESTROYED
}

/** An existing or newly provisioned sandbox environment. */
data class SandboxInstance(
    val id: String,
    val config: SandboxConfig,
    val lifecycle: SandboxLifecycle = SandboxLifecycle.READY,
    val distro: LinuxDistro,
    val createdEpochMs: Long = currentTimeMs(),
    /** Resource currently observed (may lag behind the profile). */
    val observedCpuCores: Int? = null,
    val observedRamMiB: Long? = null,
)

/** Aggregate state observation for the UI and router. */
data class SandboxState(
    val lifecycle: SandboxLifecycle = SandboxLifecycle.DESTROYED,
    val error: String? = null,
    val distro: LinuxDistro = LinuxDistro.UBUNTU,
    val progress: Float? = null,
    val statusText: String = "",
    val diskUsageMB: Long = 0,
)

fun currentTimeMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()


// ---------- Execution ----------

/** Request to run a command inside a sandbox. */
data class ExecRequest(
    val command: String,
    val args: List<String> = emptyList(),
    val workingDirectory: String? = null,
    /** Environment variables merged on top of the sandbox's defaults. */
    val environment: Map<String, String> = emptyMap(),
    val timeout: Duration? = null,
    /** stdin lines to send before EOF; mutually exclusive with interactive use. */
    val stdin: List<String> = emptyList(),
    val pty: Boolean = false,
)

/** One-shot execution outcome. */
data class ExecResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val durationMs: Long = 0,
) {
    val succeeded: Boolean get() = exitCode == 0
}

/** Streaming output observer. */
interface ExecStreamListener {
    fun onStdout(line: String) {}
    fun onStderr(line: String) {}
    fun onExit(exitCode: Int) {}
}

// ---------- Filesystem ----------

/** A filesystem entry returned by [SandboxBackend.listFiles]. */
data class SandboxFile(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
)

// ---------- Processes ----------

/** A running process returned by [SandboxBackend.listProcesses]. */
data class SandboxProcess(
    val pid: Long,
    val ppid: Long? = null,
    val user: String? = null,
    val cpuPercent: Double? = null,
    val rssMB: Long? = null,
    val state: String? = null,
    val commandLine: String,
    val startedEpochMs: Long? = null,
)

// ---------- Ports ----------

/** A sandbox port exposed through the backend's preview/proxy layer. */
data class ExposedPort(
    val sandboxId: String,
    val port: Int,
    val protocol: String,
    /** Backend-scoped URL the app UI opens — never direct host access. */
    val url: String,
    val expiresEpochMs: Long? = null,
)

// ---------- Snapshots ----------

/** Point-in-time sandbox snapshot. */
data class SandboxSnapshot(
    val id: String,
    val sandboxId: String,
    val label: String,
    val createdEpochMs: Long,
    val sizeBytes: Long = 0,
)
