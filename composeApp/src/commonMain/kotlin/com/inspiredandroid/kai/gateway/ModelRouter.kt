package com.inspiredandroid.kai.gateway

import com.inspiredandroid.kai.data.AppSettings
import com.russhwolf.settings.Settings
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * One of the built-in or custom routing profiles (section 7 of the prompt).
 * Each profile defines preference weights and hard constraints; the router
 * scores every available model against them.
 */
enum class RoutingProfileId {
    Balanced,
    MaximumQuality,
    Fast,
    Economy,
    Coding,
    Reasoning,
    Research,
    Vision,
    LocalFirst,
    PrivacyLocalOnly,
    Custom,
}

/**
 * User-editable profile configuration. Stored as JSON under one settings key;
 * fields left null inherit the built-in profile defaults.
 */
@Serializable
data class RoutingProfileConfig(
    val profileId: RoutingProfileId = RoutingProfileId.Balanced,
    val plannerModelId: String? = null,
    val codingModelId: String? = null,
    val researchModelId: String? = null,
    val visionModelId: String? = null,
    val fastModelId: String? = null,
    val summarizationModelId: String? = null,
    val memoryModelId: String? = null,
    val allowedProviderIds: List<String> = emptyList(),
    val blockedProviderIds: List<String> = emptyList(),
    val cloudAllowed: Boolean = true,
    val localPreferred: Boolean = false,
    val maxCostPerRunUsd: Double? = null,
    val maxLatencyMs: Long? = null,
    val fallbackChain: List<String> = emptyList(),
)

/**
 * A candidate model scored for a routing request. Higher score is better.
 */
data class ModelCandidate(
    val modelId: String,
    val providerInstanceId: String,
    val profileId: RoutingProfileId,
    val score: Double,
    val isLocal: Boolean = false,
    val costUsd: Double = 0.0,
    val rejectionReasons: List<String> = emptyList(),
)

/**
 * The full routing decision — not just a single winner. Consumers get the
 * selected primary candidate, the ordered fallback chain (highest to lowest
 * score after the primary), and diagnostic warnings/rejection reasons so
 * failures can be surfaced to the user or the health registry.
 */
data class RoutingDecision(
    val taskType: TaskType,
    val profileId: RoutingProfileId,
    val primary: ModelCandidate?,
    val fallbackChain: List<ModelCandidate>,
    val estimatedCostUsd: Double = 0.0,
    val warnings: List<String> = emptyList(),
)

/**
 * Smart model router. Pure, deterministic, no network calls.
 *
 * Selection pipeline:
 * 1. Expand the candidate set from all configured service instances.
 * 2. Apply hard constraints (capabilities, local/cloud, allow/block lists,
 *    health, budget).
 * 3. Score surviving candidates against profile weights.
 * 4. Order the primary + fallback chain from highest to lowest score.
 */
class ModelRouter(
    settings: AppSettings,
    private val health: ProviderHealthRegistry,
) {
    private val settings: Settings = settings.settings

    /** All candidates for a request, ordered from best to worst (includes the primary). */
    fun selectAllCandidates(
        taskType: TaskType,
        hasVisionInput: Boolean,
        requiresTools: Boolean,
        contextTokens: Int,
        profileId: RoutingProfileId = currentProfile().profileId,
        configuredInstances: List<String>,
        instanceServiceIds: Map<String, String>,
    ): RoutingDecision {
        val config = currentProfile()
        val capabilities = buildCapabilitiesFilter(taskType, hasVisionInput, requiresTools)

        val candidates = configuredInstances.mapNotNull { instanceId ->
            scoreInstance(
                instanceId = instanceId,
                serviceId = instanceServiceIds[instanceId] ?: return@mapNotNull null,
                taskType = taskType,
                capabilities = capabilities,
                contextTokens = contextTokens,
                config = config,
            )
        }

        // Hard rejections (block lists, capability gaps, budget, local-only, etc.)
        // are never silently downgraded — they must be excluded unless every
        // candidate is rejected, in which case we surface the best of the
        // remaining pool so the caller can report the failure reason.
        // "unhealthy" is the only transient rejection — everything else
        // (block lists, capability gaps, budget, local-only) is hard and
        // permanently excludes the candidate from selection, regardless of score.
        val pool = candidates.filter { it.rejectionReasons.none { r -> r != "unhealthy" } }
        val ordered = pool.sortedByDescending { it.score }
        val poolSet = pool.toSet()

        val warnings = buildList {
            val rejectedHard = candidates.filter { it !in poolSet }
            if (rejectedHard.isNotEmpty()) {
                val reasons = rejectedHard.flatMap { it.rejectionReasons }.toSet()
                if (reasons.any { it in HARD_REJECTION_REASONS }) add("hard_rejections: $reasons")
            }
            val unhealthy = candidates.count { "unhealthy" in it.rejectionReasons }
            if (unhealthy > 0) add("$unhealthy instance(s) skipped for health reasons")
            if (ordered.isEmpty()) add("no eligible model found — routing will fail")
        }

        val primary = ordered.firstOrNull()
        return RoutingDecision(
            taskType = taskType,
            profileId = config.profileId,
            primary = primary,
            fallbackChain = if (primary != null) ordered.drop(1) else ordered,
            estimatedCostUsd = primary?.costUsd ?: 0.0,
            warnings = warnings,
        )
    }

    /** Convenience: the single best model (or null) for callers that only need one. */
    fun selectModel(
        taskType: TaskType,
        hasVisionInput: Boolean,
        requiresTools: Boolean,
        contextTokens: Int,
        profileId: RoutingProfileId = currentProfile().profileId,
        configuredInstances: List<String>,
        instanceServiceIds: Map<String, String>,
    ): ModelCandidate? =
        selectAllCandidates(taskType, hasVisionInput, requiresTools, contextTokens,
            profileId, configuredInstances, instanceServiceIds).primary

    private fun scoreInstance(
        instanceId: String,
        serviceId: String,
        taskType: TaskType,
        capabilities: Set<String>,
        contextTokens: Int,
        config: RoutingProfileConfig,
    ): ModelCandidate? {
        val rejection = mutableListOf<String>()
        var score = 0.0

        val configForService = resolveConfigForService(config, serviceId)
        val explicit = modelForTaskType(configForService, taskType)

        // Explicit per-task model wins outright.
        if (explicit != null) {
            return ModelCandidate(
                modelId = explicit,
                providerInstanceId = instanceId,
                profileId = config.profileId,
                score = 100.0,
            )
        }

        val curated = ModelCapabilityCatalog.lookup(modelForService(serviceId))

        // --- Hard constraints ---
        if (capabilities.contains("vision") && curated?.supportsVision != true) {
            rejection += "no_vision"
        }
        if (capabilities.contains("tools") && curated?.supportsToolCalling != true) {
            rejection += "no_tools"
        }
        if (curated?.isLocal != true && config.profileId == RoutingProfileId.PrivacyLocalOnly) {
            rejection += "cloud_blocked"
        }
        if (instanceId in config.blockedProviderIds) rejection += "blocked"
        if (config.allowedProviderIds.isNotEmpty() && instanceId !in config.allowedProviderIds) {
            rejection += "not_allowed"
        }
        if (health.isUnhealthy(instanceId)) rejection += "unhealthy"

        val estimatedCost = estimateCost(curated, contextTokens)
        if (config.maxCostPerRunUsd != null && estimatedCost > config.maxCostPerRunUsd) {
            rejection += "over_budget"
        }

        // --- Score components ---
        val quality = curated?.qualityTier ?: 3
        val speed = 6 - (curated?.speedTier ?: 3) // invert: tier 1 fastest → 5 points
        val costBonus = if (estimatedCost == 0.0) 2.0 else -estimatedCost

        score += when (config.profileId) {
            RoutingProfileId.MaximumQuality -> quality * 4.0 + costBonus
            RoutingProfileId.Fast -> speed * 3.0 + quality * 1.0
            RoutingProfileId.Economy -> costBonus * 5 + speed * 2.0
            RoutingProfileId.Coding -> if ("coding" in (curated?.tags ?: emptyList())) 8.0 else quality * 2.5
            RoutingProfileId.Reasoning -> if ("reasoning" in (curated?.tags ?: emptyList())) 8.0 else quality * 2.0
            RoutingProfileId.Research -> quality * 2.0 + speed * 1.5
            RoutingProfileId.Vision -> if (curated?.supportsVision == true) 6.0 else -10.0
            RoutingProfileId.LocalFirst -> if (curated?.isLocal == true) 10.0 else quality * 2.0
            RoutingProfileId.PrivacyLocalOnly -> if (curated?.isLocal == true) 20.0 else -50.0
            RoutingProfileId.Custom -> quality * 2.0 + speed + costBonus
            RoutingProfileId.Balanced -> quality * 2.0 + speed + costBonus
        }

        return ModelCandidate(
            modelId = modelForService(serviceId),
            providerInstanceId = instanceId,
            profileId = config.profileId,
            score = score,
            isLocal = curated?.isLocal == true,
            costUsd = estimatedCost,
            rejectionReasons = rejection,
        )
    }

    private fun modelForService(serviceId: String): String {
        val service = com.inspiredandroid.kai.data.Service.fromId(serviceId)
        return service?.defaultModel ?: ""
    }

    private fun resolveConfigForService(
        config: RoutingProfileConfig,
        serviceId: String,
    ): RoutingProfileConfig = config

    private fun modelForTaskType(config: RoutingProfileConfig, taskType: TaskType): String? = when (taskType) {
        TaskType.Planning -> config.plannerModelId
        TaskType.Coding -> config.codingModelId
        TaskType.Research -> config.researchModelId
        TaskType.Vision -> config.visionModelId
        TaskType.FastAnswer -> config.fastModelId
        TaskType.Summarization -> config.summarizationModelId
        TaskType.Reasoning -> null
        TaskType.Chat -> null
    }

    private fun buildCapabilitiesFilter(
        taskType: TaskType,
        hasVisionInput: Boolean,
        requiresTools: Boolean,
    ): Set<String> = buildSet {
        if (hasVisionInput || taskType == TaskType.Vision) add("vision")
        if (requiresTools) add("tools")
    }

    private fun estimateCost(curated: ModelCapability?, contextTokens: Int): Double {
        if (curated == null) return 0.0
        val input = (curated.inputPricePerMTok ?: 0.0) * contextTokens / 1_000_000.0
        val output = (curated.outputPricePerMTok ?: 0.0) * (contextTokens / 4) / 1_000_000.0
        return input + output
    }

    // ------------------------------------------------------------------
    // Profile persistence
    // ------------------------------------------------------------------

    fun currentProfile(): RoutingProfileConfig {
        val raw = try { settings.getStringOrNull(KEY_ROUTING_PROFILE) } catch (_: Exception) { null }
        return if (!raw.isNullOrBlank()) runCatching { Json.decodeFromString<RoutingProfileConfig>(raw) }.getOrNull()
            ?: RoutingProfileConfig()
        else RoutingProfileConfig()
    }

    fun saveProfile(config: RoutingProfileConfig) {
        try {
            settings.putString(KEY_ROUTING_PROFILE, Json.encodeToString(config))
        } catch (_: Exception) {
            // Save failure must never break chat flow.
        }
    }

    fun profileFor(projectId: String?): RoutingProfileConfig {
        if (projectId == null) return currentProfile()
        val raw = try { settings.getStringOrNull("${KEY_PROJECT_ROUTING_PREFIX}$projectId") } catch (_: Exception) { null }
        return if (!raw.isNullOrBlank()) runCatching { Json.decodeFromString<RoutingProfileConfig>(raw) }.getOrNull()
            ?: currentProfile()
        else currentProfile()
    }

    companion object {
        private const val KEY_ROUTING_PROFILE = "routing_profile_config"
        private const val KEY_PROJECT_ROUTING_PREFIX = "project_routing_profile_"
        private val HARD_REJECTION_REASONS = setOf(
            "no_vision", "no_tools", "cloud_blocked", "blocked", "not_allowed", "over_budget",
        )
    }
}
