package com.inspiredandroid.kai.workspace

import com.inspiredandroid.kai.sandbox.ProotExecutor

/** Keeps PRoot details out of the workspace domain services. */
class ProotWorkspaceCommandRunner(private val executor: ProotExecutor) : WorkspaceCommandRunner {
    override suspend fun run(request: WorkspaceCommandRequest): WorkspaceCommandResult {
        val raw = executor.execute(
            command = request.command,
            timeoutSeconds = request.timeoutSeconds,
            workingDir = request.workingDirectory,
            extraEnv = request.environment,
            maxOutputChars = request.maxOutputChars,
        )
        return WorkspaceCommandResult(
            exitCode = (raw["exit_code"] as? Number)?.toInt() ?: if (raw["success"] == true) 0 else -1,
            stdout = raw["stdout"]?.toString().orEmpty(),
            stderr = raw["stderr"]?.toString().orEmpty().ifBlank { raw["error"]?.toString().orEmpty() },
            timedOut = raw["timed_out"] == true,
        ).redactSensitiveOutput(request)
    }
}
