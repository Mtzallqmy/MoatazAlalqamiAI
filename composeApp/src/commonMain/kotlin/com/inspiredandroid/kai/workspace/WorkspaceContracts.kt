package com.inspiredandroid.kai.workspace

/** A command boundary shared by the workspace services and the local PRoot adapter. */
interface WorkspaceCommandRunner {
    suspend fun run(request: WorkspaceCommandRequest): WorkspaceCommandResult
}

data class WorkspaceCommandRequest(
    val command: String,
    val workingDirectory: String = WorkspacePaths.ROOT,
    val environment: Map<String, String> = emptyMap(),
    /** These values must be redacted by adapters and must never be included in activity logs. */
    val sensitiveEnvironmentKeys: Set<String> = emptySet(),
    val timeoutSeconds: Long = 30,
    val maxOutputChars: Int = 15_000,
) {
    override fun toString(): String =
        "WorkspaceCommandRequest(command=$command, workingDirectory=$workingDirectory, " +
            "environmentKeys=${environment.keys}, sensitiveEnvironmentKeys=$sensitiveEnvironmentKeys, " +
            "timeoutSeconds=$timeoutSeconds, maxOutputChars=$maxOutputChars)"
}

data class WorkspaceCommandResult(
    val exitCode: Int,
    val stdout: String = "",
    val stderr: String = "",
    val timedOut: Boolean = false,
) {
    val success: Boolean get() = exitCode == 0 && !timedOut
}

/** Last-resort output redaction in case a child process reflects a secret environment value. */
fun WorkspaceCommandResult.redactSensitiveOutput(request: WorkspaceCommandRequest): WorkspaceCommandResult {
    val secrets = request.sensitiveEnvironmentKeys.mapNotNull { request.environment[it] }.filter { it.isNotBlank() }
    if (secrets.isEmpty()) return this
    fun String.redacted() = secrets.fold(this) { text, secret -> text.replace(secret, "[REDACTED]") }
    return copy(stdout = stdout.redacted(), stderr = stderr.redacted())
}

object WorkspacePaths {
    const val ROOT = "/workspace"
    const val INTERNAL = "/workspace/.moataz"
    const val SNAPSHOTS = "$INTERNAL/snapshots"
    const val UPLOADS = "/root/uploads"

    fun project(name: String): String {
        require(WorkspacePathPolicy.validProjectName(name)) { "Invalid project name" }
        return "$ROOT/$name"
    }
}

object WorkspacePathPolicy {
    private val projectName = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")
    private val relativeSegment = Regex("[^/\\\\\\u0000-\\u001f]{1,255}")

    fun validProjectName(value: String): Boolean =
        projectName.matches(value) && value != "." && value != ".." && value != ".moataz"

    fun validRelativePath(value: String, allowRoot: Boolean = false): Boolean {
        if (value.isEmpty()) return allowRoot
        if (value.startsWith('/') || '\\' in value || '\u0000' in value) return false
        val segments = value.split('/')
        return segments.all { it != "." && it != ".." && relativeSegment.matches(it) } &&
            segments.firstOrNull() != ".moataz"
    }

    fun validGitPathspec(value: String): Boolean =
        validRelativePath(value) && value != ".git" && !value.startsWith(".git/")
}

internal fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
