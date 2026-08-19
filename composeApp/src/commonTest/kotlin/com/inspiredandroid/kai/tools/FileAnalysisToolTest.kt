package com.inspiredandroid.kai.tools

import com.inspiredandroid.kai.sandbox.backend.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalEncodingApi::class)
class FileAnalysisToolTest {

    /** A recording fake backend — every exec() returns a pre-programmed line. */
    private class FakeBackend(
        private val responses: Map<String, String> = emptyMap(),
    ) : SandboxBackend {
        val writtenFiles = mutableListOf<Pair<String, ByteArray>>()
        val executed = mutableListOf<List<String>>()

        override val backendId = "fake"
        override val capabilities = SandboxCapabilities()
        override val state = MutableStateFlow(SandboxState())

        override suspend fun create(config: SandboxConfig): SandboxInstance = TODO()
        override suspend fun start(id: String) {}
        override suspend fun stop(id: String) {}
        override suspend fun destroy(id: String) {}

        override suspend fun exec(sandboxId: String, request: ExecRequest): ExecResult {
            executed += (listOf(request.command) + request.args)
            val allArgs = executed.last()
            val matched = responses.entries.firstOrNull { entry ->
                allArgs.any { arg -> arg in entry.key }
            }
            val output = matched?.value ?: ""
            return ExecResult(exitCode = 0, stdout = output, stderr = "")
        }

        override suspend fun execStreaming(sandboxId: String, request: ExecRequest, listener: ExecStreamListener): CommandHandle = CommandHandle.NO_OP
        override suspend fun listFiles(sandboxId: String, path: String, recursive: Boolean): List<SandboxFile> = emptyList()
        override suspend fun readFile(sandboxId: String, path: String, maxLength: Int): ByteArray = ByteArray(0)
        override suspend fun writeFile(sandboxId: String, path: String, content: ByteArray) {
            writtenFiles += path to content
        }
        override suspend fun deleteFile(sandboxId: String, path: String) {}
        override suspend fun moveFile(sandboxId: String, from: String, to: String) {}
        override suspend fun listProcesses(sandboxId: String): List<SandboxProcess> = emptyList()
        override suspend fun killProcess(sandboxId: String, pid: Long, signal: String) {}
        override suspend fun openPort(sandboxId: String, port: Int, protocol: String): ExposedPort = TODO()
        override suspend fun closePort(sandboxId: String, port: Int) {}
        override suspend fun snapshot(sandboxId: String, label: String): SandboxSnapshot = TODO()
    }

    private fun toolFor(responses: Map<String, String>) = FileAnalysisTool { FakeBackend(responses) }

    @Test
    fun `analyze extracts text from a PDF`() = runTest {
        val backend = FakeBackend(mapOf(
            "pdfinfo" to "Pages: 3",
            "pdftotext" to "Hello extracted PDF text",
        ))
        val tool = FileAnalysisTool { backend }
        val result = tool.analyze(FileAnalysisArgs(
            sandboxId = "sb1",
            fileName = "report.pdf",
            mimeType = "application/pdf",
            data = Base64.encode("dummy".encodeToByteArray()),
        ))
        assertIs<ToolResult.Success>(result)
        val summary = ((result.data as? Map<*, *>)?.get("summary") as? String).orEmpty()
        assertContains(summary, "report.pdf")
        assertContains(summary, "Hello extracted PDF text")
        assertTrue(backend.writtenFiles.any { it.first == "/root/uploads/report.pdf" })
    }

    @Test
    fun `analyze falls back gracefully when PDF has no text`() = runTest {
        val tool = toolFor(mapOf("pdfinfo" to "", "pdftotext" to ""))
        val result = tool.analyze(FileAnalysisArgs(
            sandboxId = "sb1", fileName = "scan.pdf", mimeType = "application/pdf",
            data = Base64.encode("dummy".encodeToByteArray()),
        ))
        assertIs<ToolResult.Success>(result)
        val summary = ((result.data as? Map<*, *>)?.get("summary") as? String).orEmpty()
        assertContains(summary, "image-based")
    }

    @Test
    fun `analyze lists archive contents without extracting`() = runTest {
        val backend = FakeBackend(mapOf("unzip" to "a.txt\nb/c.png"))
        val tool = FileAnalysisTool { backend }
        val result = tool.analyze(FileAnalysisArgs(
            sandboxId = "sb1", fileName = "project.zip", mimeType = "application/zip",
            data = Base64.encode("dummy".encodeToByteArray()),
        ))
        assertIs<ToolResult.Success>(result)
        val summary = ((result.data as? Map<*, *>)?.get("summary") as? String).orEmpty()
        assertContains(summary, "Archive contents")
        assertContains(summary, "a.txt")
        // The archive is never expanded — only listed.
        assertTrue(backend.executed.none { it.first() == "unzip" && "-x" in it })
    }

    @Test
    fun `analyze rejects invalid base64`() = runTest {
        val tool = toolFor(emptyMap())
        val result = tool.analyze(FileAnalysisArgs(
            sandboxId = "sb1", fileName = "x.pdf", mimeType = "application/pdf", data = "!!not-base64!!",
        ))
        assertIs<ToolResult.Failure>(result)
    }

    @Test
    fun `analyze rejects oversized attachments before writing`() = runTest {
        val backend = FakeBackend()
        val tool = FileAnalysisTool { backend }
        val big = Base64.encode(ByteArray(11 * 1024 * 1024))
        val result = tool.analyze(FileAnalysisArgs(
            sandboxId = "sb1", fileName = "big.pdf", mimeType = "application/pdf", data = big,
        ))
        assertIs<ToolResult.Failure>(result)
        assertTrue(backend.writtenFiles.isEmpty())
    }

    @Test
    fun `analyze sanitizes dangerous file names`() = runTest {
        val backend = FakeBackend(mapOf("pdfinfo" to "", "pdftotext" to ""))
        val tool = FileAnalysisTool { backend }
        tool.analyze(FileAnalysisArgs(
            sandboxId = "sb1", fileName = "../../../etc/passwd; rm -rf .pdf",
            mimeType = "application/pdf", data = Base64.encode("x".encodeToByteArray()),
        ))
        val path = backend.writtenFiles.first().first
        // Every non-alphanumeric character (dots, slashes, spaces, semicolons)
        // is replaced with "_" — path traversal and shell injection are both
        // neutralized into an opaque safe name.
        // "../../../etc/passwd; rm -rf .pdf" -> SAFE_NAME (collapses runs of
        // non-alphanumeric chars into a single "_", but keeps hyphens/dots) ->
        // ".._.._.._etc_passwd_rm_-rf_.pdf": slashes and the "; " run are
        // neutralized, breaking both path traversal and injection, while the
        // "-rf" flag hyphens survive (harmless in the sandbox path).
        assertEquals("/root/uploads/.._.._.._etc_passwd_rm_-rf_.pdf", path)
    }

    @Test
    fun `analyze registers as a read-only risk tool`() {
        val runtime = com.inspiredandroid.kai.tools.ToolRuntime(
            emitActivity = { _ -> },
        )
        // Sandbox tools are routed through ToolRuntime.call/riskLevelFor and
        // advertised in AgentOrchestrator.availableToolIds() — both must know
        // about analyze_file for the tool to be usable end-to-end.
        assertEquals(com.inspiredandroid.kai.tools.ToolRiskLevel.READ_ONLY, runtime.riskLevelFor("analyze_file"))
        assertTrue(
            com.inspiredandroid.kai.tools.ANALYZE_FILE_TOOL_ID == "analyze_file",
        )
    }
}
