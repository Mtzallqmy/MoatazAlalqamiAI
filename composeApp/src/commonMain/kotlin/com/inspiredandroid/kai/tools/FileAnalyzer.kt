package com.inspiredandroid.kai.tools

import com.inspiredandroid.kai.data.Attachment
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * On-device, LLM-free file analysis for user attachments.
 *
 * Text-like formats are decoded in `commonMain` so every platform gets the same
 * extraction. Binary office documents (PDF/DOCX/XLSX/PPTX/archives/images) are
 * analysed by the `analyze_file` sandbox tool at agent runtime — see
 * `ToolRuntime.analyzeFile`.
 *
 * Two public entry points:
 * - `extractText(attachment)`: full decoded text of text-like files, or `null`
 *   for binary files that need the sandbox tool.
 * - `attachmentSummary(attachment, ...)`: a short human-readable description
 *   (name + size + type) that is always prepended to user messages so even
 *   text-only models understand what the user attached.
 */

private val TEXT_MIME_PREFIXES = listOf("text/", "application/json", "application/xml",
    "application/javascript", "application/x-yaml", "application/yaml",
    "application/x-sh", "application/sql", "application/graphql", "application/toml")

private val TEXT_EXTENSIONS = setOf(
    "txt", "md", "json", "csv", "xml", "yaml", "yml",
    "html", "css", "js", "ts", "kt", "kts", "java",
    "py", "rb", "rs", "go", "c", "h", "cpp", "hpp",
    "swift", "sh", "bash", "zsh", "sql", "graphql",
    "toml", "ini", "cfg", "conf", "log", "properties",
    "gradle", "tsx", "jsx", "gsc",
)

@OptIn(ExperimentalEncodingApi::class)
fun extractText(attachment: Attachment): String? {
    val mime = attachment.mimeType
    val ext = attachment.fileName?.substringAfterLast('.', "")?.lowercase() ?: ""
    val looksText = TEXT_MIME_PREFIXES.any { mime.startsWith(it) } || mime == "application/json" ||
        ext in TEXT_EXTENSIONS
    if (!looksText) return null
    return try {
        Base64.decode(attachment.data).decodeToString()
    } catch (_: IllegalArgumentException) {
        null
    }
}

/** Human-readable byte size (e.g. "1.2 MB"). */
internal fun formatBytes(bytes: Long): String {
    require(bytes >= 0) { "bytes must be non-negative" }
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    val mb = kb / 1024
    if (mb < 1024) return String.format("%.1f MB", mb)
    return String.format("%.1f GB", mb / 1024)
}

/**
 * Short summary for one attachment — name, size, type. Always prepended to
 * user messages so the model knows a file came along, even when it cannot
 * receive the raw bytes (text-only OpenAI-compatible models).
 */
@OptIn(ExperimentalEncodingApi::class)
fun attachmentSummary(attachment: Attachment): String {
    val name = attachment.fileName?.takeIf { it.isNotBlank() } ?: "attachment"
    val bytes = runCatching { Base64.decode(attachment.data).size }.getOrDefault(0)
    val typeLabel = attachment.mimeType.ifBlank {
        attachment.fileName?.substringAfterLast('.', "")?.lowercase() ?: "file"
    }
    return "Attached file: $name (${formatBytes(bytes.toLong())}, $typeLabel)"
}
