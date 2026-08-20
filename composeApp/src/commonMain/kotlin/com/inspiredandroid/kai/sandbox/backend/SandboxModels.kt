package com.inspiredandroid.kai.sandbox.backend

import com.inspiredandroid.kai.linux.LinuxDistro
import kotlin.time.Duration

// ---------- Lifecycle ----------

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

data class SandboxInstance(
    val id: String,
    val config: SandboxConfig,
    val lifecycle: SandboxLifecycle = SandboxLifecycle.READY,
    val distro: LinuxDistro,
    val createdEpochMs: Long = currentTimeMs(),
    val observedCpuCores: Int? = null,
    val observedRamMiB: Long? = null,
)

/** Aggregate state observation for the UI and router. */
data class SandboxState(
    val lifecycle: SandboxLifecycle = SandboxLifecycle.DESTROYED,
    val error: String? = null,
    val distro: LinuxDistro = LinuxDistro.DEFAULT,
    val progress: Float? = null,
    val statusText: String = "",
    val diskUsageMB: Long = 0,
)

fun currentTimeMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

// ---------- Execution ----------

data class ExecRequest(
    val command: String,
    val args: List<String> = emptyList(),
    val workingDirectory: String? = null,
    val environment: Map<String, String> = emptyMap(),
    val timeout: Duration? = null,
    val stdin: List<String> = emptyList(),
    val pty: Boolean = false,
)

data class ExecResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val durationMs: Long = 0,
) {
    val succeeded: Boolean get() = exitCode == 0
}

interface ExecStreamListener {
    fun onStdout(line: String) {}
    fun onStderr(line: String) {}
    fun onExit(exitCode: Int) {}
}

// ---------- Filesystem ----------

data class SandboxFile(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
)

// ---------- Processes ----------

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

data class ExposedPort(
    val sandboxId: String,
    val port: Int,
    val protocol: String,
    val url: String,
    val expiresEpochMs: Long? = null,
)

// ---------- Snapshots ----------

data class SandboxSnapshot(
    val id: String,
    val sandboxId: String,
    val label: String,
    val createdEpochMs: Long,
    val sizeBytes: Long = 0,
)
