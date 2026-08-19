package com.inspiredandroid.kai.browser

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Stable backend identifier — [BrowserRouter] resolves engines by id.
 * Never hardcode a specific engine inside the Agent Runtime or Tool layer;
 * the runtime only knows about [BrowserEngine] contracts.
 */
data class BrowserEngineId(val id: String) {
    companion object {
        val LIGHTPANDA_GATEWAY = BrowserEngineId("lightpanda-gateway")
        val MOCK = BrowserEngineId("mock")
    }
}

/**
 * One browsing session scoped to a single AgentRun. Cookies, page history,
 * memory and navigation state never leak across runs — the backend is
 * required to destroy the session on [close] and to release all resources.
 */
data class BrowserSession(
    val sessionId: String,
    val engineId: BrowserEngineId,
    val runId: String,
    /** Whether the session is still alive and usable. */
    val isActive: Boolean = true,
)

/** Snapshot of page state as exposed to the agent — stable element ids, not CSS selectors. */
data class CdpElement(
    /** Stable handle issued by the engine — opaque to the agent, durable for the session. */
    val targetId: String,
    val tag: String,
    val role: String,
    val name: String?,
    val value: String?,
    val text: String?,
)

/**
 * The page model returned to the LLM. [Html] and [Screenshot] are deliberately
 * excluded from agent-visible reads to keep LLM context cheap and safe.
 */
sealed class CdpPageModel {
    data class Markdown(val content: String, val url: String) : CdpPageModel()
    data class SemanticTree(val root: CdpNode) : CdpPageModel()
    data class Elements(val elements: List<CdpElement>) : CdpPageModel()
    data object TitleOnly : CdpPageModel()
}

/** Simplified semantic tree node — tags and interactable elements only, no raw HTML. */
data class CdpNode(
    val role: String,
    val name: String?,
    val targetId: String?,
    val children: List<CdpNode> = emptyList(),
)

/**
 * Unified browser action + args — every tool call materializes into one of
 * these before reaching any backend, so policies and auditing stay engine-
 * independent.
 */
sealed class BrowserAction {
    data class Open(val url: String, val timeoutMs: Long = 30_000L) : BrowserAction()
    data class Read(val format: ReadFormat) : BrowserAction()
    data class Click(val targetId: String) : BrowserAction()
    data class TypeText(val targetId: String, val text: String, val submit: Boolean = false) : BrowserAction()
    data object Back : BrowserAction()
    data class Extract(val query: String?) : BrowserAction()
    data object Close : BrowserAction()
}

/** Read output the LLM is allowed to see. */
enum class ReadFormat {
    /** Markdown conversion (LLM primary format). */
    MARKDOWN,
    /** Stable semantic tree + interactable element index. */
    SEMANTIC,
    /** Interactable element index only (compact, for click/type targeting). */
    ELEMENTS,
}

/**
 * Unified browser result — never raw HTML, never engine-specific wire data.
 */
sealed class BrowserResult {
    data class Navigated(val url: String, val title: String?) : BrowserResult()
    data class Read(val model: CdpPageModel) : BrowserResult()
    data class Clicked(val targetId: String, val url: String) : BrowserResult()
    data class Typed(val targetId: String, val submitted: Boolean) : BrowserResult()
    data class Back(val url: String, val title: String?) : BrowserResult()
    data class Extracted(val content: String) : BrowserResult()
    data object Closed : BrowserResult()
    data class Failed(val error: String, val retryable: Boolean = false) : BrowserResult()
    /** Raised by the policy layer (SSRF, prompt injection, size limits) before any engine call. */
    data class Blocked(val reason: String) : BrowserResult()
}

/**
 * Content-safety policy applied to every read output (prompt-injection
 * mitigation) — strips executable markup, invisible tricks and instruction
 * injection attempts from data handed to the LLM.
 */
object PromptInjectionFilter {
    private val INVISIBLE_PATTERNS = listOf(
        "system", "instruction", "ignore previous", "disregard", "new directive",
        "as an ai", "override", "admin mode", "developer mode",
    )

    fun sanitize(raw: String): String {
        var text = raw
        // Kill executable/script-bearing markup before it can become a prompt.
        text = SCRIPT_TAG.replace(text, " ")
        text = META_REFRESH.replace(text, " ")
        text = ONHANDLER_ATTR.replace(text, " ")
        text = HTML_TAG.replace(text, " ")
        text = SVG_TAG.replace(text, " ")
        // Zero-width/invisible injection markers.
        text = text.replace(ZERO_WIDTH, "")
        text = text.replace(LEFT_TO_RIGHT_OVERRIDE, "")
        text = text.replace(RIGHT_TO_LEFT_OVERRIDE, "")
        // Collapse repeated invisible-trick spacing.
        text = MULTILINE_BLANK.replace(text, "\n")
        return text.trim()
    }

    /** Rejects page content that is itself an injection attempt — heuristics over the sanitized text. */
    fun isSuspiciousInstruction(text: String): Boolean {
        val lower = text.lowercase()
        return INVISIBLE_PATTERNS.any { pattern ->
            lower.contains(pattern)
        }
    }

    private val SCRIPT_TAG = Regex("""<\s*script[\s\S]*?<\s*/\s*script\s*>""", RegexOption.IGNORE_CASE)
    private val META_REFRESH = Regex("""<\s*meta[^>]*http-equiv\s*=\s*["']?refresh[^>]*>""", RegexOption.IGNORE_CASE)
    private val ONHANDLER_ATTR = Regex("""\bon\w+\s*=\s*["'][^"']*["']""", RegexOption.IGNORE_CASE)
    private val HTML_TAG = Regex("""<\s*html[^>]*>""", RegexOption.IGNORE_CASE)
    private val SVG_TAG = Regex("""<\s*svg[\s\S]*?<\s*/\s*svg\s*>""", RegexOption.IGNORE_CASE)
    private val ZERO_WIDTH = Regex("[\u200B\u200C\u200D\uFEFF\u200E\u200F\u061C]")
    private val LEFT_TO_RIGHT_OVERRIDE = Regex("[\u202A-\u202E]")
    private val RIGHT_TO_LEFT_OVERRIDE = Regex("[\u2066-\u2069]")
    private val MULTILINE_BLANK = Regex("\n{4,}")
}

/**
 * SSRF policy — rejects navigation into loopback, private, link-local and
 * cloud-metadata ranges plus non-http schemes. The same guards apply to
 * gateway URLs in settings.
 */
object SsrfGuard {
    val ALLOWED_SCHEMES = setOf("http", "https")

    fun isBlocked(url: String): String? {
        if (url.isBlank()) return "empty url"
        val withoutScheme = url.substringAfter("://").substringBefore("#").substringBefore("?")
        val scheme = url.substringBefore("://").lowercase()
        if (scheme !in ALLOWED_SCHEMES) return "scheme '$scheme' not allowed (http/https only)"
        val authority = withoutScheme.substringBefore("/").substringAfterLast("@").lowercase()
        val host = parseHost(authority) ?: return "malformed url"
        if (host.isEmpty()) return "malformed url"
        val blocked = blockedHost(host)
        if (blocked != null) return blocked
        return null
    }

    fun blockedHost(host: String): String? = when {
        host == "localhost" -> "localhost is blocked"
        host == "ip6-localhost" || host == "ip6-loopback" -> "loopback alias blocked"
        host.startsWith("127.") -> "loopback network blocked"
        host == "::1" || host.equals("[::1]", ignoreCase = true) -> "ipv6 loopback blocked"
        host.startsWith("10.") || host.startsWith("192.168.") -> "private network blocked"
        host.startsWith("172.") -> {
            val second = host.split(".").getOrNull(1)?.toIntOrNull()
            if (second != null && second in 16..31) "private network blocked" else null
        }
        host.startsWith("169.254.") -> "link-local / cloud metadata blocked"
        host.contains(":") -> when {
            host.startsWith("fc") || host.startsWith("fd") -> "unique-local ipv6 blocked"
            host.startsWith("fe8") || host.startsWith("fe9") ||
                host.startsWith("fea") || host.startsWith("feb") -> "link-local ipv6 blocked"
            host == "::" -> "unspecified ipv6 blocked"
            else -> null
        }
        // DNS rebinding via numeric hostnames is handled by re-validation at connect time.
        host.endsWith(".local") || host.endsWith(".internal") || host.endsWith(".localhost") ->
            "local-domain blocked"
        // Bare (unbracketed) IPv6 with colons — treat everything as the host.
        host.count { it == ':' } >= 2 -> ipv6Blocked(host) ?: "ipv6 host blocked"
        else -> null
    }

    /** Parse a possibly-bracketed authority into its host. */
    private fun parseHost(authority: String): String? = when {
        authority.startsWith("[") -> authority.substringAfter("[").substringBefore("]")
        authority.count { it == ':' } >= 2 -> authority // bare IPv6
        else -> authority.substringBefore(":")
    }

    private fun ipv6Blocked(host: String): String? = when {
        host == "::" || host == "::1" -> "ipv6 loopback / unspecified blocked"
        host.startsWith("fc") || host.startsWith("fd") -> "unique-local ipv6 blocked"
        host.startsWith("fe8") || host.startsWith("fe9") ||
            host.startsWith("fea") || host.startsWith("feb") -> "link-local ipv6 blocked"
        host == "::" -> "unspecified ipv6 blocked"
        else -> null
    }
}

/**
 * The BrowserEngine contract. Backends (Lightpanda gateway, future local
 * PRoot Lightpanda binary, Android WebView fallback, tests' mock) implement
 * this and nothing else. The agent layer never sees backend types.
 */
interface BrowserEngine {
    val id: BrowserEngineId

    /** Create a session bound to [runId]. Must return an independent, clean context. */
    suspend fun openSession(runId: String): BrowserSession

    /** Execute one browser action inside [session]. Throws [kotlinx.coroutines.CancellationException] on run cancellation. */
    suspend fun execute(session: BrowserSession, action: BrowserAction): BrowserResult

    /** Destroy the session and all associated resources. Idempotent. */
    suspend fun close(session: BrowserSession)
}

/**
 * Browser session manager scoped to agent runs — enforces the
 * one-session-per-run rule and guarantees cleanup on any terminal state.
 */
class BrowserSessionManager(
    private val router: BrowserRouter,
) {
    private data class Entry(val session: BrowserSession, val engine: BrowserEngine)

    private val entries = mutableMapOf<String, Entry>()

    /** Open (or reuse) the engine session for [runId]. */
    suspend fun sessionFor(runId: String): BrowserSession {
        val existing = entries[runId]
        if (existing != null && existing.session.isActive) return existing.session
        val engine = router.defaultEngine()
        val session = engine.openSession(runId)
        entries[runId] = Entry(session, engine)
        return session
    }

    /** Execute [action] on the engine bound to [runId]'s session. */
    suspend fun run(runId: String, action: BrowserAction): BrowserResult {
        val entry = entries[runId]
        if (entry == null || !entry.session.isActive) return BrowserResult.Failed("browser session not open for run $runId", retryable = false)
        return entry.engine.execute(entry.session, action)
    }

    /** Call from the orchestrator for every terminal run state (complete/failed/cancelled). */
    suspend fun finishRun(runId: String) {
        val entry = entries.remove(runId) ?: return
        if (entry.session.isActive) {
            entry.engine.close(entry.session)
        }
    }

    fun activeSession(runId: String): BrowserSession? = entries[runId]?.session?.takeIf { it.isActive }
}

/**
 * Engine registry — resolves engines by id and picks the default. Backed by
 * DI registration at startup, never by static singletons.
 */
interface BrowserRouter {
    fun engineFor(id: BrowserEngineId): BrowserEngine?
    fun defaultEngine(): BrowserEngine
    fun register(engine: BrowserEngine)

    companion object {
        /** Hard cap on content handed to the LLM per read (bytes). */
        const val MAX_LLM_CONTENT_BYTES: Int = 16 * 1024
    }
}
