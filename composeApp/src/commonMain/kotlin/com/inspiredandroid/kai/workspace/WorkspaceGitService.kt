package com.inspiredandroid.kai.workspace

enum class WorkspaceGitOperation { STATUS, DIFF, BRANCH, STAGE, UNSTAGE, COMMIT }

data class WorkspaceGitResult(val operation: WorkspaceGitOperation, val result: WorkspaceCommandResult)

/** Local Git operations only. Push, remote mutation and deployment deliberately have no API here. */
class WorkspaceGitService(private val runner: WorkspaceCommandRunner) {
    suspend fun status(projectName: String) = run(projectName, WorkspaceGitOperation.STATUS, "git status --porcelain=v1 -b")

    suspend fun diff(projectName: String, staged: Boolean = false): WorkspaceGitResult = run(
        projectName,
        WorkspaceGitOperation.DIFF,
        "git diff ${if (staged) "--cached " else ""}--no-ext-diff --no-color --",
    )

    suspend fun branches(projectName: String) = run(
        projectName,
        WorkspaceGitOperation.BRANCH,
        "git branch --no-color --format='%(HEAD)\\t%(refname:short)'",
    )

    suspend fun stage(projectName: String, paths: List<String>): WorkspaceGitResult {
        requirePathspecs(paths)
        return run(projectName, WorkspaceGitOperation.STAGE, "git add -- ${paths.joinToString(" ", transform = ::shellQuote)}")
    }

    suspend fun unstage(projectName: String, paths: List<String>): WorkspaceGitResult {
        requirePathspecs(paths)
        return run(projectName, WorkspaceGitOperation.UNSTAGE, "git restore --staged -- ${paths.joinToString(" ", transform = ::shellQuote)}")
    }

    suspend fun commit(projectName: String, message: String): WorkspaceGitResult {
        require(message.isNotBlank() && message.length <= 500 && '\u0000' !in message) { "Invalid commit message" }
        return run(projectName, WorkspaceGitOperation.COMMIT, "git commit -m ${shellQuote(message)}")
    }

    private suspend fun run(projectName: String, operation: WorkspaceGitOperation, command: String): WorkspaceGitResult {
        require(WorkspacePathPolicy.validProjectName(projectName)) { "Invalid project name" }
        val project = WorkspacePaths.project(projectName)
        val guarded = "test -d ${shellQuote(project)} && test ! -L ${shellQuote(project)} && $command"
        val result = runner.run(WorkspaceCommandRequest(guarded, project, timeoutSeconds = 120, maxOutputChars = 1_000_000))
        return WorkspaceGitResult(operation, result)
    }

    private fun requirePathspecs(paths: List<String>) {
        require(paths.isNotEmpty() && paths.size <= 500) { "No Git paths supplied" }
        require(paths.all(WorkspacePathPolicy::validGitPathspec)) { "Invalid Git path" }
    }
}
