package com.inspiredandroid.kai.hotupdate

import com.inspiredandroid.kai.gateway.AuthScheme
import com.inspiredandroid.kai.gateway.ProviderDefinition
import com.inspiredandroid.kai.gateway.ProviderRegistry
import com.inspiredandroid.kai.gateway.ReasoningMode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement

/**
 * PHASE 10: remote-deliverable provider & model catalogs. Signed via the
 * Ed25519 manifest envelope already in place (`RemoteManifestVerifier`), so a
 * malicious catalog can never be injected — an unverifiable document is
 * discarded and the baked-in registry stays in force.
 *
 * Example catalog document (embedded inside a signed manifest):
 * ```json
 * {
 *   "catalog_version": 1,
 *   "min_app_version": "3.9.0",
 *   "providers": [
 *     {
 *       "id": "my-provider",
 *       "displayName": "My Provider",
 *       "baseUrl": "https://api.my-provider.example.com/v1",
 *       "protocolId": "openai_chat",
 *       "supportsImages": true
 *     }
 *   ],
 *   "model_catalog": [
 *     {
 *       "providerId": "my-provider",
 *       "id": "fast-model",
 *       "displayName": "Fast Model",
 *       "qualityTier": 3, "speedTier": 2,
 *       "tags": ["coding"], "supportsVision": true, "isLocal": false
 *     }
 *   ]
 * }
 * ```
 */

@Serializable
data class RemoteProviderCatalogEntry(
    val id: String,
    val displayName: String,
    val baseUrl: String = "",
    val protocolId: String = "openai_chat",
    val authScheme: String = "BearerHeader",
    val supportsImages: Boolean = true,
    val supportsPdf: Boolean = false,
    val reasoningMode: String = "None",
)

/**
 * A remotely-deliverable model-row. The model *metadata* is trusted only as
 * display/sorting hints — actual routing still applies hard capability gates
 * via `ModelCapabilityCatalog` and the router's constraint filters.
 */
@Serializable
data class RemoteModelCatalogEntry(
    val providerId: String,
    val id: String,
    val displayName: String? = null,
    val qualityTier: Int? = null,
    val speedTier: Int? = null,
    val inputPricePerMTok: Double? = null,
    val outputPricePerMTok: Double? = null,
    val tags: List<String> = emptyList(),
    val supportsVision: Boolean = false,
    val isLocal: Boolean = false,
    val contextLimit: Int? = null,
    val arabicQuality: Boolean = false,
)

/** Full remote catalog document. */
@Serializable
data class RemoteCatalog(
    val catalog_version: Long = 1,
    val min_app_version: String? = null,
    val providers: List<RemoteProviderCatalogEntry> = emptyList(),
    val model_catalog: List<RemoteModelCatalogEntry> = emptyList(),
) {
    fun validated(minSemver: SemVer? = null): Result<RemoteCatalog> = runCatching {
        val identifier = Regex("^[A-Za-z0-9._-]{3,64}\$")
        val versionGate = minSemver != null && min_app_version != null &&
            minSemver < SemVer.parse(min_app_version)
        if (versionGate) return Result.success(RemoteCatalog(catalog_version = catalog_version, min_app_version = min_app_version))

        val legalProviders = providers.filter {
            identifier.matches(it.id) && it.displayName.isNotBlank() &&
                isSafeUrl(it.baseUrl) && it.protocolId in ALLOWED_PROTOCOLS &&
                it.authScheme in ALLOWED_AUTH_SCHEMES && it.reasoningMode in ALLOWED_REASONING_MODES &&
                it.tagsAllSane()
        }

        val legalModels = model_catalog.filter {
            identifier.matches(it.providerId) && identifier.matches(it.id) &&
                it.tags.all { tag -> Regex("^[A-Za-z0-9_-]{2,40}\$").matches(tag) } &&
                safeRange(it.qualityTier) && safeRange(it.speedTier) && safeContext(it.contextLimit) &&
                safePrice(it.inputPricePerMTok) && safePrice(it.outputPricePerMTok)
        }
        copy(providers = legalProviders, model_catalog = legalModels)
    }
}

private fun RemoteProviderCatalogEntry.tagsAllSane(): Boolean =
    supportsImages || !supportsImages && true

private fun safeRange(value: Int?): Boolean = value == null || value in 1..10

private fun safeContext(value: Int?): Boolean = value == null || value in 1024..2_000_000

private fun safePrice(value: Double?): Boolean = value == null || value in 0.0..1_000.0

/** Only HTTPS and local loopback URLs may arrive in a remote catalog. */
private fun isSafeUrl(url: String): Boolean {
    if (url.isBlank()) return true
    val lower = url.trim().lowercase()
    if (lower.startsWith("https://")) return true
    if (lower.startsWith("http://localhost") || lower.startsWith("http://127.0.0.1")) return true
    return false
}

/**
 * Apply a verified remote catalog on top of the baked-in registry. Built-in
 * providers always win on id collision (defense in depth — the registry
 * itself enforces the same rule), and malformed entries have already been
 * stripped by [RemoteCatalog.validated].
 */
fun RemoteCatalog.applyToRegistry() {
    if (providers.isEmpty()) return
    ProviderRegistry.applyRemoteCatalog(
        providers.map { entry ->
            ProviderDefinition(
                id = entry.id,
                displayName = entry.displayName,
                protocolId = entry.protocolId,
                baseUrl = entry.baseUrl,
                authScheme = parseAuthScheme(entry.authScheme),
                supportsImages = entry.supportsImages,
                supportsPdf = entry.supportsPdf,
                reasoningMode = parseReasoningMode(entry.reasoningMode),
            )
        },
    )
}

private fun parseAuthScheme(raw: String): AuthScheme =
    runCatching { AuthScheme.valueOf(raw) }.getOrDefault(AuthScheme.BearerHeader)

private fun parseReasoningMode(raw: String): ReasoningMode =
    runCatching { ReasoningMode.valueOf(raw) }.getOrDefault(ReasoningMode.None)

private val ALLOWED_PROTOCOLS = setOf(
    "openai_chat", "openai_responses", "anthropic_messages",
    "gemini_native", "ollama", "litert_local",
)

private val ALLOWED_AUTH_SCHEMES = setOf("BearerHeader", "XApiKeyHeader", "QueryParam", "None")

private val ALLOWED_REASONING_MODES = setOf("None", "ReasoningContent")
