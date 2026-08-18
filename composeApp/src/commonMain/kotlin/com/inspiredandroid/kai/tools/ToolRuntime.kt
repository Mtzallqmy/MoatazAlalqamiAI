package com.inspiredandroid.kai.tools

import com.inspiredandroid.kai.sandbox.backend.CommandHandle
import com.inspiredandroid.kai.sandbox.backend.ExecRequest
import com.inspiredandroid.kai.sandbox.backend.ExecResult
import com.inspiredandroid.kai.sandbox.backend.ExecStreamListener
import com.inspiredandroid.kai.sandbox.backend.SandboxBackend
import com.inspiredandroid.kai.sandbox.backend.SandboxError
import com.inspiredandroid.kai.sandbox.backend.currentTimeMs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * An emitted tool-activity event — the orchestrator writes these into the
 * workspace Activity timeline so every step is observable.
 */
data class ToolActivityEvent(
    val tool: String,
    val success: Boolean,
    val detail: String,
    val epochMs: Long = currentTimeMs(),
)

typealias ToolActivityEmitter = (ToolActivityEvent) -> Unit

/**
 * The tool runtime: owns the 23 agentic tools (terminal / filesystem / git /
 * process / port / sandbox), each with typed args, typed results, per-tool
 * timeouts, risk classification, cancellation, unified error mapping and
 * activity-event emission.
 *
 * Backend-agnostic by construction — every tool takes a [SandboxBackend] at
 * dispatch time, so local PRoot and remote VM routes are identical from the
 * agent's perspective.
 */
class ToolRuntime(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val emitActivity: ToolActivityEmitter = {},
) {

    /** Live streaming handles keyed by sandbox id — input/cancel target these. */
    private val streamingHandles = mutableMapOf<String, MutableMap<String, CommandHandle>>()

    fun shutdown() {
        scope.cancel()
    }

    // ---------- Dispatch ----------

    suspend fun call(name: String, raw: Map<String, Any?>): ToolResult = try {
        when (name) {
            "terminal.exec" -> terminalExec(parseTerminalExec(raw))
            "terminal.exec_stream" -> terminalExecStream(parseTerminalExecStream(raw))
            "terminal.input" -> terminalInput(parseTerminalInput(raw))
            "terminal.cancel" -> terminalCancel(parseTerminalCancel(raw))
            "fs.list" -> fsList(parseFsList(raw))
            "fs.read" -> fsRead(parseFsRead(raw))
            "fs.write" -> fsWrite(parseFsWrite(raw))
            "fs.patch" -> fsPatch(parseFsPatch(raw))
            "fs.move" -> fsMove(parseFsMove(raw))
            "fs.delete" -> fsDelete(parseFsDelete(raw))
            "fs.search" -> fsSearch(parseFsSearch(raw))
            "git.status" -> gitExec(parseGitCommand(raw, listOf("status", "--porcelain", "-u")))
            "git.diff" -> gitExec(parseGitCommand(raw, listOf("diff", "--stat")))
            "git.log" -> gitExec(parseGitCommand(raw, listOf("log", "--oneline", "-20")))
            "git.branch" -> gitExec(parseGitCommand(raw, listOf("branch", "-a")))
            "git.checkout" -> gitExec(parseGitCommand(raw, listOf("checkout", raw["branch"] as? String ?: error("branch required"))))
            "git.commit" -> gitExec(parseGitCommand(raw, listOf("commit", "-m", raw["message"] as? String ?: error("message required"))))
            "process.list" -> processList(parseProcessList(raw))
            "process.kill" -> processKill(parseProcessKill(raw))
            "port.open" -> portOpen(parsePortOpen(raw))
            "port.close" -> portClose(parsePortClose(raw))
            "sandbox.info" -> sandboxInfo(parseSandboxInfo(raw))
            "sandbox.snapshot" -> sandboxSnapshot(parseSandboxSnapshot(raw))
            "preview.open" -> previewOpen(parsePreviewOpen(raw))
            else -> ToolResult.Failure("Unknown tool: $name")
        }
    } catch (ce: CancellationException) {
        throw ce
    } catch (e: SandboxError) {
        emitActivity(ToolActivityEvent(name, success = false, detail = e.message ?: e::class.simpleName ?: "error"))
        ToolResult.Failure(e.message ?: e::class.simpleName ?: "sandbox error", retryable = e is SandboxError.NetworkError || e is SandboxError.SandboxUnavailable)
    } catch (e: Exception) {
        emitActivity(ToolActivityEvent(name, success = false, detail = e.message ?: e::class.simpleName ?: "error"))
        ToolResult.Failure(e.message ?: e::class.simpleName ?: "tool error", retryable = false)
    }

    fun riskLevelFor(name: String): ToolRiskLevel = when (name) {
        "terminal.exec", "terminal.exec_stream" -> ToolRiskLevel.WORKSPACE_WRITE
        "terminal.input", "terminal.cancel" -> ToolRiskLevel.PROCESS_CONTROL
        "fs.list", "fs.read", "fs.search", "git.status", "git.diff", "git.log", "git.branch", "process.list", "sandbox.info" -> ToolRiskLevel.READ_ONLY
        "fs.write", "fs.patch", "fs.move", "git.checkout" -> ToolRiskLevel.WORKSPACE_WRITE
        "git.commit" -> ToolRiskLevel.GIT_WRITE
        "fs.delete" -> ToolRiskLevel.DESTRUCTIVE
        "process.kill", "port.open", "port.close", "preview.open" -> ToolRiskLevel.NETWORK
        "sandbox.snapshot" -> ToolRiskLevel.READ_ONLY
        else -> ToolRiskLevel.WORKSPACE_WRITE
    }

    // ---------- Terminal ----------

    suspend fun terminalExec(args: TerminalExecArgs): ToolResult {
        val backend = backendFor(args.sandboxId)
        val result = withTimeout(args.timeout ?: DEFAULT_EXEC_TIMEOUT) {
            backend.exec(
                args.sandboxId,
                ExecRequest(
                    command = args.command,
                    args = args.args,
                    workingDirectory = args.workingDirectory,
                    timeout = args.timeout ?: DEFAULT_EXEC_TIMEOUT,
                ),
            )
        }
        emitActivity(ToolActivityEvent("terminal.exec", result.succeeded, args.command))
        return ToolResult.Success(ExecToolResult(result.exitCode, result.stdout, result.stderr))
    }

    suspend fun terminalExecStream(args: TerminalExecStreamArgs): ToolResult {
        val backend = backendFor(args.sandboxId)
        val handleId = "handle-${currentTimeMs()}"
        val listener = CollectingStreamListener()
        val handle = backend.execStreaming(
            args.sandboxId,
            ExecRequest(
                command = args.command,
                workingDirectory = args.workingDirectory,
                timeout = args.timeout ?: 300.seconds,
            ),
            listener,
        )
        streamingHandles.getOrPut(args.sandboxId) { mutableMapOf() }[handleId] = handle
        // Wait briefly so the caller can see early output, but return the handle
        // id immediately — the orchestrator awaits exit through await-exit or
        // watches the transcript.
        kotlinx.coroutines.delay(500)
        emitActivity(ToolActivityEvent("terminal.exec_stream", true, args.command))
        return ToolResult.Success(mapOf(
            "handleId" to handleId,
            "stdout" to listener.stdout.toString(),
            "stderr" to listener.stderr.toString(),
        ))
    }

    suspend fun terminalInput(args: TerminalInputArgs): ToolResult {
        val handle = streamingHandles[args.sandboxId]?.get(args.handleId)
            ?: return ToolResult.Failure("No active stream for handle ${args.handleId}")
        handle.writeInput(args.line)
        return ToolResult.Success()
    }

    suspend fun terminalCancel(args: TerminalCancelArgs): ToolResult {
        val handle = streamingHandles[args.sandboxId]?.remove(args.handleId)
            ?: return ToolResult.Failure("No active stream for handle ${args.handleId}")
        handle.cancel()
        emitActivity(ToolActivityEvent("terminal.cancel", true, args.handleId))
        return ToolResult.Success()
    }

    // ---------- Filesystem ----------

    suspend fun fsList(args: FsListArgs): ToolResult {
        val backend = backendFor(args.sandboxId)
        val files = backend.listFiles(args.sandboxId, args.path, args.recursive)
        emitActivity(ToolActivityEvent("fs.list", true, args.path))
        return ToolResult.Success(FsListResult(files))
    }

    suspend fun fsRead(args: FsReadArgs): ToolResult {
        val backend = backendFor(args.sandboxId)
        val bytes = backend.readFile(args.sandboxId, args.path, args.maxLength)
        val text = try {
            bytes.decodeToString()
        } catch (_: Exception) {
            null
        }
        emitActivity(ToolActivityEvent("fs.read", true, args.path))
        return ToolResult.Success(mapOf("bytes" to bytes, "text" to text, "truncated" to (bytes.size >= args.maxLength)))
    }

    suspend fun fsWrite(args: FsWriteArgs): ToolResult {
        val backend = backendFor(args.sandboxId)
        if (args.append) {
            val existing = try {
                backend.readFile(args.sandboxId, args.path)
            } catch (_: SandboxError.PermissionDenied) {
                byteArrayOf()
            } catch (_: SandboxError) {
                byteArrayOf()
            }
            backend.writeFile(args.sandboxId, args.path, existing + args.content)
        } else {
            backend.writeFile(args.sandboxId, args.path, args.content)
        }
        emitActivity(ToolActivityEvent("fs.write", true, args.path))
        return ToolResult.Success()
    }

    suspend fun fsPatch(args: FsPatchArgs): ToolResult {
        val backend = backendFor(args.sandboxId)
        val bytes = backend.readFile(args.sandboxId, args.path)
        var text = bytes.decodeToString()
        var applied = 0
        for ((old, new) in args.replacements) {
            if (old in text) {
                text = text.replace(old, new)
                applied++
            }
        }
        if (applied == 0) return ToolResult.Failure("No replacements matched in ${args.path}")
        backend.writeFile(args.sandboxId, args.path, text.encodeToByteArray())
        emitActivity(ToolActivityEvent("fs.patch", true, "${args.path}: $applied replacement(s)"))
        return ToolResult.Success(mapOf("applied" to applied))
    }

    suspend fun fsMove(args: FsMoveArgs): ToolResult {
        backendFor(args.sandboxId).moveFile(args.sandboxId, args.from, args.to)
        emitActivity(ToolActivityEvent("fs.move", true, "${args.from} -> ${args.to}"))
        return ToolResult.Success()
    }

    suspend fun fsDelete(args: FsDeleteArgs): ToolResult {
        backendFor(args.sandboxId).deleteFile(args.sandboxId, args.path)
        emitActivity(ToolActivityEvent("fs.delete", true, args.path))
        return ToolResult.Success()
    }

    suspend fun fsSearch(args: FsSearchArgs): ToolResult {
        val backend = backendFor(args.sandboxId)
        val regex = try {
            Regex(args.pattern, if (args.caseSensitive) setOf() else setOf(RegexOption.IGNORE_CASE))
        } catch (_: Exception) {
            Regex(Regex.escape(args.pattern))
        }
        val matches = mutableListOf<FsMatch>()
        // Walk the directory tree through listFiles (recursive), then grep text files.
        val files = backend.listFiles(args.sandboxId, args.path, recursive = false)
        for (file in files) {
            if (matches.size >= args.maxMatches) break
            if (file.isDirectory) {
                val nested = try {
                    backend.listFiles(args.sandboxId, file.path, recursive = true)
                } catch (_: SandboxError) {
                    continue
                }
                for (entry in nested) {
                    if (matches.size >= args.maxMatches) break
                    if (!entry.isDirectory) grepFile(backend, args.sandboxId, entry, regex, args.maxMatches - matches.size, matches)
                }
            } else {
                grepFile(backend, args.sandboxId, file, regex, args.maxMatches - matches.size, matches)
            }
        }
        emitActivity(ToolActivityEvent("fs.search", true, "${args.path}: ${matches.size} match(es)"))
        return ToolResult.Success(FsSearchResult(matches, matches.size >= args.maxMatches))
    }

    private suspend fun grepFile(
        backend: SandboxBackend,
        sandboxId: String,
        file: com.inspiredandroid.kai.sandbox.backend.SandboxFile,
        regex: Regex,
        remaining: Int,
        matches: MutableList<FsMatch>,
    ) {
        if (file.sizeBytes <= 0 || file.sizeBytes > 2 * 1024 * 1024) return
        val text = try {
            backend.readFile(sandboxId, file.path).decodeToString()
        } catch (_: SandboxError) {
            return
        }
        for ((index, line) in text.lines().withIndex()) {
            if (matches.size >= remaining) return
            if (regex.containsMatchIn(line)) {
                matches += FsMatch(file.path, index + 1, line.trim())
            }
        }
    }

    // ---------- Git ----------

    suspend fun gitExec(args: GitCommandArgs): ToolResult {
        val backend = backendFor(args.sandboxId)
        val result = withTimeout(DEFAULT_EXEC_TIMEOUT) {
            backend.exec(
                args.sandboxId,
                ExecRequest(
                    command = "git ${args.args.joinToString(" ")}",
                    workingDirectory = args.repository,
                    timeout = DEFAULT_EXEC_TIMEOUT,
                ),
            )
        }
        val toolName = "git.${args.args.firstOrNull() ?: "unknown"}"
        emitActivity(ToolActivityEvent(toolName, result.succeeded, args.args.joinToString(" ")))
        return if (result.succeeded) {
            ToolResult.Success(mapOf("stdout" to result.stdout, "stderr" to result.stderr))
        } else {
            ToolResult.Failure("git ${args.args.firstOrNull()}: ${result.stderr.ifEmpty { result.stdout }}", retryable = false)
        }
    }

    // ---------- Process ----------

    suspend fun processList(args: ProcessListArgs): ToolResult {
        val processes = backendFor(args.sandboxId).listProcesses(args.sandboxId)
        emitActivity(ToolActivityEvent("process.list", true, "${processes.size} process(es)"))
        return ToolResult.Success(ProcessListResult(processes))
    }

    suspend fun processKill(args: ProcessKillArgs): ToolResult {
        backendFor(args.sandboxId).killProcess(args.sandboxId, args.pid, args.signal)
        emitActivity(ToolActivityEvent("process.kill", true, "pid ${args.pid}"))
        return ToolResult.Success()
    }

    // ---------- Ports ----------

    suspend fun portOpen(args: PortOpenArgs): ToolResult {
        val exposed = backendFor(args.sandboxId).openPort(args.sandboxId, args.port, args.protocol)
        emitActivity(ToolActivityEvent("port.open", true, "port ${args.port} -> ${exposed.url}"))
        return ToolResult.Success(exposed)
    }

    suspend fun portClose(args: PortCloseArgs): ToolResult {
        backendFor(args.sandboxId).closePort(args.sandboxId, args.port)
        emitActivity(ToolActivityEvent("port.close", true, "port ${args.port}"))
        return ToolResult.Success()
    }

    // ---------- Sandbox ----------

    suspend fun sandboxInfo(args: SandboxInfoArgs): ToolResult {
        val backend = backendFor(args.sandboxId)
        val state = backend.state.value
        return ToolResult.Success(SandboxInfoResult(
            sandboxId = args.sandboxId,
            distro = state.distro.id,
            lifecycle = state.lifecycle.name,
            diskUsageMB = state.diskUsageMB,
        ))
    }

    suspend fun sandboxSnapshot(args: SandboxSnapshotArgs): ToolResult {
        val snapshot = backendFor(args.sandboxId).snapshot(args.sandboxId, args.label)
        emitActivity(ToolActivityEvent("sandbox.snapshot", true, args.label))
        return ToolResult.Success(snapshot)
    }

    suspend fun previewOpen(args: PreviewOpenArgs): ToolResult {
        val exposed = backendFor(args.sandboxId).openPort(args.sandboxId, args.port)
        emitActivity(ToolActivityEvent("preview.open", true, "port ${args.port} -> ${exposed.url}"))
        return ToolResult.Success(exposed)
    }

    // ---------- Helpers ----------

    /** Override point for tests / wiring; defaults to the registered backend registry. */
    var backendProvider: (sandboxId: String) -> SandboxBackend = { _ ->
        throw SandboxError.ConfigurationError("backend", "No backend provider configured")
    }

    private fun backendFor(sandboxId: String): SandboxBackend = backendProvider(sandboxId)

    companion object {
        val DEFAULT_EXEC_TIMEOUT: Duration = 120.seconds
    }

    private class CollectingStreamListener : ExecStreamListener {
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        override fun onStdout(line: String) {
            stdout.appendLine(line)
        }
        override fun onStderr(line: String) {
            stderr.appendLine(line)
        }
    }
}

// ---------- Typed args parsing (from LLM JSON-ish maps) ----------

private fun parseTerminalExec(raw: Map<String, Any?>): TerminalExecArgs =
    TerminalExecArgs(
        sandboxId = raw["sandbox_id"] as? String ?: raw["sandboxId"] as? String ?: error("sandboxId required"),
        command = raw["command"] as? String ?: error("command required"),
        args = (raw["args"] as? List<*>)?.filterIsInstance<String>().orEmpty(),
        workingDirectory = raw["working_directory"] as? String ?: raw["workingDirectory"] as? String,
        timeout = (raw["timeout_seconds"] as? Number)?.toLong()?.let { it.seconds } ?: (raw["timeout"] as? Number)?.toLong()?.let { it.seconds },
    )

private fun parseTerminalExecStream(raw: Map<String, Any?>): TerminalExecStreamArgs =
    TerminalExecStreamArgs(
        sandboxId = raw["sandbox_id"] as? String ?: raw["sandboxId"] as? String ?: error("sandboxId required"),
        command = raw["command"] as? String ?: error("command required"),
        workingDirectory = raw["working_directory"] as? String ?: raw["workingDirectory"] as? String,
        timeout = (raw["timeout_seconds"] as? Number)?.toLong()?.let { it.seconds },
    )

private fun parseTerminalInput(raw: Map<String, Any?>): TerminalInputArgs =
    TerminalInputArgs(
        sandboxId = raw["sandbox_id"] as? String ?: raw["sandboxId"] as? String ?: error("sandboxId required"),
        handleId = raw["handle_id"] as? String ?: raw["handleId"] as? String ?: error("handleId required"),
        line = raw["line"] as? String ?: raw["input"] as? String ?: error("line required"),
    )

private fun parseTerminalCancel(raw: Map<String, Any?>): TerminalCancelArgs =
    TerminalCancelArgs(
        sandboxId = raw["sandbox_id"] as? String ?: raw["sandboxId"] as? String ?: error("sandboxId required"),
        handleId = raw["handle_id"] as? String ?: raw["handleId"] as? String ?: error("handleId required"),
    )

private fun parseFsList(raw: Map<String, Any?>): FsListArgs =
    FsListArgs(
        sandboxId = raw["sandbox_id"] as? String ?: raw["sandboxId"] as? String ?: error("sandboxId required"),
        path = raw["path"] as? String ?: "/",
        recursive = raw["recursive"] as? Boolean ?: false,
    )

private fun parseFsRead(raw: Map<String, Any?>): FsReadArgs =
    FsReadArgs(
        sandboxId = raw["sandbox_id"] as? String ?: raw["sandboxId"] as? String ?: error("sandboxId required"),
        path = raw["path"] as? String ?: error("path required"),
        maxLength = (raw["max_length"] as? Number)?.toInt() ?: (raw["maxLength"] as? Number)?.toInt() ?: 64 * 1024,
    )

private fun parseFsWrite(raw: Map<String, Any?>): FsWriteArgs =
    FsWriteArgs(
        sandboxId = raw["sandbox_id"] as? String ?: raw["sandboxId"] as? String ?: error("sandboxId required"),
        path = raw["path"] as? String ?: error("path required"),
        content = (raw["content"] as? String)?.encodeToByteArray() ?: error("content required"),
        append = raw["append"] as? Boolean ?: false,
    )

private fun parseFsPatch(raw: Map<String, Any?>): FsPatchArgs {
    val replacements = (raw["replacements"] as? List<*>)
        ?.mapNotNull { entry ->
            val map = entry as? Map<*, *> ?: return@mapNotNull null
            (map["old"] as? String ?: map["from"] as? String ?: return@mapNotNull null) to
                (map["new"] as? String ?: map["to"] as? String ?: return@mapNotNull null)
        }.orEmpty()
    return FsPatchArgs(
        sandboxId = raw["sandbox_id"] as? String ?: raw["sandboxId"] as? String ?: error("sandboxId required"),
        path = raw["path"] as? String ?: error("path required"),
        replacements = replacements,
    )
}

private fun parseFsMove(raw: Map<String, Any?>): FsMoveArgs =
    FsMoveArgs(
        sandboxId = raw["sandbox_id"] as? String ?: raw["sandboxId"] as? String ?: error("sandboxId required"),
        from = raw["from"] as? String ?: error("from required"),
        to = raw["to"] as? String ?: error("to required"),
    )

private fun parseFsDelete(raw: Map<String, Any?>): FsDeleteArgs =
    FsDeleteArgs(
        sandboxId = raw["sandbox_id"] as? String ?: raw["sandboxId"] as? String ?: error("sandboxId required"),
        path = raw["path"] as? String ?: error("path required"),
        recursive = raw["recursive"] as? Boolean ?: false,
    )

private fun parseFsSearch(raw: Map<String, Any?>): FsSearchArgs =
    FsSearchArgs(
        sandboxId = raw["sandbox_id"] as? String ?: raw["sandboxId"] as? String ?: error("sandboxId required"),
        path = raw["path"] as? String ?: "/workspace",
        pattern = raw["pattern"] as? String ?: raw["query"] as? String ?: error("pattern required"),
        caseSensitive = raw["case_sensitive"] as? Boolean ?: true,
        maxMatches = (raw["max_matches"] as? Number)?.toInt() ?: 50,
    )

private fun parseGitCommand(raw: Map<String, Any?>, defaultArgs: List<String>): GitCommandArgs =
    GitCommandArgs(
        sandboxId = raw["sandbox_id"] as? String ?: raw["sandboxId"] as? String ?: error("sandboxId required"),
        repository = raw["repository"] as? String ?: raw["repo"] as? String ?: "/workspace",
        args = (raw["args"] as? List<*>)?.filterIsInstance<String>() ?: defaultArgs,
    )

private fun parseProcessList(raw: Map<String, Any?>): ProcessListArgs =
    ProcessListArgs(raw["sandbox_id"] as? String ?: raw["sandboxId"] as? String ?: error("sandboxId required"))

private fun parseProcessKill(raw: Map<String, Any?>): ProcessKillArgs =
    ProcessKillArgs(
        sandboxId = raw["sandbox_id"] as? String ?: raw["sandboxId"] as? String ?: error("sandboxId required"),
        pid = (raw["pid"] as? Number)?.toLong() ?: error("pid required"),
        signal = raw["signal"] as? String ?: "SIGTERM",
    )

private fun parsePortOpen(raw: Map<String, Any?>): PortOpenArgs =
    PortOpenArgs(
        sandboxId = raw["sandbox_id"] as? String ?: raw["sandboxId"] as? String ?: error("sandboxId required"),
        port = (raw["port"] as? Number)?.toInt() ?: error("port required"),
        protocol = raw["protocol"] as? String ?: "tcp",
    )

private fun parsePortClose(raw: Map<String, Any?>): PortCloseArgs =
    PortCloseArgs(
        sandboxId = raw["sandbox_id"] as? String ?: raw["sandboxId"] as? String ?: error("sandboxId required"),
        port = (raw["port"] as? Number)?.toInt() ?: error("port required"),
    )

private fun parseSandboxInfo(raw: Map<String, Any?>): SandboxInfoArgs =
    SandboxInfoArgs(raw["sandbox_id"] as? String ?: raw["sandboxId"] as? String ?: error("sandboxId required"))

private fun parseSandboxSnapshot(raw: Map<String, Any?>): SandboxSnapshotArgs =
    SandboxSnapshotArgs(
        sandboxId = raw["sandbox_id"] as? String ?: raw["sandboxId"] as? String ?: error("sandboxId required"),
        label = raw["label"] as? String ?: error("label required"),
    )

private fun parsePreviewOpen(raw: Map<String, Any?>): PreviewOpenArgs =
    PreviewOpenArgs(
        sandboxId = raw["sandbox_id"] as? String ?: raw["sandboxId"] as? String ?: error("sandboxId required"),
        port = (raw["port"] as? Number)?.toInt() ?: error("port required"),
    )
