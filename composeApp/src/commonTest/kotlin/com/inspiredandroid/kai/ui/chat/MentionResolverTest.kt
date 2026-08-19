package com.inspiredandroid.kai.ui.chat

import com.inspiredandroid.kai.NoOpSandboxController
import com.inspiredandroid.kai.SandboxController
import com.inspiredandroid.kai.SandboxFileEntry
import com.inspiredandroid.kai.TextFileResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * Verifies that `@<path>` mentions typed into the chat composer are detected,
 * resolved from the sandbox, and inlined into the prompt without altering the
 * user-visible message text.
 */
class MentionResolverTest {

    @Test
    fun `detects mention query at token start`() {
        assertEquals("", detectMentionQuery("@", 1))
        assertEquals("pro", detectMentionQuery("@pro", 4))
        assertEquals("app.py", detectMentionQuery("review @app.py", 14))
        assertEquals("", detectMentionQuery("hello @", 7))
        // `@` mid-word is not a mention token
        assertEquals(null, detectMentionQuery("email@x.com", 6))
        // Past a space inside the token stops matching
        assertEquals(null, detectMentionQuery("@app bar", 8))
    }

    @Test
    fun `applyMentionSuggestion rewrites the active token`() {
        val rewritten = applyMentionSuggestion("review @app", 11, "/root/projects/app.py")
        assertEquals("review @/root/projects/app.py ", rewritten)
    }

    @Test
    fun `applyMentionSuggestion consumes trailing whitespace and keeps the tail`() {
        val rewritten = applyMentionSuggestion("@app check this", 4, "/root/a.py")
        assertEquals("@/root/a.py check this", rewritten)
    }

    @Test
    fun `resolve returns original message when there are no mentions`() = runTest {
        val resolver = MentionResolver(NoOpSandboxController())
        assertEquals("hello world", resolver.resolve("hello world"))
        assertEquals("", resolver.resolve(""))
    }

    private class FakeSandbox(
        private val dirs: Map<String, List<SandboxFileEntry>> = emptyMap(),
        private val contents: Map<String, TextFileResult> = emptyMap(),
    ) : SandboxController by NoOpSandboxController() {
        override suspend fun listDirectory(path: String): List<SandboxFileEntry> = dirs[path] ?: emptyList()
        override suspend fun readTextFile(path: String, maxBytes: Int, force: Boolean): TextFileResult =
            contents[path] ?: TextFileResult.Unreadable
    }

    @Test
    fun `resolve inlines file content for each mention`() = runTest {
        val entries = mapOf(
            "/root/projects" to listOf(SandboxFileEntry("app.py", "/root/projects/app.py", isDirectory = false, sizeBytes = 10, lastModifiedMs = 0L)),
            "/root" to listOf(SandboxFileEntry("projects", "/root/projects", isDirectory = true, sizeBytes = 0, lastModifiedMs = 0L)),
        )
        val contents = mapOf(
            "/root/projects/app.py" to "print('hi')",
        )
        val sandbox = FakeSandbox(dirs = entries, contents = contents.mapValues { TextFileResult.Text(it.value) })
        val resolver = MentionResolver(sandbox)
        val resolved = resolver.resolve("check @/root/projects/app.py please")
        assertTrue(resolved.startsWith("check @/root/projects/app.py please"), resolved)
        assertTrue(resolved.contains("--- mentioned: @/root/projects/app.py ---"), resolved)
        assertTrue(resolved.contains("print('hi')"), resolved)
        assertTrue(resolved.endsWith("--- end mentions ---"), resolved)
    }

    @Test
    fun `unreadable mentions surface as markers instead of failing`() = runTest {
        val resolver = MentionResolver(NoOpSandboxController())
        val resolved = resolver.resolve("see @/root/missing.py")
        assertTrue(resolved.startsWith("see @/root/missing.py"), resolved)
        assertTrue(resolved.contains("[unreadable: @/root/missing.py]"), resolved)
    }

    @Test
    fun `rawMentions dedupes and caps count`() {
        val resolver = MentionResolver(NoOpSandboxController())
        val mentions = resolver.rawMentions("@a @b @a @/c")
        assertEquals(3, mentions.size)
        assertTrue("@a" in mentions && "@b" in mentions && "@/c" in mentions)
    }

    @Test
    fun `rawMentions normalizes double slashes`() {
        val resolver = MentionResolver(NoOpSandboxController())
        assertEquals(listOf("@/root/a"), resolver.rawMentions("@//root///a"))
    }

    @Test
    fun `candidateFor requires a matching sandbox entry`() = runTest {
        val sandbox = FakeSandbox(
            dirs = mapOf("/root" to listOf(SandboxFileEntry("f.txt", "/root/f.txt", isDirectory = false, sizeBytes = 5, lastModifiedMs = 0L))),
        )
        val resolver = MentionResolver(sandbox)
        // candidateFor takes the raw mention path (`@` prefix stripped by the caller)
        assertEquals("/root/f.txt", resolver.candidateFor("/root/f.txt")?.path)
        assertEquals(null, resolver.candidateFor("/root/ghost.txt"))
    }

    @Test
    fun `collectMentionCandidates walks directories depth first without hidden dirs`() = runTest {
        val sandbox = FakeSandbox(
            dirs = mapOf(
                "/root/projects" to listOf(
                    SandboxFileEntry("todo", "/root/projects/todo", isDirectory = true, sizeBytes = 0, lastModifiedMs = 0L),
                    SandboxFileEntry(".hidden", "/root/projects/.hidden", isDirectory = true, sizeBytes = 0, lastModifiedMs = 0L),
                ),
                "/root/projects/todo" to listOf(
                    SandboxFileEntry("app.py", "/root/projects/todo/app.py", isDirectory = false, sizeBytes = 5, lastModifiedMs = 0L),
                ),
                "/root/projects/.hidden" to listOf(SandboxFileEntry("secret", "/root/projects/.hidden/secret", isDirectory = false, sizeBytes = 1, lastModifiedMs = 0L)),
            ),
        )
        // depthLimit=2 means root (depth 0) + one level (depth 1) + another (depth 2) — app.py is at depth 2.
        val candidates = collectMentionCandidates(sandbox, rootPaths = listOf("/root/projects"), depthLimit = 2)
        assertTrue(candidates.any { it.path == "/root/projects/todo" }, candidates.toString())
        assertTrue(candidates.any { it.path == "/root/projects/todo/app.py" }, candidates.toString())
        assertTrue(candidates.none { it.path.contains(".hidden") }, candidates.toString())
    }

    private class BoomSandbox : SandboxController by NoOpSandboxController() {
        override suspend fun listDirectory(path: String): List<SandboxFileEntry> =
            throw IllegalStateException("boom")
    }

    @Test
    fun `collectMentionCandidates tolerates sandbox errors`() = runTest {
        val candidates = collectMentionCandidates(BoomSandbox())
        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `binary files report as binary instead of failing`() = runTest {
        val sandbox = FakeSandbox(
            dirs = mapOf("/root" to listOf(SandboxFileEntry("blob.bin", "/root/blob.bin", isDirectory = false, sizeBytes = 20, lastModifiedMs = 0L))),
            contents = mapOf("/root/blob.bin" to TextFileResult.Binary),
        )
        val resolver = MentionResolver(sandbox)
        val resolved = resolver.resolve("look at @/root/blob.bin")
        assertTrue(resolved.contains("(binary file, not readable as text)"), resolved)
    }
}
