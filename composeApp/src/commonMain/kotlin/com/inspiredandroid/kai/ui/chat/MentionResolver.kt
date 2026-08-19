package com.inspiredandroid.kai.ui.chat

import com.inspiredandroid.kai.SandboxController
import com.inspiredandroid.kai.SandboxFileEntry
import com.inspiredandroid.kai.SandboxSessions
import com.inspiredandroid.kai.TextFileResult

/**
 * Maximum file content (bytes) inlined for a single `@path` mention. Matches the
 * chat sandbox's bounded read cap so a hostile mention cannot exhaust memory.
 */
internal const val MENTION_MAX_FILE_BYTES = 64 * 1024

/** Hard cap on how many mentions a single message may resolve. */
internal const val MENTION_MAX_COUNT = 10

/** A single file found in the sandbox that can be mentioned with `@`. */
data class MentionCandidate(
    /** Guest path, e.g. `/root/projects/todo/app.py` or `projects/todo/app.py` for display. */
    val path: String,
    /** Basename shown in the suggestion list. */
    val displayName: String,
    val isDirectory: Boolean,
)

/**
 * Resolves `@<path>` mentions typed into the chat composer. The user types the raw
 * token; on send the resolver inlines each referenced file's content (when readable)
 * so the agent sees the file without the user attaching it manually.
 *
 * Reads come straight from the sandbox controller — on platforms without a Linux
 * environment [SandboxController] is a [com.inspiredandroid.kai.NoOpSandboxController]
 * and every mention silently falls back to an unreadable marker.
 */
class MentionResolver(private val sandbox: SandboxController) {

    /** Token pattern: `@` followed by a path that may contain `/`, letters, digits, `.` `-` `_`. */
    private val mentionRegex = Regex("""@(/[A-Za-z0-9._/\-]+|[A-Za-z0-9._/\-][A-Za-z0-9._/\-]*)""")

    /**
     * Returns the raw mention paths the user typed, in message order, de-duplicated
     * while preserving first occurrence.
     */
    fun rawMentions(message: String): List<String> {
        val seen = linkedSetOf<String>()
        mentionRegex.findAll(message).forEach { seen += "@" + normalize(it.groupValues[1]) }
        return seen.toList().take(MENTION_MAX_COUNT)
    }

    /**
     * Resolves [mention] to a display-ready candidate when the sandbox knows about it.
     * Used by the suggestion sheet to show only files that actually exist.
     */
    suspend fun candidateFor(mention: String): MentionCandidate? {
        val entry = sandbox.listDirectory(parentOf(mention)).firstOrNull { it.name == lastOf(mention) }
            ?: return null
        return MentionCandidate(
            path = mention,
            displayName = entry.name,
            isDirectory = entry.isDirectory,
        )
    }

    /**
     * Inlines every `@<path>` mention in [message] by appending each file's content
     * after the user text. The visible message stays exactly what the user typed;
     * the appended block is purely context for the model. Unreadable mentions are
     * reported as `[unreadable: <path>]` markers inside the block so the model knows
     * the reference could not be loaded.
     */
    suspend fun resolve(message: String): String {
        val mentions = rawMentions(message)
        if (mentions.isEmpty()) return message

        val blocks = buildString {
            for (mention in mentions) {
                appendLine()
                appendLine("--- mentioned: $mention ---")
                when (val result = readSandboxFile(mention.removePrefix("@"))) {
                    is TextFileResult.Text -> append(result.content)
                    is TextFileResult.Binary -> append("(binary file, not readable as text)")
                    is TextFileResult.TooLarge -> append("(file too large to inline: ${result.sizeBytes} bytes)")
                    is TextFileResult.Unreadable -> append("[unreadable: $mention]")
                }
                appendLine()
            }
            append("--- end mentions ---")
        }
        return if (blocks.isNotBlank()) "$message\n$blocks" else message
    }

    private suspend fun readSandboxFile(path: String): TextFileResult {
        val resolved = path.removePrefix("/")
        return try {
            sandbox.readTextFile("/$resolved", maxBytes = MENTION_MAX_FILE_BYTES, force = true)
        } catch (_: Throwable) {
            TextFileResult.Unreadable
        }
    }

    private fun parentOf(path: String): String {
        val withoutLeading = path.removePrefix("/")
        val lastSlash = withoutLeading.lastIndexOf('/')
        return if (lastSlash <= 0) "/" else "/" + withoutLeading.substring(0, lastSlash)
    }

    private fun lastOf(path: String): String {
        val withoutLeading = path.removePrefix("/")
        val lastSlash = withoutLeading.lastIndexOf('/')
        return if (lastSlash < 0) withoutLeading else withoutLeading.substring(lastSlash + 1)
    }

    private fun normalize(token: String): String {
        // Collapse `//` and strip a single trailing slash so different typings resolve identically.
        var cleaned = token.replace(Regex("/{2,}"), "/")
        if (cleaned.length > 1 && cleaned.endsWith('/')) cleaned = cleaned.dropLast(1)
        return cleaned
    }
}

/**
 * Walks [rootPaths] (guest paths) collecting text-friendly candidates depth-first
 * up to [depthLimit] directory levels and [maxCandidates] total entries. Skips
 * hidden directories (dotfiles) and non-readable roots silently.
 */
suspend fun collectMentionCandidates(
    sandbox: SandboxController,
    rootPaths: List<String> = listOf("/root/projects", "/root", "/home"),
    depthLimit: Int = 2,
    maxCandidates: Int = 40,
): List<MentionCandidate> {
    val out = mutableListOf<MentionCandidate>()
    suspend fun walk(path: String, depth: Int) {
        if (out.size >= maxCandidates || depth > depthLimit) return
        val entries = try {
            sandbox.listDirectory(path)
        } catch (_: Throwable) {
            emptyList<SandboxFileEntry>()
        }
        for (entry in entries) {
            if (out.size >= maxCandidates) break
            if (entry.name.startsWith('.') && entry.isDirectory) continue
            val guestPath = path.trimEnd('/') + "/" + entry.name
            out += MentionCandidate(
                path = guestPath,
                displayName = entry.name,
                isDirectory = entry.isDirectory,
            )
            if (entry.isDirectory) walk(guestPath, depth + 1)
        }
    }
    for (root in rootPaths) walk(root, depth = 0)
    return out
}

/** Sanity helper used by tests and the suggestion flow — does not require a live sandbox. */
internal fun detectMentionQuery(text: String, cursor: Int): String? {
    if (cursor <= 0 || cursor > text.length) return null
    val before = text.substring(0, cursor)
    val lastAt = before.lastIndexOf('@')
    if (lastAt < 0) return null
    val token = before.substring(lastAt + 1)
    // Query must be a single unbroken token (no spaces before the cursor) and the
    // `@` must be at the start of a token (preceded by whitespace or line start).
    val prefixChar = if (lastAt > 0) before[lastAt - 1] else ' '
    if (!prefixChar.isWhitespace()) return null
    if (token.any { it.isWhitespace() }) return null
    if (token.isEmpty()) return ""
    return token
}

/** Insert/replace suggestion: rewrites the active `@<query>` token to `@<path> `. */
internal fun applyMentionSuggestion(text: String, cursor: Int, path: String): String {
    val before = text.substring(0, cursor)
    val lastAt = before.lastIndexOf('@')
    val tokenStart = lastAt + 1
    val afterCursor = text.substring(cursor)
    // Strip any following whitespace after the cursor so `@foo bar` -> `@/root/f bar` works.
    val tail = afterCursor.trimStart()
    val beforeToken = text.substring(0, tokenStart)
    return "$beforeToken$path " + tail
}

/** Expose the session constant for the sandbox root lookup. */
@Suppress("unused")
internal val mentionSandboxSessionId = SandboxSessions.DEFAULT
