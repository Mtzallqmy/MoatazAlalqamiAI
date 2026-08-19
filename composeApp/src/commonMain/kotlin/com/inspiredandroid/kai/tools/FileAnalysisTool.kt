package com.inspiredandroid.kai.tools

import com.inspiredandroid.kai.sandbox.backend.ExecRequest
import com.inspiredandroid.kai.sandbox.backend.SandboxBackend
import com.inspiredandroid.kai.sandbox.backend.SandboxError
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * `analyze_file` — lets the agent read *any* user attachment, not just images
 * or PDFs that the chat providers happen to support.
 *
 * Flow: decode the attached bytes -> write them into an isolated uploads
 * directory inside the sandbox -> run a small, format-aware extraction
 * script (pdftotext / python3 zipfile / list / image info) -> return the
 * extracted text as the tool result so the LLM can reason about the file.
 *
 * Security notes:
 * - The upload path is derived only from a sanitized file name; the sandbox
 *   is an isolated rootfs, not the user's device.
 * - Archives are never auto-extracted (only listed) — extraction is a
 *   deliberate follow-up command that still passes through the unified
 *   `PolicyEngine` command checks.
 */

const val ANALYZE_FILE_TOOL_ID: String = "analyze_file"

private const val MAX_ATTACHMENT_BYTES: Int = 10 * 1024 * 1024 // 10 MB raw cap

private val SAFE_NAME = Regex("[^A-Za-z0-9._\\-]+")

@OptIn(ExperimentalEncodingApi::class)
class FileAnalysisTool(private val backend: () -> SandboxBackend) {

    suspend fun analyze(args: FileAnalysisArgs): ToolResult {
        val raw = try {
            Base64.decode(args.data)
        } catch (e: IllegalArgumentException) {
            return ToolResult.Failure("Attachment data is not valid base64: ${e.message}", retryable = false)
        }
        if (raw.size > MAX_ATTACHMENT_BYTES) {
            return ToolResult.Failure(
                "Attachment too large (${raw.size} bytes) — maximum is $MAX_ATTACHMENT_BYTES bytes",
                retryable = false,
            )
        }

        val safeName = args.fileName?.takeIf { it.isNotBlank() }
            ?.let { SAFE_NAME.replace(it, "_") }?.take(120) ?: "attachment.bin"
        val uploadPath = "/root/uploads/$safeName"
        val backend = backend()
        backend.exec(args.sandboxId, ExecRequest(command = "mkdir", args = listOf("-p", "/root/uploads")))
        backend.writeFile(args.sandboxId, uploadPath, raw)

        val summary = runCatching { runExtraction(backend, args.sandboxId, uploadPath, args.mimeType, safeName) }
            .getOrElse { e ->
                when (e) {
                    is AnalysisUnsupported -> return ToolResult.Failure(e.message ?: "Unsupported file type", retryable = false)
                    is AnalysisFailure -> return ToolResult.Failure(e.message ?: "Analysis failed", retryable = true)
                    else -> return ToolResult.Failure("File analysis failed: ${e.message ?: e::class.simpleName}", retryable = false)
                }
            }

        return ToolResult.Success(summary)
    }

    private suspend fun runExtraction(
        backend: SandboxBackend,
        sandboxId: String,
        path: String,
        mimeType: String,
        name: String,
    ): Map<String, String> {
        val base = "File: $name\n"

        return when {
            mimeType == "application/pdf" || name.endsWith(".pdf") -> {
                val pages = execLine(backend, sandboxId, listOf("pdfinfo", path))
                    .lines().firstOrNull { it.startsWith("Pages:") }?.trim() ?: ""
                val text = execLine(backend, sandboxId, listOf("pdftotext", "-layout", path, "-"))
                    .trim().take(MAX_OUTPUT_CHARS)
                if (text.isEmpty()) {
                    mapOf("summary" to "$base${pages.ifEmpty { "Pages: unknown" }}\nThis PDF appears to be image-based (no extractable text).")
                } else {
                    mapOf("summary" to "$base${pages.ifEmpty { "" }}\n\nExtracted text:\n$text")
                }
            }
            name.endsWith(".docx") -> officeText(backend, sandboxId, path, name, base)
            name.endsWith(".xlsx") || name.endsWith(".xls") -> spreadsheetText(backend, sandboxId, path, name, base)
            name.endsWith(".pptx") -> presentationText(backend, sandboxId, path, name, base)
            name.endsWith(".zip") || name.endsWith(".tar") || name.endsWith(".gz") || name.endsWith(".tgz") -> {
                val listing = archiveListing(backend, sandboxId, path, name)
                mapOf("summary" to "$base\nArchive contents:\n$listing")
            }
            mimeType.startsWith("image/") -> {
                val escapedPath = path.replace("'", "'\"'\"'")
                val dims = execLine(backend, sandboxId, listOf("python3", "-c",
                    "from PIL import Image; im=Image.open('$escapedPath'); print(f\"{im.size[0]}x{im.size[1]} {im.mode}\")"))
                    .takeIf { it.isNotBlank() } ?: "dimensions unknown"
                mapOf("summary" to "$base\nImage: $dims")
            }
            else -> {
                val probe = execLine(backend, sandboxId, listOf("file", path)).trim().take(200)
                mapOf("summary" to "${base}File type probe: ${probe.ifEmpty { "unknown" }}\nThis file type has no built-in extractor; the agent can still read it with fs.read if it is text-based.")
            }
        }
    }

    private suspend fun officeText(backend: SandboxBackend, sandboxId: String, path: String, name: String, base: String): Map<String, String> {
                val escapedPath = path.replace("'", "'\"'\"'")
                val script = """
import zipfile, re, sys
texts = []
try:
    with zipfile.ZipFile('$escapedPath') as z:
        for n in z.namelist():
            if n.startswith('word/') and n.endswith('.xml'):
                xml = z.read(n).decode('utf-8', 'replace')
                texts.append(' '.join(re.findall(r'<w:t[^>]*>([^<]*)</w:t>', xml)))
except Exception as e:
    print('ERR: ' + str(e)); sys.exit(1)
print('\n\n'.join(t for t in texts if t.strip()))
""".trimIndent()
        val text = execLine(backend, sandboxId, listOf("python3", "-c", script)).trim().take(MAX_OUTPUT_CHARS)
        return mapOf("summary" to if (text.isEmpty()) "${base}DOCX contains no extractable text (likely image-based)." else "$base\nExtracted text:\n$text")
    }

    private suspend fun spreadsheetText(backend: SandboxBackend, sandboxId: String, path: String, name: String, base: String): Map<String, String> {
        val escapedPath = path.replace("'", "'\"'\"'")
        val script = """
import zipfile, re, html
out = []
try:
    with zipfile.ZipFile('$escapedPath') as z:
        xml = z.read('xl/sharedStrings.xml').decode('utf-8', 'replace') if 'xl/sharedStrings.xml' in z.namelist() else ''
        shared = re.findall(r'<si>(?:<r[^>]*>)*<t[^>]*>([^<]*)</t>(?:</r>)*</si>', xml) or re.findall(r'<t[^>]*>([^<]*)</t>', xml)
        for s in z.namelist():
            m = re.match(r'xl/worksheets/sheet\d+\.xml', s)
            if not m: continue
            sx = z.read(s).decode('utf-8', 'replace')
            refs = re.findall(r'<c [^>]*r="([A-Z]+\d+)"[^>]*/?>', sx)
            vals = re.findall(r'<c [^>]*t="s"[^>]*><v>(\d+)</v></c>', sx)
            row = {}
            for r, v in zip(refs, vals):
                try: row[r] = shared[int(v)]
                except IndexError: pass
            if row:
                out.append(' | '.join(f'{c}={html.unescape(row[c])}' for c in sorted(row)))
except Exception as e:
    print('ERR: ' + str(e)); sys.exit(1)
print('\n'.join(out))
""".trimIndent()
        val text = execLine(backend, sandboxId, listOf("python3", "-c", script)).trim().take(MAX_OUTPUT_CHARS)
        return mapOf("summary" to if (text.isEmpty()) "${base}Spreadsheet contains no readable data." else "$base\nCells:\n$text")
    }

    private suspend fun presentationText(backend: SandboxBackend, sandboxId: String, path: String, name: String, base: String): Map<String, String> {
        val escapedPath = path.replace("'", "'\"'\"'")
        val script = """
import zipfile, re
texts = []
try:
    with zipfile.ZipFile('$escapedPath') as z:
        for n in sorted(z.namelist()):
            m = re.match(r'ppt/slides/slide(\d+)\.xml', n)
            if not m: continue
            xml = z.read(n).decode('utf-8', 'replace')
            t = ' '.join(re.findall(r'<a:t>([^<]*)</a:t>', xml))
            if t.strip(): texts.append(f"Slide {m.group(1)}: {t.strip()}")
except Exception as e:
    print('ERR: ' + str(e)); sys.exit(1)
print('\n'.join(texts) if texts else 'NO_TEXT')
""".trimIndent()
        val out = execLine(backend, sandboxId, listOf("python3", "-c", script)).trim().take(MAX_OUTPUT_CHARS)
        return mapOf("summary" to if (out.isEmpty() || out == "NO_TEXT") "${base}Presentation contains no extractable text." else "$base\n$out")
    }

    private suspend fun archiveListing(backend: SandboxBackend, sandboxId: String, path: String, name: String): String {
        return when {
            name.endsWith(".zip") -> execLine(backend, sandboxId, listOf("unzip", "-l", path)).trim().lines().take(30).joinToString("\n")
            name.endsWith(".tar") || name.endsWith(".tgz") || name.endsWith(".gz") ->
                execLine(backend, sandboxId, listOf("tar", "tzf", path)).ifEmpty { execLine(backend, sandboxId, listOf("tar", "tf", path)) }
                    .trim().lines().take(30).joinToString("\n")
            else -> "(listing unavailable)"
        }.take(MAX_OUTPUT_CHARS)
    }

    private suspend fun execLine(backend: SandboxBackend, sandboxId: String, args: List<String>): String =
        backend.exec(sandboxId, ExecRequest(command = args.first(), args = args.drop(1))).stdout

    companion object {
        private const val MAX_OUTPUT_CHARS = 8000
    }
}

class FileAnalysisArgs(
    val sandboxId: String,
    val fileName: String?,
    val mimeType: String,
    val data: String,
)

/** Non-retryable: the format genuinely has no extractor. */
internal class AnalysisUnsupported(message: String) : Exception(message)

/** Retryable: transient command failure inside the sandbox. */
internal class AnalysisFailure(message: String) : Exception(message)
