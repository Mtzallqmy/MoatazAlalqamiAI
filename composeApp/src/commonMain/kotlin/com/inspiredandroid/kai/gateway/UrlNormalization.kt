package com.inspiredandroid.kai.gateway

/**
 * Protocol-aware base-URL and endpoint normalization for the AI Gateway.
 *
 * Users type their base URL in many forms:
 *   https://server.example.com
 *   https://server.example.com/
 *   https://server.example.com/v1
 *   https://server.example.com/v1/
 *
 * Each protocol adapter is responsible for its own endpoint paths. This layer
 * never attaches `/v1`, `/models` or `/chat/completions` blindly — the adapter
 * owns path resolution via [ProtocolAdapter.chatPath] / [modelsPath].
 *
 * Key guarantees:
 * - `/v1` is never appended twice.
 * - Trailing slashes are always stripped from the normalized base.
 * - A user-provided versioned base (e.g. `https://x.com/openai/v1`) is kept
 *   as-is; only relative paths are joined to it.
 * - Custom endpoints (e.g. `.../v1/responses`) that already contain path
 *   segments beyond the version are preserved.
 */
object UrlNormalization {

    fun normalizeBaseUrl(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        return trimmed
    }

    /** Ensures a version path exists exactly once. Pass the desired segment, e.g. "v1". */
    fun ensureVersionPath(baseUrl: String, version: String = "v1"): String {
        val base = normalizeBaseUrl(baseUrl)
        if (base.isBlank()) return ""
        if (Regex("/v\\d+$").containsMatchIn(base) || Regex("/$version\$").containsMatchIn(base)) return base
        // If the base already contains a slash after the host (custom sub-path), keep it.
        return "$base/$version"
    }

    fun joinPath(baseUrl: String, path: String): String {
        val base = normalizeBaseUrl(baseUrl)
        val cleanPath = path.trim().trimStart('/')
        return if (base.isEmpty()) cleanPath else "$base/$cleanPath"
    }
}

/**
 * Protocol adapter contract. A protocol knows how to build chat / streaming /
 * model-discovery endpoints from a normalized base URL and can validate it.
 */
interface ProtocolAdapter {
    val protocolId: String
    fun chatPath(baseUrl: String): String
    fun streamingChatPath(baseUrl: String): String = chatPath(baseUrl)
    fun modelsPath(baseUrl: String): String?
    fun validate(baseUrl: String): Boolean
}

/**
 * Built-in protocol adapters covering the protocols the gateway speaks natively.
 */
object Protocols {
    val OpenAIChatCompletions = object : ProtocolAdapter {
        override val protocolId = "openai_chat"
        override fun chatPath(baseUrl: String) = UrlNormalization.joinPath(baseUrl, "chat/completions")
        override fun streamingChatPath(baseUrl: String) = UrlNormalization.joinPath(baseUrl, "chat/completions")
        override fun modelsPath(baseUrl: String) = UrlNormalization.joinPath(baseUrl, "models")
        override fun validate(baseUrl: String) = true
    }

    val OpenAIResponses = object : ProtocolAdapter {
        override val protocolId = "openai_responses"
        override fun chatPath(baseUrl: String) = UrlNormalization.joinPath(baseUrl, "responses")
        override fun streamingChatPath(baseUrl: String) = UrlNormalization.joinPath(baseUrl, "responses")
        override fun modelsPath(baseUrl: String) = UrlNormalization.joinPath(baseUrl, "models")
        override fun validate(baseUrl: String) = true
    }

    val AnthropicMessages = object : ProtocolAdapter {
        override val protocolId = "anthropic_messages"
        override fun chatPath(baseUrl: String) = UrlNormalization.joinPath(baseUrl, "messages")
        override fun streamingChatPath(baseUrl: String) = UrlNormalization.joinPath(baseUrl, "messages")
        // Anthropic has no public /models endpoint; catalog is static metadata.
        override fun modelsPath(baseUrl: String) = null
        override fun validate(baseUrl: String) = true
    }

    val GeminiNative = object : ProtocolAdapter {
        override val protocolId = "gemini_native"
        override fun chatPath(baseUrl: String): String = baseUrl // Gemini URLs are template-resolved per model.
        override fun streamingChatPath(baseUrl: String) = chatPath(baseUrl)
        override fun modelsPath(baseUrl: String) = UrlNormalization.joinPath(baseUrl, "models")
        override fun validate(baseUrl: String) = true
    }

    val Ollama = object : ProtocolAdapter {
        override val protocolId = "ollama"
        override fun chatPath(baseUrl: String) = UrlNormalization.joinPath(baseUrl, "api/chat")
        override fun streamingChatPath(baseUrl: String) = UrlNormalization.joinPath(baseUrl, "api/chat")
        override fun modelsPath(baseUrl: String) = UrlNormalization.joinPath(baseUrl, "api/tags")
        override fun validate(baseUrl: String) = true
    }

    val LiteRt = object : ProtocolAdapter {
        override val protocolId = "litert_local"
        override fun chatPath(baseUrl: String): String = "" // On-device; no HTTP endpoint.
        override fun modelsPath(baseUrl: String) = null
        override fun validate(baseUrl: String) = true
    }

    fun byId(id: String): ProtocolAdapter =
        when (id) {
            "openai_chat" -> OpenAIChatCompletions
            "openai_responses" -> OpenAIResponses
            "anthropic_messages" -> AnthropicMessages
            "gemini_native" -> GeminiNative
            "ollama" -> Ollama
            "litert_local" -> LiteRt
            else -> OpenAIChatCompletions
        }
}
