package com.inspiredandroid.kai.workspace

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WorkspaceServicesTest {
    @Test
    fun `workspace paths cannot escape project or enter internal state`() {
        assertTrue(WorkspacePathPolicy.validRelativePath("src/main/App.kt"))
        assertFalse(WorkspacePathPolicy.validRelativePath("../secret"))
        assertFalse(WorkspacePathPolicy.validRelativePath("src\\secret"))
        assertFalse(WorkspacePathPolicy.validRelativePath(".moataz/snapshots"))
        assertFalse(WorkspacePathPolicy.validGitPathspec(".git/config"))
        assertFailsWith<IllegalArgumentException> { WorkspacePaths.project("../outside") }
    }

    @Test
    fun `command result redacts every marked secret`() {
        val request = WorkspaceCommandRequest(
            command = "git clone",
            environment = mapOf("TOKEN" to "secret-123", "VISIBLE" to "ok"),
            sensitiveEnvironmentKeys = setOf("TOKEN"),
        )
        val result = WorkspaceCommandResult(1, "echo secret-123", "failed secret-123").redactSensitiveOutput(request)
        assertFalse("secret-123" in result.stdout)
        assertFalse("secret-123" in result.stderr)
        assertTrue("[REDACTED]" in result.stderr)
    }

    @Test
    fun `archive contract rejects traversal links special files and expansion bombs`() {
        assertIs<ArchiveValidation.Valid>(
            WorkspaceArchivePolicy.validate(
                listOf(
                    ArchiveEntry("project/", 0, ArchiveEntryType.DIRECTORY),
                    ArchiveEntry("project/src/App.kt", 42, ArchiveEntryType.FILE),
                ),
            ),
        )
        assertIs<ArchiveValidation.Invalid>(
            WorkspaceArchivePolicy.validate(listOf(ArchiveEntry("../../escape", 1, ArchiveEntryType.FILE))),
        )
        assertIs<ArchiveValidation.Invalid>(
            WorkspaceArchivePolicy.validate(listOf(ArchiveEntry("safe/link", 0, ArchiveEntryType.SYMLINK))),
        )
        assertIs<ArchiveValidation.Invalid>(
            WorkspaceArchivePolicy.validate(listOf(ArchiveEntry("huge.bin", 2L * 1024 * 1024 * 1024 + 1, ArchiveEntryType.FILE))),
        )
    }

    @Test
    fun `github import is staged under workspace and private token never enters command`() = runTest {
        val runner = RecordingRunner(
            WorkspaceCommandResult(0),
            WorkspaceCommandResult(0, stdout = "cloned"),
        )
        val token = "github_pat_super_secret"
        val result = WorkspaceImportService(runner).import(
            WorkspaceImportRequest(
                "private-project",
                WorkspaceImportSource.GitHub("https://github.com/acme/private.git", "main", token),
            ),
        )

        assertEquals("/workspace/private-project", result.projectPath)
        val clone = runner.requests.last()
        assertTrue("git -c credential.helper= clone" in clone.command)
        assertTrue("/workspace/.moataz/imports/private-project" in clone.command)
        assertFalse(token in clone.command)
        assertEquals(token, clone.environment["MOATAZ_GITHUB_TOKEN"])
        assertTrue("MOATAZ_GITHUB_TOKEN" in clone.sensitiveEnvironmentKeys)
        assertTrue("GIT_TERMINAL_PROMPT" in clone.environment)
        assertFalse(token in clone.toString())
        assertFalse(token in WorkspaceImportSource.GitHub("https://github.com/acme/private", accessToken = token).toString())
    }

    @Test
    fun `archive import is atomic and invokes bounded safe extractor`() = runTest {
        val runner = RecordingRunner(WorkspaceCommandResult(0), WorkspaceCommandResult(0))
        WorkspaceImportService(runner).import(
            WorkspaceImportRequest("sample", WorkspaceImportSource.UploadedArchive("/root/uploads/sample.tar.xz")),
        )
        val request = runner.requests.last()
        assertTrue("MAX_FILES" in request.command)
        assertTrue("archive.extractall" in request.command)
        assertTrue("/workspace/.moataz/imports/sample" in request.command)
        assertTrue("mv --" in request.command)
        assertFalse("rm -rf '/workspace/sample'" in request.command)
    }

    @Test
    fun `existing project aborts before import`() = runTest {
        val runner = RecordingRunner(WorkspaceCommandResult(1, stderr = "exists"))
        assertFailsWith<IllegalArgumentException> {
            WorkspaceImportService(runner).import(
                WorkspaceImportRequest("sample", WorkspaceImportSource.GitHub("https://github.com/acme/sample")),
            )
        }
        assertEquals(1, runner.requests.size)
    }

    @Test
    fun `file read write explorer and rg stay inside selected project`() = runTest {
        val runner = RecordingRunner(
            WorkspaceCommandResult(0, "d\t0\tsrc\nf\t12\tsrc/App.kt\n"),
            WorkspaceCommandResult(0, "hello"),
            WorkspaceCommandResult(0),
            WorkspaceCommandResult(0, "{\"type\":\"match\"}"),
        )
        val files = WorkspaceFileService(runner)
        assertEquals(2, files.list("demo").size)
        assertEquals("hello", files.readText("demo", "src/App.kt"))
        assertTrue(files.writeText("demo", "src/App.kt", "new text").success)
        assertTrue(files.search("demo", "new text", "*.kt").success)
        assertTrue(runner.requests.all { it.workingDirectory == "/workspace/demo" })
        assertTrue("mktemp" in runner.requests[2].command)
        assertTrue("rg --json" in runner.requests[3].command)
        assertFailsWith<IllegalArgumentException> { files.readText("demo", "../secret") }
    }

    @Test
    fun `git service supports local review workflow but exposes no push command`() = runTest {
        val runner = RecordingRunner(*Array(6) { WorkspaceCommandResult(0) })
        val git = WorkspaceGitService(runner)
        git.status("demo")
        git.diff("demo")
        git.branches("demo")
        git.stage("demo", listOf("src/App.kt"))
        git.unstage("demo", listOf("src/App.kt"))
        git.commit("demo", "fix: quote user's input")

        assertTrue(runner.requests.any { "git add -- 'src/App.kt'" in it.command })
        assertTrue(runner.requests.any { "git restore --staged -- 'src/App.kt'" in it.command })
        assertTrue(runner.requests.none { Regex("(^|\\s)push(\\s|$)").containsMatchIn(it.command) })
        assertFailsWith<IllegalArgumentException> { git.stage("demo", listOf(".git/config")) }
    }

    @Test
    fun `project edit test diff and undo use evidence from the runner`() = runTest {
        val runner = RecordingRunner(
            WorkspaceCommandResult(0), // snapshot
            WorkspaceCommandResult(0), // write
            WorkspaceCommandResult(0, "gradle\npython\n"), // detect
            WorkspaceCommandResult(0, "BUILD SUCCESSFUL\n2 tests passed"), // test
            WorkspaceCommandResult(1, "-old\n+fixed\n"), // diff: one means files differ
            WorkspaceCommandResult(0), // undo
        )
        val snapshotService = WorkspaceSnapshotService(runner) { "checkpoint-1" }
        val snapshot = snapshotService.create("demo")
        assertTrue(WorkspaceFileService(runner).writeText("demo", "src/App.kt", "fixed").success)
        val testCommand = WorkspaceBuildService(runner).detect("demo").first { it.system == BuildSystem.GRADLE && it.kind == BuildCommand.Kind.TEST }
        val evidence = WorkspaceBuildService(runner).execute("demo", testCommand)
        val diff = snapshotService.diff(snapshot)
        val undo = snapshotService.undo(snapshot)

        assertTrue(evidence.success)
        assertTrue("2 tests passed" in evidence.stdout)
        assertTrue(diff.hasChanges)
        assertEquals(1, diff.commandResult.exitCode)
        assertTrue("+fixed" in diff.text)
        assertTrue(undo.success)
        assertTrue("rsync -a --safe-links --delete" in runner.requests.first().command)
        assertTrue("rsync -a --safe-links --delete" in runner.requests.last().command)
    }

    private class RecordingRunner(vararg results: WorkspaceCommandResult) : WorkspaceCommandRunner {
        private val results = ArrayDeque(results.toList())
        val requests = mutableListOf<WorkspaceCommandRequest>()

        override suspend fun run(request: WorkspaceCommandRequest): WorkspaceCommandResult {
            requests += request
            return results.removeFirstOrNull() ?: error("No fake command result for: ${request.command}")
        }
    }
}
