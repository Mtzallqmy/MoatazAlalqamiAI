package com.inspiredandroid.kai.gateway

/**
 * Declarative, data-only description of an AI provider: **Protocol != Provider**.
 *
 * A [ProviderDefinition] declares *what* a provider offers (base URL, auth
 * scheme, discovery endpoint, capabilities) while [Protocols] describes *how*
 * to speak with it. Adding a new provider that uses an existing protocol
 * requires no new implementation code — only a new definition.
 *
 * Definitions may arrive from the signed remote config (`remote-config/` in
 * the repository), so this class intentionally contains no behavior:
 * deserialization, validation, and rendering are done by the hot-update layer.
 *
 * @property id stable lowercase id, used as settings prefix and in logs
 * @property displayName human-readable name shown in the UI
 * @property protocolId one of the protocol ids registered in [Protocols]
 * @property baseUrl default base URL (may be overridden per user in settings)
 * @property modelsUrl optional models-discovery path appended to the base URL
 * @property modelsResponseIsArray true when the discovery endpoint returns a
 *   bare JSON array (`[ {...} ]`) instead of `{ "data": [ {...} ] }`
 * @property authScheme how the API key is transmitted
 * @property supportsImages whether multimodal image parts are accepted
 * @property supportsPdf whether PDF document parts are accepted
 * @property reasoningMode how `reasoning_content` is handled on requests
 * @property capabilities free-form capability tags consumed by the router
 */
data class ProviderDefinition(
    val id: String,
    val displayName: String,
    val protocolId: String = "openai_chat",
    val baseUrl: String = "",
    val modelsUrl: String? = null,
    val modelsResponseIsArray: Boolean = false,
    val authScheme: AuthScheme = AuthScheme.BearerHeader,
    val supportsImages: Boolean = true,
    val supportsPdf: Boolean = false,
    val reasoningMode: ReasoningMode = ReasoningMode.None,
    val capabilities: Set<String> = emptySet(),
) {
    init {
        require(id.isNotBlank()) { "ProviderDefinition.id must not be blank" }
        require(id == id.lowercase()) { "ProviderDefinition.id must be lowercase: $id" }
        require(id.length <= 64) { "ProviderDefinition.id too long: $id" }
        require(displayName.isNotBlank()) { "ProviderDefinition.displayName must not be blank" }
        require(baseUrl == "" || isHttpsOrSafe(baseUrl)) {
            "ProviderDefinition.baseUrl must be empty or HTTPS-scheme: $baseUrl"
        }
    }

    fun withProtocol(newProtocolId: String): ProviderDefinition = copy(protocolId = newProtocolId)
    fun withBaseUrl(newBaseUrl: String): ProviderDefinition = copy(baseUrl = newBaseUrl)
}

enum class AuthScheme {
    /** `Authorization: Bearer <key>` — OpenAI-compatible default. */
    BearerHeader,
    /** `x-api-key: <key>` — Anthropic-style. */
    XApiKeyHeader,
    /** `api_key=<key>` query parameter — Gemini-style. */
    QueryParam,
    /** No authentication (local / anonymous endpoints). */
    None,
}

enum class ReasoningMode {
    None,
    ReasoningContent,
}

/**
 * Registry of all providers: built-in first, remote (signed hot-update)
 * definitions merge on top. Remote definitions may extend but never silently
 * override a built-in provider with the same id.
 */
object ProviderRegistry {

    /** Builtin providers translated from the legacy [com.inspiredandroid.kai.data.Service] catalog. */
    val builtins: List<ProviderDefinition> = listOf(
        ProviderDefinition("atlascloud", "Atlas Cloud", baseUrl = "https://api.atlascloud.ai/v1"),
        ProviderDefinition("groq", "GroqCloud", baseUrl = "https://api.groq.com/openai/v1"),
        ProviderDefinition("xai", "xAI", baseUrl = "https://api.x.ai/v1"),
        ProviderDefinition(
            "openrouter", "OpenRouter", baseUrl = "https://openrouter.ai/api/v1",
            supportsPdf = true, reasoningMode = ReasoningMode.ReasoningContent,
        ),
        ProviderDefinition("nvidia", "NVIDIA", baseUrl = "https://integrate.api.nvidia.com/v1"),
        ProviderDefinition("gemini", "Gemini", baseUrl = "https://generativelanguage.googleapis.com/v1beta", supportsPdf = true, authScheme = AuthScheme.QueryParam),
        ProviderDefinition(
            "anthropic", "Anthropic", protocolId = "anthropic_messages",
            baseUrl = "https://api.anthropic.com/v1", supportsPdf = true, authScheme = AuthScheme.XApiKeyHeader,
        ),
        ProviderDefinition("openai", "OpenAI", baseUrl = "https://api.openai.com/v1", supportsPdf = true),
        ProviderDefinition("deepseek", "DeepSeek", baseUrl = "https://api.deepseek.com", reasoningMode = ReasoningMode.ReasoningContent),
        ProviderDefinition("mistral", "Mistral", baseUrl = "https://api.mistral.ai/v1"),
        ProviderDefinition("cerebras", "Cerebras", baseUrl = "https://api.cerebras.ai/v1"),
        ProviderDefinition("ollamacloud", "Ollama Cloud", baseUrl = "https://ollama.com/v1"),
        ProviderDefinition(
            "longcat", "LongCat", baseUrl = "https://api.longcat.chat/openai/v1",
            reasoningMode = ReasoningMode.ReasoningContent,
        ),
        ProviderDefinition("together", "Together AI", baseUrl = "https://api.together.xyz/v1", modelsResponseIsArray = true),
        ProviderDefinition("huggingface", "Hugging Face", baseUrl = "https://router.huggingface.co/v1"),
        ProviderDefinition(
            "venice", "Venice AI", baseUrl = "https://api.venice.ai/api/v1",
            reasoningMode = ReasoningMode.ReasoningContent,
        ),
        ProviderDefinition(
            "moonshot", "Moonshot AI", baseUrl = "https://api.moonshot.cn/v1",
            reasoningMode = ReasoningMode.ReasoningContent,
        ),
        ProviderDefinition(
            "zai", "Z.AI", baseUrl = "https://api.z.ai/api/paas/v4",
            reasoningMode = ReasoningMode.ReasoningContent,
        ),
        ProviderDefinition(
            "zai-coding-plan", "Z.AI Coding Plan", baseUrl = "https://api.z.ai/api/coding/paas/v4",
            reasoningMode = ReasoningMode.ReasoningContent,
        ),
        ProviderDefinition(
            "minimax", "MiniMax", baseUrl = "https://api.minimax.io/v1",
            reasoningMode = ReasoningMode.ReasoningContent,
        ),
        ProviderDefinition("aihubmix", "AIHubMix", baseUrl = "https://aihubmix.com/v1"),
        ProviderDefinition("deepinfra", "Deep Infra", baseUrl = "https://api.deepinfra.com/v1/openai"),
        ProviderDefinition(
            "fireworksai", "Fireworks AI", baseUrl = "https://api.fireworks.ai/inference/v1",
            reasoningMode = ReasoningMode.ReasoningContent,
        ),
        ProviderDefinition(
            "opencode", "OpenCode", baseUrl = "https://opencode.ai/zen/v1",
            reasoningMode = ReasoningMode.ReasoningContent,
        ),
        ProviderDefinition("publicai", "Public AI", baseUrl = "https://api.publicai.co/v1"),
        ProviderDefinition("aihorde", "AI Horde", baseUrl = "https://oai.aihorde.net/v1"),
        ProviderDefinition(
            "perplexity", "Perplexity", baseUrl = "https://api.perplexity.ai",
            capabilities = setOf("static_model_list"),
        ),
        ProviderDefinition("openai-compatible", "OpenAI-Compatible API", authScheme = AuthScheme.BearerHeader),
        ProviderDefinition("litert", "Local Model", protocolId = "litert_local", authScheme = AuthScheme.None),
    )

    /** Providers loaded from the signed remote config; empty until hot-update runs. */
    var remote: List<ProviderDefinition> = emptyList()
        private set

    fun has(id: String): Boolean = all.any { it.id == id }

    fun get(id: String): ProviderDefinition? = all.find { it.id == id }

    /** Latest-known catalog: builtins first, then remote additions. */
    val all: List<ProviderDefinition>
        get() = builtins + remote.filterNot { builtins.any { b -> b.id == it.id } }

    /**
     * Replace the remote layer with a newly verified catalog. Invalid ids are
     * dropped (never injected into the registry), and builtins always win on
     * id collision.
     */
    fun applyRemoteCatalog(definitions: List<ProviderDefinition>) {
        remote = definitions.filter { it.id.isNotBlank() && it.id == it.id.lowercase() }
    }

    /**
     * Parse a catalog that may contain hostile or malformed rows. Invalid rows
     * are dropped instead of throwing, so one bad entry cannot poison the
     * registry or crash the caller.
     */
    fun applyRemoteCatalogFromMaps(rows: List<Map<String, String>>) {
        remote = rows.mapNotNull { map ->
            runCatching {
                ProviderDefinition(
                    id = map["id"] ?: "",
                    displayName = map["displayName"] ?: "",
                    protocolId = map.getOrDefault("protocolId", "openai_chat"),
                    baseUrl = map.getOrDefault("baseUrl", ""),
                    modelsResponseIsArray = map.getOrDefault("modelsResponseIsArray", "false") == "true",
                    authScheme = runCatching { AuthScheme.valueOf(map.getOrDefault("authScheme", "BearerHeader")) }.getOrDefault(AuthScheme.BearerHeader),
                    supportsImages = map.getOrDefault("supportsImages", "true") == "true",
                    supportsPdf = map.getOrDefault("supportsPdf", "false") == "true",
                    reasoningMode = runCatching { ReasoningMode.valueOf(map.getOrDefault("reasoningMode", "None")) }.getOrDefault(ReasoningMode.None),
                )
            }.getOrNull()
        }
    }
}

private fun isHttpsOrSafe(url: String): Boolean {
    val trimmed = url.trim().lowercase()
    if (trimmed.startsWith("https://")) return true
    // Local loopback addresses are safe for self-hosted endpoints (e.g. Ollama).
    if (trimmed.startsWith("http://localhost") || trimmed.startsWith("http://127.0.0.1")) return true
    return false
}
