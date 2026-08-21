package com.inspiredandroid.kai.workspace

private const val MAX_ARCHIVE_FILES = 20_000
private const val MAX_ARCHIVE_BYTES = 2L * 1024 * 1024 * 1024
private const val MAX_ARCHIVE_SOURCE_BYTES = 512L * 1024 * 1024

enum class ArchiveEntryType { FILE, DIRECTORY, SYMLINK, HARDLINK, DEVICE, FIFO, OTHER }

object WorkspaceSourcePolicy {
    private val branchName = Regex("[A-Za-z0-9][A-Za-z0-9._/-]{0,127}")
    private val githubUrl = Regex("https://github\\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+(?:\\.git)?/?")
    private val archiveExtensions = listOf(".zip", ".tar", ".tgz", ".tar.gz", ".tar.xz")

    fun validBranch(value: String): Boolean = branchName.matches(value) && ".." !in value
    fun validGithubUrl(value: String): Boolean = githubUrl.matches(value)
    fun validUploadedArchive(value: String): Boolean {
        val lower = value.lowercase()
        return value.startsWith("${WorkspacePaths.UPLOADS}/") && ".." !in value && !value.endsWith('/') &&
            archiveExtensions.any(lower::endsWith)
    }
}

data class ArchiveEntry(val name: String, val size: Long, val type: ArchiveEntryType)

sealed interface ArchiveValidation {
    data class Valid(val files: Int, val expandedBytes: Long) : ArchiveValidation
    data class Invalid(val reason: String) : ArchiveValidation
}

/** Pure mirror of the extractor policy, usable before extraction and in contract tests. */
object WorkspaceArchivePolicy {
    fun validate(entries: List<ArchiveEntry>): ArchiveValidation {
        if (entries.size > MAX_ARCHIVE_FILES) return ArchiveValidation.Invalid("archive file limit exceeded")
        var total = 0L
        for (entry in entries) {
            if (!safeEntryName(entry.name)) return ArchiveValidation.Invalid("unsafe archive path: ${entry.name}")
            if (entry.size < 0 || entry.size > MAX_ARCHIVE_BYTES - total) {
                return ArchiveValidation.Invalid("archive expanded size limit exceeded")
            }
            if (entry.type !in setOf(ArchiveEntryType.FILE, ArchiveEntryType.DIRECTORY)) {
                return ArchiveValidation.Invalid("archive links and special files are not allowed")
            }
            total += entry.size
        }
        return ArchiveValidation.Valid(entries.size, total)
    }

    fun safeEntryName(name: String): Boolean {
        if (name.isBlank() || '\u0000' in name || '\\' in name || name.startsWith('/')) return false
        val parts = name.split('/').filter { it.isNotEmpty() }
        return parts.isNotEmpty() && parts.none { it == "." || it == ".." }
    }
}

sealed interface WorkspaceImportSource {
    data class GitHub(
        val httpsUrl: String,
        val branch: String? = null,
        /** Supplied only for private repositories and passed through a secret environment value. */
        val accessToken: String? = null,
    ) : WorkspaceImportSource {
        override fun toString(): String =
            "GitHub(httpsUrl=$httpsUrl, branch=$branch, accessToken=${if (accessToken == null) "null" else "[REDACTED]"})"
    }

    data class UploadedArchive(val path: String) : WorkspaceImportSource
}

data class WorkspaceImportRequest(val projectName: String, val source: WorkspaceImportSource)

data class WorkspaceImportResult(val projectPath: String, val commandResult: WorkspaceCommandResult)

class WorkspaceImportService(private val runner: WorkspaceCommandRunner) {
    suspend fun import(request: WorkspaceImportRequest): WorkspaceImportResult {
        require(WorkspacePathPolicy.validProjectName(request.projectName)) { "Invalid project name" }
        val target = WorkspacePaths.project(request.projectName)
        val absent = runner.run(
            WorkspaceCommandRequest("test ! -e ${shellQuote(target)} && test ! -L ${shellQuote(target)}", timeoutSeconds = 10),
        )
        require(absent.success) { "Project already exists or cannot be inspected: $target" }

        val result = when (val source = request.source) {
            is WorkspaceImportSource.GitHub -> importGitHub(source, target, request.projectName)
            is WorkspaceImportSource.UploadedArchive -> importArchive(source.path, target, request.projectName)
        }
        return WorkspaceImportResult(target, result)
    }

    private suspend fun importGitHub(
        source: WorkspaceImportSource.GitHub,
        target: String,
        projectName: String,
    ): WorkspaceCommandResult {
        require(WorkspaceSourcePolicy.validGithubUrl(source.httpsUrl)) { "Only HTTPS github.com repository URLs are allowed" }
        require(source.branch == null || WorkspaceSourcePolicy.validBranch(source.branch)) { "Invalid Git branch" }
        require(source.accessToken == null || source.accessToken.isNotBlank()) { "Blank GitHub token" }

        val stage = "${WorkspacePaths.INTERNAL}/imports/$projectName"
        val branch = source.branch?.let { "--branch ${shellQuote(it)} " }.orEmpty()
        val askPass = "${WorkspacePaths.INTERNAL}/askpass-$projectName.sh"
        val imports = WorkspacePaths.INTERNAL + "/imports"
        val internalGuard = "test ! -L ${shellQuote(WorkspacePaths.INTERNAL)} && " +
            "mkdir -p ${shellQuote(WorkspacePaths.INTERNAL)} && test ! -L ${shellQuote(imports)} && " +
            "mkdir -p ${shellQuote(imports)} && rm -rf -- ${shellQuote(stage)} && "
        val authSetup = if (source.accessToken != null) {
            internalGuard +
                "printf '%s\\n' '#!/bin/sh' 'case \"${'$'}1\" in *Username*) printf %s x-access-token;; *) printf %s \"${'$'}MOATAZ_GITHUB_TOKEN\";; esac' > ${shellQuote(askPass)} && " +
                "chmod 700 ${shellQuote(askPass)} && "
        } else internalGuard
        val cleanup = if (source.accessToken != null) "rm -f -- $askPass; rm -rf -- $stage" else "rm -rf -- $stage"
        val command = "${authSetup}trap ${shellQuote(cleanup)} EXIT; " +
            "git -c credential.helper= clone --depth 1 $branch-- ${shellQuote(source.httpsUrl)} ${shellQuote(stage)} && " +
            "mv -- ${shellQuote(stage)} ${shellQuote(target)}"
        val environment = if (source.accessToken != null) {
            mapOf(
                "GIT_ASKPASS" to askPass,
                "GIT_TERMINAL_PROMPT" to "0",
                "MOATAZ_GITHUB_TOKEN" to source.accessToken,
            )
        } else mapOf("GIT_TERMINAL_PROMPT" to "0")
        return runner.run(
            WorkspaceCommandRequest(
                command = command,
                environment = environment,
                sensitiveEnvironmentKeys = if (source.accessToken != null) setOf("MOATAZ_GITHUB_TOKEN") else emptySet(),
                timeoutSeconds = 300,
            ),
        )
    }

    private suspend fun importArchive(path: String, target: String, projectName: String): WorkspaceCommandResult {
        require(WorkspaceSourcePolicy.validUploadedArchive(path)) {
            "Archive must be an analyzed upload under ${WorkspacePaths.UPLOADS}"
        }
        val stage = "${WorkspacePaths.INTERNAL}/imports/$projectName"
        val imports = WorkspacePaths.INTERNAL + "/imports"
        val command = "test ! -L ${shellQuote(WorkspacePaths.INTERNAL)} && mkdir -p ${shellQuote(WorkspacePaths.INTERNAL)} && " +
            "test ! -L ${shellQuote(imports)} && mkdir -p ${shellQuote(imports)} && rm -rf -- ${shellQuote(stage)} && " +
            "trap ${shellQuote("rm -rf -- $stage")} EXIT; " +
            "mkdir -p ${shellQuote(stage)} && " +
            "python3 -c ${shellQuote(SAFE_ARCHIVE_SCRIPT.trimIndent())} ${shellQuote(path)} ${shellQuote(stage)} && " +
            "mv -- ${shellQuote(stage)} ${shellQuote(target)}"
        return runner.run(WorkspaceCommandRequest(command, timeoutSeconds = 300))
    }

    private companion object {
        val SAFE_ARCHIVE_SCRIPT = """
import os, pathlib, stat, sys, tarfile, zipfile
src, dst = sys.argv[1], pathlib.Path(sys.argv[2])
MAX_FILES, MAX_BYTES, MAX_SOURCE = $MAX_ARCHIVE_FILES, $MAX_ARCHIVE_BYTES, $MAX_ARCHIVE_SOURCE_BYTES
real_src = os.path.realpath(src)
if not real_src.startswith('/root/uploads/') or not os.path.isfile(real_src) or os.path.getsize(real_src) > MAX_SOURCE: raise SystemExit('archive source limit exceeded')
def safe_name(name):
    if not name or '\x00' in name or '\\' in name: return False
    p = pathlib.PurePosixPath(name)
    return not p.is_absolute() and all(part not in ('', '.', '..') for part in p.parts)
if zipfile.is_zipfile(src):
    with zipfile.ZipFile(src) as archive:
        members = archive.infolist()
        if len(members) > MAX_FILES or sum(m.file_size for m in members) > MAX_BYTES: raise SystemExit('archive limits exceeded')
        for member in members:
            mode = member.external_attr >> 16
            kind = stat.S_IFMT(mode)
            if not safe_name(member.filename) or member.flag_bits & 1 or kind not in (0, stat.S_IFREG, stat.S_IFDIR):
                raise SystemExit('unsafe archive member')
        archive.extractall(dst)
else:
    with tarfile.open(src, 'r:*') as archive:
        members = archive.getmembers()
        if len(members) > MAX_FILES or sum(member.size for member in members) > MAX_BYTES: raise SystemExit('archive limits exceeded')
        if any(not safe_name(member.name) or not (member.isfile() or member.isdir()) for member in members): raise SystemExit('unsafe archive member')
        archive.extractall(dst, filter='data')
"""
    }
}
