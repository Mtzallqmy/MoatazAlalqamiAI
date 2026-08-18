package com.inspiredandroid.kai.tools

import com.inspiredandroid.kai.sandbox.backend.ExecRequest
import com.inspiredandroid.kai.sandbox.backend.SandboxFile
import com.inspiredandroid.kai.sandbox.backend.SandboxProcess
import com.inspiredandroid.kai.sandbox.backend.ExposedPort
import com.inspiredandroid.kai.sandbox.backend.SandboxSnapshot
import kotlin.time.Duration

/**
 * Risk level of a tool invocation — drives the ApprovalEngine's auto-approve
 * matrix (Safe / Balanced / Autonomous modes).
 */
enum class ToolRiskLevel {
    READ_ONLY,
    WORKSPACE_WRITE,
    PACKAGE_INSTALL,
    NETWORK,
    PROCESS_CONTROL,
    GIT_WRITE,
    SECRET_ACCESS,
    DESTRUCTIVE,
}

/** Typed result of a tool invocation. */
sealed class ToolResult {
    data class Success(val data: Any? = null, val message: String = "ok") : ToolResult()
    data class Failure(val error: String, val retryable: Boolean = false) : ToolResult()
}

// ---------- Terminal ----------

data class TerminalExecArgs(
    val sandboxId: String,
    val command: String,
    val args: List<String> = emptyList(),
    val workingDirectory: String? = null,
    val timeout: Duration? = null,
)

data class TerminalExecStreamArgs(
    val sandboxId: String,
    val command: String,
    val workingDirectory: String? = null,
    val timeout: Duration? = null,
)

data class TerminalInputArgs(
    val sandboxId: String,
    val handleId: String,
    val line: String,
)

data class TerminalCancelArgs(
    val sandboxId: String,
    val handleId: String,
)

// ---------- Filesystem ----------

data class FsListArgs(
    val sandboxId: String,
    val path: String = "/",
    val recursive: Boolean = false,
)

data class FsReadArgs(
    val sandboxId: String,
    val path: String,
    val maxLength: Int = 64 * 1024,
)

data class FsWriteArgs(
    val sandboxId: String,
    val path: String,
    val content: ByteArray,
    val append: Boolean = false,
)

data class FsPatchArgs(
    val sandboxId: String,
    val path: String,
    val replacements: List<Pair<String, String>>,
)

data class FsMoveArgs(
    val sandboxId: String,
    val from: String,
    val to: String,
)

data class FsDeleteArgs(
    val sandboxId: String,
    val path: String,
    val recursive: Boolean = false,
)

data class FsSearchArgs(
    val sandboxId: String,
    val path: String = "/workspace",
    val pattern: String,
    val caseSensitive: Boolean = true,
    val maxMatches: Int = 50,
)

// ---------- Git ----------

data class GitCommandArgs(
    val sandboxId: String,
    val repository: String = "/workspace",
    val args: List<String>,
)

// ---------- Process ----------

data class ProcessListArgs(val sandboxId: String)

data class ProcessKillArgs(
    val sandboxId: String,
    val pid: Long,
    val signal: String = "SIGTERM",
)

// ---------- Ports ----------

data class PortOpenArgs(
    val sandboxId: String,
    val port: Int,
    val protocol: String = "tcp",
)

data class PortCloseArgs(
    val sandboxId: String,
    val port: Int,
)

// ---------- Sandbox ----------

data class SandboxInfoArgs(val sandboxId: String)

data class SandboxSnapshotArgs(
    val sandboxId: String,
    val label: String,
)

data class PreviewOpenArgs(
    val sandboxId: String,
    val port: Int,
)

/**
 * Rich result payload for the terminal tools (streaming keeps a live handle
 * id so follow-up input/cancel calls can target the same command).
 */
data class ExecToolResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val handleId: String? = null,
)

/** Rich result for process listing. */
data class ProcessListResult(val processes: List<SandboxProcess>)

/** Rich result for directory listing. */
data class FsListResult(val files: List<SandboxFile>)

/** Rich result for search. */
data class FsSearchResult(
    val matches: List<FsMatch>,
    val truncated: Boolean,
)

data class FsMatch(
    val path: String,
    val lineNumber: Int? = null,
    val line: String? = null,
)

/** Rich result for snapshot/preview. */
data class SandboxInfoResult(
    val sandboxId: String,
    val distro: String,
    val lifecycle: String,
    val diskUsageMB: Long,
)
