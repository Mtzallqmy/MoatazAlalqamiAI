package com.inspiredandroid.kai.gateway

/**
 * Per-model capability metadata. The gateway must never assume a capability
 * for an unknown model — every field is nullable and `unknown` is a valid
 * state. Providers / discovery populate what the API exposes; the user may
 * override anything through the settings UI; the curated overlay fills known
 * models via [ModelCapabilityCatalog].
 */
data class ModelCapability(
    val supportsText: Boolean? = null,
    val supportsVision: Boolean? = null,
    val supportsToolCalling: Boolean? = null,
    val supportsStructuredOutput: Boolean? = null,
    val supportsReasoning: Boolean? = null,
    val supportsAudio: Boolean? = null,
    val supportsEmbeddings: Boolean? = null,
    val isLocal: Boolean? = null, // on-device vs cloud
    /** Per-thousand-token pricing (USD). null = unknown — never fake it. */
    val inputPricePerMTok: Double? = null,
    val outputPricePerMTok: Double? = null,
    val cacheReadPricePerMTok: Double? = null,
    /** Rough latency heuristic: 1 = fastest … 5 = slowest. */
    val speedTier: Int? = null,
    /** Rough quality heuristic: 1 = basic … 5 = frontier. */
    val qualityTier: Int? = null,
    val tags: List<String> = emptyList(),
)

/**
 * Curated, hand-maintained capability overlay. Like `ModelCatalog` it only
 * fills gaps — provider-discovered values always win. Keys are lowercase
 * model ids and may repeat for alias ids.
 *
 * Pricing in USD per 1M tokens at time of entry. Verified against public
 * provider pricing pages; do not guess values for models not listed.
 */
internal object ModelCapabilityCatalog {

    private val entries: Map<String, ModelCapability> = mapOf(
        // -------------------------------------------------------------
        // Anthropic
        // -------------------------------------------------------------
        "claude-sonnet-4-5" to ModelCapability(
            supportsText = true, supportsVision = true, supportsToolCalling = true,
            supportsStructuredOutput = true, supportsReasoning = true,
            inputPricePerMTok = 3.0, outputPricePerMTok = 15.0, cacheReadPricePerMTok = 0.30,
            speedTier = 2, qualityTier = 5,
        ),
        "claude-opus-4-5" to ModelCapability(
            supportsText = true, supportsVision = true, supportsToolCalling = true,
            supportsStructuredOutput = true, supportsReasoning = true,
            inputPricePerMTok = 15.0, outputPricePerMTok = 75.0, cacheReadPricePerMTok = 1.50,
            speedTier = 3, qualityTier = 5,
        ),
        "claude-opus-4-1" to ModelCapability(
            supportsText = true, supportsVision = true, supportsToolCalling = true,
            supportsStructuredOutput = true, supportsReasoning = true,
            inputPricePerMTok = 15.0, outputPricePerMTok = 75.0, cacheReadPricePerMTok = 1.50,
            speedTier = 3, qualityTier = 5,
        ),
        "claude-sonnet-4" to ModelCapability(
            supportsText = true, supportsVision = true, supportsToolCalling = true,
            supportsStructuredOutput = true, supportsReasoning = true,
            inputPricePerMTok = 3.0, outputPricePerMTok = 15.0,
            speedTier = 2, qualityTier = 5,
        ),
        "claude-3-5-sonnet" to ModelCapability(
            supportsText = true, supportsVision = true, supportsToolCalling = true,
            supportsStructuredOutput = true,
            inputPricePerMTok = 3.0, outputPricePerMTok = 15.0,
            speedTier = 2, qualityTier = 4,
        ),
        "claude-3-5-haiku" to ModelCapability(
            supportsText = true, supportsVision = true, supportsToolCalling = true,
            supportsStructuredOutput = true,
            inputPricePerMTok = 0.80, outputPricePerMTok = 4.0,
            speedTier = 1, qualityTier = 3,
        ),

        // -------------------------------------------------------------
        // OpenAI
        // -------------------------------------------------------------
        "gpt-5" to ModelCapability(
            supportsText = true, supportsVision = true, supportsToolCalling = true,
            supportsStructuredOutput = true, supportsReasoning = true,
            inputPricePerMTok = 1.25, outputPricePerMTok = 10.0, cacheReadPricePerMTok = 0.3125,
            speedTier = 2, qualityTier = 5,
        ),
        "gpt-5-mini" to ModelCapability(
            supportsText = true, supportsVision = true, supportsToolCalling = true,
            supportsStructuredOutput = true, supportsReasoning = true,
            inputPricePerMTok = 0.25, outputPricePerMTok = 2.0,
            speedTier = 1, qualityTier = 3,
        ),
        "gpt-5-nano" to ModelCapability(
            supportsText = true, supportsVision = true, supportsToolCalling = true,
            supportsStructuredOutput = true, supportsReasoning = true,
            inputPricePerMTok = 0.05, outputPricePerMTok = 0.40,
            speedTier = 1, qualityTier = 2,
        ),
        "gpt-4.1" to ModelCapability(
            supportsText = true, supportsVision = true, supportsToolCalling = true,
            supportsStructuredOutput = true,
            inputPricePerMTok = 2.0, outputPricePerMTok = 8.0, cacheReadPricePerMTok = 0.50,
            speedTier = 2, qualityTier = 5,
        ),
        "gpt-4.1-mini" to ModelCapability(
            supportsText = true, supportsVision = true, supportsToolCalling = true,
            supportsStructuredOutput = true,
            inputPricePerMTok = 0.40, outputPricePerMTok = 1.60, cacheReadPricePerMTok = 0.10,
            speedTier = 1, qualityTier = 3,
        ),
        "gpt-4o" to ModelCapability(
            supportsText = true, supportsVision = true, supportsToolCalling = true,
            supportsStructuredOutput = true, supportsAudio = true,
            inputPricePerMTok = 2.50, outputPricePerMTok = 10.0,
            speedTier = 2, qualityTier = 4,
        ),
        "gpt-4o-mini" to ModelCapability(
            supportsText = true, supportsVision = true, supportsToolCalling = true,
            supportsStructuredOutput = true,
            inputPricePerMTok = 0.15, outputPricePerMTok = 0.60,
            speedTier = 1, qualityTier = 3,
        ),

        // -------------------------------------------------------------
        // Google Gemini
        // -------------------------------------------------------------
        "gemini-2.5-pro" to ModelCapability(
            supportsText = true, supportsVision = true, supportsToolCalling = true,
            supportsStructuredOutput = true, supportsReasoning = true,
            inputPricePerMTok = 1.25, outputPricePerMTok = 10.0, cacheReadPricePerMTok = 0.3125,
            speedTier = 2, qualityTier = 5,
        ),
        "gemini-2.5-flash" to ModelCapability(
            supportsText = true, supportsVision = true, supportsToolCalling = true,
            supportsStructuredOutput = true, supportsReasoning = true,
            inputPricePerMTok = 0.15, outputPricePerMTok = 0.60, cacheReadPricePerMTok = 0.0375,
            speedTier = 1, qualityTier = 4,
        ),
        "gemini-2.5-flash-lite" to ModelCapability(
            supportsText = true, supportsVision = true, supportsToolCalling = true,
            supportsStructuredOutput = true,
            inputPricePerMTok = 0.075, outputPricePerMTok = 0.30,
            speedTier = 1, qualityTier = 2,
        ),

        // -------------------------------------------------------------
        // Coding-focused
        // -------------------------------------------------------------
        "gpt-5-codex" to ModelCapability(
            supportsText = true, supportsToolCalling = true, supportsStructuredOutput = true,
            supportsReasoning = true,
            inputPricePerMTok = 1.25, outputPricePerMTok = 10.0,
            speedTier = 2, qualityTier = 5, tags = listOf("coding"),
        ),
        "deepseek-r1" to ModelCapability(
            supportsText = true, supportsToolCalling = true, supportsReasoning = true,
            inputPricePerMTok = 0.55, outputPricePerMTok = 2.19,
            speedTier = 3, qualityTier = 4, tags = listOf("reasoning"),
        ),
        "kimi-k2" to ModelCapability(
            supportsText = true, supportsToolCalling = true, supportsReasoning = true,
            inputPricePerMTok = 0.57, outputPricePerMTok = 2.30,
            speedTier = 3, qualityTier = 4, tags = listOf("coding", "reasoning"),
        ),
        "qwen3-coder" to ModelCapability(
            supportsText = true, supportsToolCalling = true, supportsReasoning = true,
            inputPricePerMTok = 0.15, outputPricePerMTok = 0.60,
            speedTier = 2, qualityTier = 3, tags = listOf("coding"),
        ),

        // -------------------------------------------------------------
        // Fast / economy
        // -------------------------------------------------------------
        "gpt-oss-20b" to ModelCapability(
            supportsText = true, supportsToolCalling = false,
            inputPricePerMTok = 0.10, outputPricePerMTok = 0.10,
            speedTier = 1, qualityTier = 2, tags = listOf("fast", "economy"),
        ),
        "gpt-oss-120b" to ModelCapability(
            supportsText = true, supportsToolCalling = true,
            inputPricePerMTok = 0.15, outputPricePerMTok = 0.60,
            speedTier = 2, qualityTier = 3, tags = listOf("fast"),
        ),

        // -------------------------------------------------------------
        // Local (LiteRT) — no cloud cost
        // -------------------------------------------------------------
        "llama-3.1-8b" to ModelCapability(
            supportsText = true, supportsToolCalling = true, isLocal = true,
            inputPricePerMTok = 0.0, outputPricePerMTok = 0.0,
            speedTier = 1, qualityTier = 2, tags = listOf("local"),
        ),
        "qwen2.5-7b" to ModelCapability(
            supportsText = true, supportsToolCalling = true, isLocal = true,
            inputPricePerMTok = 0.0, outputPricePerMTok = 0.0,
            speedTier = 1, qualityTier = 2, tags = listOf("local"),
        ),
    )

    /** Capability overlay for a model id, or null when nothing is curated. */
    fun lookup(modelId: String): ModelCapability? =
        entries[modelId.lowercase()]
            ?: entries[stripModelVersionSuffix(modelId.lowercase())]

    private fun stripModelVersionSuffix(id: String): String {
        // "gpt-4o-2024-08-06" → "gpt-4o"
        val dateLike = Regex("^(.+?)-20\\d{2}-\\d{2}(?:-\\d{2})?\$")
        return dateLike.matchEntire(id)?.groupValues?.get(1) ?: id
    }
}
