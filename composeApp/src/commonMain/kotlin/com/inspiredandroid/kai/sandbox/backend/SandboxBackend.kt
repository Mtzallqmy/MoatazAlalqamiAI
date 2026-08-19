package com.inspiredandroid.kai.sandbox.backend

/**
 * Unified sandbox abstraction for the Agentic Development Platform (v3.4.0+).
 *
 * The agent never knows which backend it is talking to — `LocalProotSandboxBackend`
 * (Ubuntu 26.04 via PRoot on-device) and `RemoteSandboxBackend` (Ubuntu 26.04 VM
 * via the Sandbox Gateway + Incus) both implement this exact interface.
 *
 * All data types live in this package and stay platform-agnostic: the interface,
 * models, errors and capability flags are pure Kotlin in `commonMain`, so agent
 * tooling, the orchestrator and tests never touch platform code.
 */
interface SandboxBackend {

    /** Backend identity (e.g. "local-proot", "remote-gateway"). */
    val backendId: String

    /** What this backend can actually do — checked before routing work to it. */
    val capabilities: SandboxCapabilities

    /** Last observed state, refreshed by the backend as things change. */
    val state: kotlinx.coroutines.flow.StateFlow<SandboxState>

    suspend fun create(config: SandboxConfig): SandboxInstance

    suspend fun start(id: String)

    suspend fun stop(id: String)

    suspend fun destroy(id: String)

    suspend fun exec(sandboxId: String, request: ExecRequest): ExecResult

    /**
     * Run a command with live streaming output. The returned [CommandHandle]
     * lets the caller push stdin, cancel, and await the exit code.
     */
    suspend fun execStreaming(
        sandboxId: String,
        request: ExecRequest,
        listener: ExecStreamListener,
    ): CommandHandle

    suspend fun listFiles(
        sandboxId: String,
        path: String,
        recursive: Boolean = false,
    ): List<SandboxFile>

    suspend fun readFile(sandboxId: String, path: String, maxLength: Int = 64 * 1024): ByteArray

    suspend fun writeFile(sandboxId: String, path: String, content: ByteArray)

    suspend fun deleteFile(sandboxId: String, path: String)

    suspend fun moveFile(sandboxId: String, from: String, to: String)

    suspend fun listProcesses(sandboxId: String): List<SandboxProcess>

    suspend fun killProcess(sandboxId: String, pid: Long, signal: String = "SIGTERM")

    suspend fun openPort(sandboxId: String, port: Int, protocol: String = "tcp"): ExposedPort

    suspend fun closePort(sandboxId: String, port: Int)

    suspend fun snapshot(sandboxId: String, label: String): SandboxSnapshot
}

/**
 * Handle to a running streaming command. The caller can push stdin lines,
 * cancel execution, and await the final exit code.
 */
interface CommandHandle {
    fun cancel()
    fun isCancelled(): Boolean
    suspend fun writeInput(line: String)
    suspend fun awaitExit(): Int

    companion object {
        /** Sentinel handle for backends/paths that have nothing to control. */
        val NO_OP: CommandHandle = NoOpCommandHandle
    }
}

internal object NoOpCommandHandle : CommandHandle {
    override fun cancel() {}
    override fun isCancelled(): Boolean = false
    override suspend fun writeInput(line: String) {}
    override suspend fun awaitExit(): Int = -1
}
