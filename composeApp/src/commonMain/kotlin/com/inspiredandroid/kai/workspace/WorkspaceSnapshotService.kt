package com.inspiredandroid.kai.workspace

data class WorkspaceSnapshot(val id: String, val projectName: String, val path: String)
data class WorkspaceDiff(val hasChanges: Boolean, val text: String, val commandResult: WorkspaceCommandResult)

fun interface WorkspaceSnapshotIdGenerator { fun nextId(): String }

class WorkspaceSnapshotService(
    private val runner: WorkspaceCommandRunner,
    private val ids: WorkspaceSnapshotIdGenerator,
) {
    suspend fun create(projectName: String): WorkspaceSnapshot {
        require(WorkspacePathPolicy.validProjectName(projectName)) { "Invalid project name" }
        val id = ids.nextId()
        require(validSnapshotId(id)) { "Invalid snapshot id" }
        val project = WorkspacePaths.project(projectName)
        val target = snapshotPath(projectName, id)
        val result = runner.run(
            WorkspaceCommandRequest(
                command = internalGuard(projectName) + "test ! -e ${shellQuote(target)} && test ! -L ${shellQuote(target)} && " +
                    "size=${'$'}(du -sk ${shellQuote(project)} | awk '{print ${'$'}1}'); " +
                    "test \"${'$'}size\" -le 2097152 || { echo 'snapshot size limit exceeded' >&2; exit 73; }; " +
                    "mkdir -p ${shellQuote(target)} && rsync -a --safe-links --delete ${shellQuote("$project/")} ${shellQuote("$target/")}",
                timeoutSeconds = 300,
            ),
        )
        check(result.success) { result.stderr.ifBlank { "Snapshot failed" } }
        return WorkspaceSnapshot(id, projectName, target)
    }

    suspend fun diff(snapshot: WorkspaceSnapshot): WorkspaceDiff {
        validate(snapshot)
        val result = runner.run(
            WorkspaceCommandRequest(
                command = internalGuard(snapshot.projectName) + "test -d ${shellQuote(snapshot.path)} && test ! -L ${shellQuote(snapshot.path)} && " +
                    "diff -ruN --exclude=.git --exclude=.moataz -- ${shellQuote(snapshot.path)} ${shellQuote(WorkspacePaths.project(snapshot.projectName))}",
                timeoutSeconds = 120,
                maxOutputChars = 1_000_000,
            ),
        )
        check(!result.timedOut && result.exitCode in 0..1) { result.stderr.ifBlank { "Snapshot diff failed" } }
        return WorkspaceDiff(result.exitCode == 1, result.stdout, result)
    }

    suspend fun undo(snapshot: WorkspaceSnapshot): WorkspaceCommandResult {
        validate(snapshot)
        val project = WorkspacePaths.project(snapshot.projectName)
        return runner.run(
            WorkspaceCommandRequest(
                command = internalGuard(snapshot.projectName) + "test -d ${shellQuote(snapshot.path)} && test ! -L ${shellQuote(snapshot.path)} && " +
                    "rsync -a --safe-links --delete ${shellQuote(snapshot.path + "/")} ${shellQuote("$project/")}",
                timeoutSeconds = 300,
            ),
        )
    }

    private fun validate(snapshot: WorkspaceSnapshot) {
        require(WorkspacePathPolicy.validProjectName(snapshot.projectName) && validSnapshotId(snapshot.id)) { "Invalid snapshot" }
        require(snapshot.path == snapshotPath(snapshot.projectName, snapshot.id)) { "Snapshot path mismatch" }
    }

    private fun snapshotPath(projectName: String, id: String) = "${WorkspacePaths.SNAPSHOTS}/$projectName/$id"
    private fun validSnapshotId(id: String) = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}").matches(id)
    private fun internalGuard(projectName: String): String {
        val project = WorkspacePaths.project(projectName)
        val projectSnapshots = "${WorkspacePaths.SNAPSHOTS}/$projectName"
        return "test -d ${shellQuote(project)} && test ! -L ${shellQuote(project)} && " +
            "test ! -L ${shellQuote(WorkspacePaths.INTERNAL)} && mkdir -p ${shellQuote(WorkspacePaths.INTERNAL)} && " +
            "test ! -L ${shellQuote(WorkspacePaths.SNAPSHOTS)} && mkdir -p ${shellQuote(WorkspacePaths.SNAPSHOTS)} && " +
            "test ! -L ${shellQuote(projectSnapshots)} && mkdir -p ${shellQuote(projectSnapshots)} && "
    }
}
