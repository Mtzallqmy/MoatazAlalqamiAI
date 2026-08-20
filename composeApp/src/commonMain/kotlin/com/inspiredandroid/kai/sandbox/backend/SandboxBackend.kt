package com.inspiredandroid.kai.sandbox.backend

/**
 * Unified sandbox abstraction for the Agentic Development Platform.
 *
 * The agent never needs host-specific process APIs: local Debian 13 via PRoot
 * and remote VM backends implement the same lifecycle/execution contract.
 */
interface SandboxBackend {
    val backendId: String
    val capabilities: SandboxCapabilities
    val state: kotlinx.coroutines.flow.StateFlow<SandboxState>

    suspend fun create(config: SandboxConfig): SandboxInstance
    suspend fun start(id: String)
    suspend fun stop(id: String)
    suspend fun destroy(id: String)
    suspend fun exec(sandboxId: String, request: ExecRequest): ExecResult

    /**
     * Run a command with live streaming output. When [ExecRequest.pty] is true a
     * capable backend should attach a real terminal rather than silently falling
     * back to pipes.
     */
    suspend fun execStreaming(
        sandboxId: String,
        request: ExecRequest,
        listener: ExecStreamListener,
    ): CommandHandle

    suspend fun listFiles(sandboxId: String, path: String, recursive: Boolean = false): List<SandboxFile>
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

/** Handle to a running streaming command. */
interface CommandHandle {
    fun cancel()
    fun isCancelled(): Boolean

    /** Line-oriented input retained for ordinary shells and remote backends. */
    suspend fun writeInput(line: String)

    /**
     * Raw terminal input. Existing handles remain compatible by decoding to the
     * legacy string method; PTY handles override this and write bytes unchanged.
     */
    suspend fun writeBytes(data: ByteArray) {
        writeInput(data.decodeToString())
    }

    /** Resize a live PTY. Non-PTY/backends may safely ignore it. */
    suspend fun resize(columns: Int, rows: Int) {}

    suspend fun awaitExit(): Int

    companion object {
        val NO_OP: CommandHandle = NoOpCommandHandle
    }
}

internal object NoOpCommandHandle : CommandHandle {
    override fun cancel() {}
    override fun isCancelled(): Boolean = false
    override suspend fun writeInput(line: String) {}
    override suspend fun awaitExit(): Int = -1
}
