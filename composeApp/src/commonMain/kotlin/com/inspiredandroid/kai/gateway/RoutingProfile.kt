package com.inspiredandroid.kai.gateway

/**
 * Single source of truth for routing weights (PHASE 9, section 12 of the
 * prompt). All scoring multipliers live in this file — never scattered
 * across ModelRouter call sites. The profile is pure data, so it can be
 * unit tested, serialized, and later supplied by the signed remote config.
 *
 * Pipeline (documented here, enforced by [RoutingPipeline]):
 *   Task → Task Classification → Requirement Extraction → Candidate
 *   Filtering → Scoring (this profile) → Primary + Fallback Graph.
 */
data class RoutingProfile(
    val id: String,
    val displayName: String,
    val weights: RoutingWeights = RoutingWeights(),
    val constraints: RoutingConstraints = RoutingConstraints(),
) {
    init {
        require(id.isNotBlank() && id == id.lowercase()) { "RoutingProfile.id must be lowercase: $id" }
        weights.validate()
    }

    fun withWeights(newWeights: RoutingWeights): RoutingProfile = copy(weights = newWeights)
    fun withConstraints(newConstraints: RoutingConstraints): RoutingProfile = copy(constraints = newConstraints)

    companion object {
        fun defaultFor(profileId: RoutingProfileId): RoutingProfile = when (profileId) {
            RoutingProfileId.Balanced -> RoutingProfile("balanced", "Balanced")
            RoutingProfileId.MaximumQuality -> RoutingProfile(
                "maximum-quality", "Maximum Quality",
                weights = RoutingWeights(quality = 4.0, speed = 1.0, cost = 0.25, local = 1.0, arabic = 1.0),
            )
            RoutingProfileId.Fast -> RoutingProfile(
                "fast", "Fast",
                weights = RoutingWeights(quality = 1.5, speed = 3.0, cost = 1.0, local = 1.0, arabic = 1.0),
            )
            RoutingProfileId.Economy -> RoutingProfile(
                "economy", "Economy",
                weights = RoutingWeights(quality = 1.0, speed = 1.5, cost = 5.0, local = 1.0, arabic = 1.0),
            )
            RoutingProfileId.Coding -> RoutingProfile(
                "coding", "Coding",
                weights = RoutingWeights(quality = 2.5, speed = 1.0, cost = 0.5, local = 1.0, arabic = 1.0),
                constraints = RoutingConstraints(tagBoost = "coding", boostAmount = 8.0),
            )
            RoutingProfileId.Reasoning -> RoutingProfile(
                "reasoning", "Reasoning",
                weights = RoutingWeights(quality = 2.0, speed = 0.5, cost = 0.5, local = 1.0, arabic = 1.0),
                constraints = RoutingConstraints(tagBoost = "reasoning", boostAmount = 8.0),
            )
            RoutingProfileId.Research -> RoutingProfile(
                "research", "Research",
                weights = RoutingWeights(quality = 2.0, speed = 1.5, cost = 0.5, local = 1.0, arabic = 1.0),
            )
            RoutingProfileId.Vision -> RoutingProfile(
                "vision", "Vision",
                weights = RoutingWeights(quality = 2.0, speed = 1.0, cost = 0.5, local = 1.0, arabic = 1.0),
                constraints = RoutingConstraints(visionBonus = 6.0, visionPenalty = -10.0),
            )
            RoutingProfileId.LocalFirst -> RoutingProfile(
                "local-first", "Local First",
                weights = RoutingWeights(quality = 2.0, speed = 1.0, cost = 0.5, local = 5.0, arabic = 1.0),
                constraints = RoutingConstraints(localBoost = 10.0),
            )
            RoutingProfileId.PrivacyLocalOnly -> RoutingProfile(
                "privacy-local-only", "Privacy (Local Only)",
                weights = RoutingWeights(quality = 2.0, speed = 1.0, cost = 0.5, local = 10.0, arabic = 1.0),
                constraints = RoutingConstraints(localBoost = 20.0, localPenalty = -50.0, blockCloud = true),
            )
            RoutingProfileId.Custom -> RoutingProfile("custom", "Custom")
        }
    }
}

/**
 * Weights consumed by [RoutingPipeline.score]. Every factor has a non-negative
 * multiplier; a missing factor (weight 0) is excluded from scoring.
 *
 * @property quality quality tier multiplier
 * @property speed speed bonus (faster = higher)
 * @property cost per-run cost penalty (negative contribution)
 * @property local boost for on-device models
 * @property arabic bonus for documented Arabic proficiency
 */
data class RoutingWeights(
    val quality: Double = 2.0,
    val speed: Double = 1.0,
    val cost: Double = 1.0,
    val local: Double = 1.0,
    val arabic: Double = 1.0,
) {
    fun validate() {
        require(quality >= 0 && speed >= 0 && cost >= 0 && local >= 0 && arabic >= 0) {
            "RoutingWeights must be non-negative: $this"
        }
        val total = quality + speed + cost + local + arabic
        require(total > 0) { "RoutingWeights must not all be zero" }
    }
}

/**
 * Hard constraints and tag bonuses applied during candidate filtering/scoring.
 * Kept separate from weights so policy decisions stay auditable.
 */
data class RoutingConstraints(
    val tagBoost: String? = null,
    val boostAmount: Double = 0.0,
    val visionBonus: Double = 0.0,
    val visionPenalty: Double = 0.0,
    val localBoost: Double = 0.0,
    val localPenalty: Double = 0.0,
    val blockCloud: Boolean = false,
    val maxCostPerRunUsd: Double? = null,
)

/**
 * Requirement extraction: converts a routing request into the requirement set
 * the pipeline filters and scores against. This is the missing bridge between
 * [TaskClassifier] and candidate scoring — capabilities and context demands
 * are now explicit instead of inline in the router.
 */
data class RoutingRequirements(
    val capabilities: Set<String>,
    val needsArabic: Boolean,
    val contextTokens: Int,
    val hasVisionInput: Boolean,
)

object RequirementExtractor {
    /** Static analysis of the request — never an LLM call. */
    fun extract(
        prompt: String,
        hasVisionInput: Boolean,
        requiresTools: Boolean,
        contextTokens: Int,
    ): RoutingRequirements {
        val lower = prompt.lowercase()
        val arabic = Regex("[\\u0600-\\u06FF]").containsMatchIn(lower)
        return RoutingRequirements(
            capabilities = buildSet {
                if (hasVisionInput) add("vision")
                if (requiresTools) add("tools")
                if (lower.contains("pdf") || lower.contains(".pdf")) add("pdf")
            },
            needsArabic = arabic,
            contextTokens = contextTokens,
            hasVisionInput = hasVisionInput,
        )
    }
}

/**
 * Deterministic scoring function over a profile + requirements + catalog
 * metadata. Pure and testable; the router just wires instances into it.
 */
object RoutingScoring {
    fun score(
        profile: RoutingProfile,
        requirements: RoutingRequirements,
        qualityTier: Int,
        speedTier: Int,
        estimatedCostUsd: Double,
        tags: Set<String>,
        isLocal: Boolean,
        supportsVision: Boolean,
        supportsTools: Boolean,
        arabicQuality: Boolean,
        inputPricePerMTok: Double?,
        outputPricePerMTok: Double?,
        contextTokens: Int,
    ): Double {
        val w = profile.weights
        val c = profile.constraints
        val cost = if (inputPricePerMTok != null && outputPricePerMTok != null) {
            (inputPricePerMTok * contextTokens / 1_000_000.0) +
                (outputPricePerMTok * (contextTokens / 4) / 1_000_000.0)
        } else estimatedCostUsd

        var score = w.quality * qualityTier +
            w.speed * (6 - speedTier) +
            w.cost * (if (cost == 0.0) 2.0 else -cost) +
            w.arabic * (if (arabicQuality && requirements.needsArabic) 2.0 else 0.0)

        if (isLocal) score += w.local
        if (requirements.capabilities.contains("vision")) {
            if (supportsVision) score += c.visionBonus
            else score += c.visionPenalty
        }
        if (isLocal) {
            if (c.localBoost != 0.0) score += c.localBoost
        } else if (c.localPenalty != 0.0) {
            score += c.localPenalty
        }
        if (c.tagBoost != null && c.tagBoost in tags) score += c.boostAmount
        return score
    }
}

/**
 * Pure filtering function: decides which hard constraints reject a candidate
 * and returns the rejection reasons (never silent downgrades).
 */
object RoutingFiltering {
    fun hardRejections(
        requirements: RoutingRequirements,
        constraints: RoutingConstraints,
        supportsVision: Boolean,
        supportsTools: Boolean,
        isLocal: Boolean,
        instanceId: String,
        allowedProviderIds: List<String>,
        blockedProviderIds: List<String>,
        isUnhealthy: Boolean,
        estimatedCostUsd: Double,
    ): List<String> = buildList {
        if (requirements.capabilities.contains("vision") && !supportsVision) add("no_vision")
        if (requirements.capabilities.contains("tools") && !supportsTools) add("no_tools")
        if (requirements.capabilities.contains("pdf") && !supportsVision) add("no_pdf")
        if (constraints.blockCloud && !isLocal) add("cloud_blocked")
        if (instanceId in blockedProviderIds) add("blocked")
        if (allowedProviderIds.isNotEmpty() && instanceId !in allowedProviderIds) add("not_allowed")
        if (isUnhealthy) add("unhealthy")
        if (constraints.maxCostPerRunUsd != null && estimatedCostUsd > constraints.maxCostPerRunUsd) {
            add("over_budget")
        }
    }
}
