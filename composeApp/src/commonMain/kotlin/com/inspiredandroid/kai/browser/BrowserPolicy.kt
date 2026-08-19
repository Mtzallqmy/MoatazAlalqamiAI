package com.inspiredandroid.kai.browser

/**
 * Central browser policy — the ONLY place where SSRF, content-size and
 * action-level restrictions are enforced. Tool layer and backend must never
 * re-implement or bypass these checks.
 *
 * Designed as a pure function holder so it is fully unit-testable and so the
 * policy can be tightened without touching the engine or UI.
 */
object BrowserPolicy {
    /** Maximum navigation depth allowed for a back-then-open chain within one session (replay safety). */
    const val MAX_NAVIGATIONS_PER_RUN: Int = 60

    /** Maximum chars the LLM may receive from a single read. */
    const val MAX_LLM_CONTENT_CHARS: Int = 16 * 1024

    /** Maximum chars accepted in a type action (prevents prompt stuffing via values). */
    const val MAX_TYPE_CHARS: Int = 4_000

    /** Domains the user may explicitly allow-list when blocking mode is strict. Empty = use defaults. */
    var allowList: Set<String> = emptySet()

    /** Domains never allowed regardless of allow-listing (e.g. cloud metadata hostnames). */
    val DENY_LIST: Set<String> = setOf(
        "169.254.169.254",
        "metadata.google.internal",
        "169.254.170.2",
    )

    /**
     * Validate an open action BEFORE any engine call.
     * @return null when allowed, or a human-readable blocking reason.
     */
    fun validateOpen(action: BrowserAction.Open): String? {
        val url = action.url.trim()
        SsrfGuard.isBlocked(url)?.let { return it }
        val authority = url.substringAfter("://").substringBefore("/").substringAfterLast("@").lowercase()
        val host = if (authority.startsWith("[")) authority.substringAfter("[").substringBefore("]")
        else authority.substringBefore(":")
        if (host in DENY_LIST) return "host on browser deny-list"
        return null
    }

    /** Validate a click/type target — stable ids only. Rejects raw selectors as a prompt-injection guard. */
    fun validateTarget(targetId: String?): String? {
        if (targetId.isNullOrBlank()) return "missing target"
        if (targetId.contains(".") || targetId.contains("#") || targetId.contains("[") ||
            targetId.contains("(") || targetId.contains(" ")
        ) return "target must be a stable targetId, not a selector"
        return null
    }

    /** Guard typed content against stuffing and injection. */
    fun validateType(text: String): String? {
        if (text.length > MAX_TYPE_CHARS) return "type text exceeds ${MAX_TYPE_CHARS} chars"
        val sanitized = PromptInjectionFilter.sanitize(text)
        if (sanitized.isEmpty()) return "type text empty after sanitization"
        return null
    }

    /** Cap read content to LLM budget and run it through injection filtering. */
    fun capForLlm(content: String): String {
        val sanitized = PromptInjectionFilter.sanitize(content)
        return if (sanitized.length > MAX_LLM_CONTENT_CHARS) {
            sanitized.substring(0, MAX_LLM_CONTENT_CHARS) +
                "\n…(truncated to ${MAX_LLM_CONTENT_CHARS} chars by browser policy)"
        } else sanitized
    }

    /** Extract query for extract actions — no raw selectors, keyword-style only. */
    fun validateExtract(query: String?): String? {
        if (query == null) return null
        if (query.isBlank()) return "extract query empty"
        if (query.contains("document.") || query.contains("querySelector") || query.contains("$")) {
            return "extract must use keyword targeting, not script expressions"
        }
        return null
    }
}
