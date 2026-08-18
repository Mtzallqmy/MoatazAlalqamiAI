package com.inspiredandroid.kai.sandbox.backend

import android.util.Log
import com.inspiredandroid.kai.linux.GuestFileMap
import com.inspiredandroid.kai.linux.LinuxDistro
import com.inspiredandroid.kai.sandbox.LinuxSandboxManager
import com.inspiredandroid.kai.sandbox.SessionShell
import com.inspiredandroid.kai.sandbox.toFileEntry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * `LocalProotSandboxBackend` — the on-device Ubuntu 26.04 (PRoot) backend.
 *
 * Does NOT re-implement anything: it delegates lifecycle to `LinuxSandboxManager`,
 * command execution to its `SessionShell` (which owns `PersistentSandboxShell`),
 * and file operations to the same `GuestFileMap` the Files tab uses. It exists to
 * give the agent, tool runtime and orchestrator one stable interface that the
 * future `RemoteSandboxBackend` will mirror.
 *
 * Sandbox ids are logical ids the agent picks; the backend maps each to the
 * selected distro's single rootfs via its own session.
 */
class LocalProotSandboxBackend(
    private val sandboxManager: LinuxSandboxManager,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : SandboxBackend {

    override val backendId: String = "local-proot"

    override val capabilities: SandboxCapabilities = SandboxCapabilities.LOCAL_PROOT

    private val _state = MutableStateFlow(SandboxState())
    override val state: StateFlow<SandboxState> = _state
    /** Snapshot accessor — the interface exposes the current value directly. */
    fun currentState(): SandboxState = _state.value

    /** Logical sandbox id -> session id inside the manager's shell registry. */
    private val sessions = ConcurrentHashMap<String, String>()

    /** Logical sandbox id -> running streaming handles (multiple commands may run). */
    private val runningHandles = ConcurrentHashMap<String, MutableList<SessionCommandHandle>>()

    private val idCounter = java.util.concurrent.atomic.AtomicInteger(0)

    init {
        refreshState()
    }

    fun refreshState() {
        val managerState = sandboxManager.state.value
        _state.update {
            SandboxState(
                lifecycle = when (managerState) {
                    is com.inspiredandroid.kai.sandbox.SandboxState.Ready -> SandboxLifecycle.READY
                    is com.inspiredandroid.kai.sandbox.SandboxState.Installing -> SandboxLifecycle.CREATING
                    is com.inspiredandroid.kai.sandbox.SandboxState.Downloading -> SandboxLifecycle.CREATING
                    is com.inspiredandroid.kai.sandbox.SandboxState.Extracting -> SandboxLifecycle.CREATING
                    is com.inspiredandroid.kai.sandbox.SandboxState.Error -> SandboxLifecycle.ERROR
                    else -> SandboxLifecycle.DESTROYED
                },
                distro = sandboxManager.distro,
                diskUsageMB = sandboxManager.getDiskUsageMB(),
            )
        }
    }

    override suspend fun create(config: SandboxConfig): SandboxInstance {
        if (!isEnvironmentReady()) {
            throw SandboxError.ConfigurationError("install", "Local Ubuntu environment is not installed — call install() first")
        }
        val id = "local-${idCounter.incrementAndGet()}-${UUID.randomUUID().toString().take(8)}"
        sessions[id] = sandboxIdFor(id)
        return SandboxInstance(
            id = id,
            config = config,
            lifecycle = SandboxLifecycle.READY,
            distro = sandboxManager.distro,
        )
    }

    private fun sandboxIdFor(id: String): String =
        if (id.startsWith("local-")) id.substringAfter("local-").substringBeforeLast('-') else id

    override suspend fun start(id: String) {
        ensureSandbox(id)
        refreshState()
    }

    override suspend fun stop(id: String) {
        closeSessionFor(id)
        refreshState()
    }

    override suspend fun destroy(id: String) {
        closeSessionFor(id)
        sessions.remove(id)
        runningHandles.remove(id)
    }

    override suspend fun exec(sandboxId: String, request: ExecRequest): ExecResult {
        ensureSandbox(sandboxId)
        val shell = shellFor(sandboxId)
        val timeoutSec = (request.timeout?.inWholeSeconds?.coerceIn(1, 600)) ?: 120
        return withContext(Dispatchers.IO) {
            try {
                val raw = shell.run(
                    command = buildCommand(request),
                    timeoutSeconds = timeoutSec,
                    displayCommand = request.command,
                )
                ExecResult(
                    exitCode = (raw["exit_code"] as? Int) ?: -1,
                    stdout = raw["stdout"] as? String ?: "",
                    stderr = raw["stderr"] as? String ?: "",
                )
            } catch (e: Exception) {
                ExecResult(exitCode = -1, stdout = "", stderr = e.message ?: e::class.simpleName ?: "exec failed")
            }
        }
    }

    override suspend fun execStreaming(
        sandboxId: String,
        request: ExecRequest,
        listener: ExecStreamListener,
    ): CommandHandle {
        ensureSandbox(sandboxId)
        val shell = shellFor(sandboxId)
        val handle = SessionCommandHandle(shell, request, listener)
        runningHandles.getOrPut(sandboxId) { mutableListOf() }.add(handle)
        return handle
    }

    override suspend fun listFiles(
        sandboxId: String,
        path: String,
        recursive: Boolean,
    ): List<SandboxFile> = withContext(Dispatchers.IO) {
        val fileMap = sandboxManager.fileMap()
        val target = fileMap.resolve(path)
            ?: throw SandboxError.PermissionDenied(path)
        val files = mutableListOf<SandboxFile>()
        walk(target, path, fileMap, recursive, files)
        files
    }

    private fun walk(file: File, guestParent: String, fileMap: GuestFileMap, recursive: Boolean, out: MutableList<SandboxFile>) {
        val entries = file.listFiles() ?: return
        for (entry in entries.sortedBy { it.name }) {
            val guestPath = if (guestParent == "/") "/${entry.name}" else "$guestParent/${entry.name}"
            out += entry.toFileEntry("").copy(
                name = entry.name,
                path = guestPath,
            ).let { SandboxFile(it.name, it.path, it.isDirectory, it.sizeBytes, it.lastModifiedMs) }
            if (recursive && entry.isDirectory && out.size < 500) walk(entry, guestPath, fileMap, recursive, out)
        }
    }

    override suspend fun readFile(sandboxId: String, path: String, maxLength: Int): ByteArray =
        withContext(Dispatchers.IO) {
            val file = sandboxManager.fileMap().resolve(path)
                ?: throw SandboxError.PermissionDenied(path)
            if (!file.isFile) throw SandboxError.PermissionDenied(path)
            val bytes = file.readBytes()
            if (bytes.size > maxLength) bytes.copyOfRange(0, maxLength) else bytes
        }

    override suspend fun writeFile(sandboxId: String, path: String, content: ByteArray) =
        withContext(Dispatchers.IO) {
            val file = sandboxManager.fileMap().resolve(path)
                ?: throw SandboxError.PermissionDenied(path)
            file.parentFile?.mkdirs()
            file.writeBytes(content)
        }

    override suspend fun deleteFile(sandboxId: String, path: String) =
        withContext(Dispatchers.IO) {
            val file = sandboxManager.fileMap().resolve(path)
                ?: throw SandboxError.PermissionDenied(path)
            if (sandboxManager.fileMap().isRoot(file)) {
                throw SandboxError.PolicyDenied("delete-root", "Refusing to delete a sandbox bind root")
            }
            if (!file.deleteRecursively()) throw SandboxError.CommandFailed(-1, "", "Delete failed: $path")
        }

    override suspend fun moveFile(sandboxId: String, from: String, to: String) =
        withContext(Dispatchers.IO) {
            val fileMap = sandboxManager.fileMap()
            val src = fileMap.resolve(from) ?: throw SandboxError.PermissionDenied(from)
            val dst = fileMap.resolve(to) ?: throw SandboxError.PermissionDenied(to)
            if (fileMap.isRoot(src)) throw SandboxError.PolicyDenied("move-root", "Refusing to move a sandbox bind root")
            dst.parentFile?.mkdirs()
            if (!src.renameTo(dst)) throw SandboxError.CommandFailed(-1, "", "Rename failed: $from -> $to")
        }

    override suspend fun listProcesses(sandboxId: String): List<SandboxProcess> {
        ensureSandbox(sandboxId)
        val result = exec(sandboxId, ExecRequest("ps -eo pid,ppid,user,pcpu,rss,stat,etime,cmd --no-headers"))
        return result.stdout.lines().mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 8) return@mapNotNull null
            SandboxProcess(
                pid = parts[0].toLongOrNull() ?: return@mapNotNull null,
                ppid = parts[1].toLongOrNull(),
                user = parts[2].takeIf { it != "-" },
                cpuPercent = parts[3].toDoubleOrNull(),
                rssMB = parts[4].toLongOrNull()?.div(1024),
                state = parts[5].takeIf { it != "-" },
                commandLine = parts.drop(7).joinToString(" "),
            )
        }
    }

    override suspend fun killProcess(sandboxId: String, pid: Long, signal: String): Unit =
        exec(sandboxId, ExecRequest("kill -$signal $pid"))
            .takeUnless { it.succeeded }
            ?.let { throw SandboxError.ProcessFailed(pid, signal) }
            ?: Unit

    override suspend fun openPort(sandboxId: String, port: Int, protocol: String): ExposedPort {
        if (port !in 1..65535) throw SandboxError.ConfigurationError("port", "Invalid port: $port")
        // Local backend exposes via loopback; the app previews the PRoot-bound
        // port directly through the same mapping the Terminal uses.
        return ExposedPort(
            sandboxId = sandboxId,
            port = port,
            protocol = protocol,
            url = "http://127.0.0.1:$port",
        )
    }

    override suspend fun closePort(sandboxId: String, port: Int) {
        // PRoot-bind ephemeral ports are released when their owning process dies.
        exec(sandboxId, ExecRequest("fuser -k $port/tcp 2>/dev/null || true"))
    }

    override suspend fun snapshot(sandboxId: String, label: String): SandboxSnapshot {
        // PRoot snapshots are advisory: the rootfs is a plain directory tree and
        // the home directory carries the user's data; we tag the timestamp.
        return SandboxSnapshot(
            id = "snap-${UUID.randomUUID().toString().take(8)}",
            sandboxId = sandboxId,
            label = label,
            createdEpochMs = currentTimeMs(),
        )
    }

    private fun ensureSandbox(sandboxId: String) {
        if (!sessions.containsKey(sandboxId)) {
            throw SandboxError.SandboxUnavailable(sandboxId, "Sandbox not created on this backend")
        }
        if (!isEnvironmentReady()) {
            throw SandboxError.SandboxUnavailable(sandboxId, "Local Ubuntu environment is not installed")
        }
    }

    private fun shellFor(sandboxId: String): SessionShell {
        val sessionId = sessions[sandboxId] ?: error("no session for $sandboxId")
        return sandboxManager.shellFor(sessionId)
    }

    private fun closeSessionFor(id: String) {
        sessions[id]?.let { sandboxManager.closeShell(it) }
    }

    /** The rootfs is installed and settled (not mid-install). */
    private fun isEnvironmentReady(): Boolean {
        val current = sandboxManager.state.value
        return current is com.inspiredandroid.kai.sandbox.SandboxState.Ready
    }

    /**
     * Wraps a `SessionShell.run` call into the shared [CommandHandle] contract:
     * stdin lines are written after start, cancel kills the foreground process,
     * and awaitExit suspends until the sentinel completes.
     */
    private class SessionCommandHandle(
        private val shell: SessionShell,
        private val request: ExecRequest,
        private val listener: ExecStreamListener,
    ) : CommandHandle {

        private val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        private val exit = CompletableDeferred<Int>()
        private val runner = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        init {
            runner.launch {
                try {
                    val raw = shell.run(
                        command = buildCommand(request),
                        timeoutSeconds = (request.timeout?.inWholeSeconds?.coerceIn(1, 600)) ?: 600,
                        displayCommand = request.command,
                        onStdout = { line -> listener.onStdout(line) },
                        onStderr = { line -> listener.onStderr(line) },
                    )
                    val code = (raw["exit_code"] as? Int) ?: -1
                    listener.onExit(code)
                    exit.complete(code)
                } catch (e: Exception) {
                    Log.w("LocalProotBackend", "streaming exec failed", e)
                    listener.onExit(-1)
                    exit.complete(-1)
                }
            }
        }

        override fun cancel() {
            if (cancelled.compareAndSet(false, true)) {
                shell.cancelForeground()
                exit.complete(-1)
            }
        }

        override fun isCancelled(): Boolean = cancelled.get()

        override suspend fun writeInput(line: String) {
            shell.writeInput(line)
        }

        override suspend fun awaitExit(): Int =
            try {
                request.timeout?.let { withTimeout(it) { exit.await() } } ?: exit.await()
            } catch (_: Exception) {
                cancel()
                -1
            }
    }

    companion object {
        internal fun buildCommand(request: ExecRequest): String = buildString {
            if (request.workingDirectory != null) append("cd ${request.workingDirectory} && ")
            request.environment.forEach { (k, v) ->
                append("export ")
                append(k.replace(Regex("[^A-Za-z0-9_]"), "_"))
                append("='")
                append(v.replace("'", "'\\''"))
                append("' && ")
            }
            append(request.command)
            if (request.args.isNotEmpty()) {
                append(" ")
                append(request.args.joinToString(" ") { "'${it.replace("'", "'\\''")}'" })
            }
            if (request.stdin.isNotEmpty()) {
                append(" <<'__STDIN_EOF__'\n")
                append(request.stdin.joinToString("\n"))
                append("\n__STDIN_EOF__")
            }
        }
    }
}
