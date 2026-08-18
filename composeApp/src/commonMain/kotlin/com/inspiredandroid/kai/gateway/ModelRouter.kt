package com.inspiredandroid.kai.gateway

import com.inspiredandroid.kai.data.AppSettings
import com.russhwolf.settings.Settings
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Task type inferred by the [TaskClassifier]. Routing decisions are driven by
 * task type + required capabilities — never by a raw string in the UI layer.
 */
enum class TaskType {
    Chat,
    Coding,
    Reasoning,
    Research,
    Vision,
    Summarization,
    Planning,
    FastAnswer,
}

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
 * Heuristic-only task classifier. No LLM call is ever needed: keywords and
 * simple structure rules decide the task type. An optional small router model
 * could refine this later, but heuristics are reliable enough for the core
 * profiles and cost nothing.
 */
object TaskClassifier {

    private val codingKeywords = listOf(
        "fix bug", "fix the bug", "bug in", "refactor", "implement ", "implement a",
        "write code", "write a function", "write a test", "tests", "unit test",
        "compile", "build ", "lint", "error in", "exception", "crash", "stack trace",
        "pull request", "commit", "git ", "branch", "dependency", "package.json",
        "gradle", "kotlin", "python", "node", "npm", "install ", "ci/cd",
        "code review", "add endpoint", "api ", "function ", "class ",
    )

    private val reasoningKeywords = listOf(
        "think step by step", "step-by-step", "reason", "proof", "prove",
        "solve ", "calculate", "math", "equation", "logic puzzle", "analyze ",
        "explain the reasoning", "why does", "deep think",
    )

    private val researchKeywords = listOf(
        "research ", "find information", "search the web", "browse", "look up",
        "summary of", "summarize this", "overview of", "who is", "what is",
        "compare ", "comparison", "latest news", "current events", "read this page",
    )

    private val visionKeywords = listOf(
        "screenshot", "image", "photo", "picture", "analyze this image",
        "describe the image", "vision", "ocr", "chart", "graph", "diagram",
        "what do you see", "scan",
    )

    private val summarizationKeywords = listOf(
        "summarize", "summary", "tl;dr", "tldr", "brief", "condense",
        "key points", "main points", "in short",
    )

    fun classify(message: String): TaskType {
        val lower = message.lowercase()
        // A message with an image part attached is at least a vision task.
        val scores = mapOf(
            TaskType.Coding to codingKeywords.count { lower.contains(it) },
            TaskType.Reasoning to reasoningKeywords.count { lower.contains(it) },
            TaskType.Research to researchKeywords.count { lower.contains(it) },
            TaskType.Vision to visionKeywords.count { lower.contains(it) },
            TaskType.Summarization to summarizationKeywords.count { lower.contains(it) },
            TaskType.FastAnswer to if (lower.length < 60 && lower.endsWith("?")) 1 else 0,
        )
        val best = scores.maxByOrNull { it.value }
        return if (best == null || best.value == 0) TaskType.Chat else best.key
    }
}

/**
 * A candidate model scored for a routing request. Higher is better.
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
 * Smart model router. Pure, deterministic, no network calls.
 *
 * Selection pipeline:
 * 1. Expand the candidate set from all configured service instances.
 * 2. Apply hard constraints (capabilities, local/cloud, allow/block lists,
 *    health, budget).
 * 3. Score surviving candidates against profile weights.
 * 4. Order the fallback chain from highest to lowest score.
 */
class ModelRouter(
    settings: AppSettings,
    private val health: ProviderHealthRegistry,
) {
    private val settings: Settings = settings.settings

    fun selectModel(
        taskType: TaskType,
        hasVisionInput: Boolean,
        requiresTools: Boolean,
        contextTokens: Int,
        profileId: RoutingProfileId = currentProfile().profileId,
        configuredInstances: List<String>,
        instanceServiceIds: Map<String, String>,
    ): ModelCandidate? {
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

        val healthy = candidates.filter { it.rejectionReasons.none { r -> r == "unhealthy" } }
        val pool = healthy.ifEmpty { candidates }
        val ordered = pool.sortedByDescending { it.score }
        return ordered.firstOrNull()
    }

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
        if (curated?.isLocal == true && config.cloudAllowed.not()) {
            // fine — local only allows local models
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
    }
}
