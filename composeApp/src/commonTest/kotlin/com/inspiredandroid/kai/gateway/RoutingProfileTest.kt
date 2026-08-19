package com.inspiredandroid.kai.gateway

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RoutingProfileTest {

    @Test
    fun `every built-in profile id produces a valid RoutingProfile`() {
        for (id in RoutingProfileId.entries) {
            val profile = RoutingProfile.defaultFor(id)
            assertTrue(profile.id.isNotBlank())
            profile.weights.validate()
        }
    }

    @Test
    fun `constructor rejects uppercase or blank ids`() {
        assertFailsWith<IllegalArgumentException> { RoutingProfile("", "X") }
        assertFailsWith<IllegalArgumentException> { RoutingProfile("MixedCase", "X") }
    }

    @Test
    fun `weights reject negative or all-zero values`() {
        assertFailsWith<IllegalArgumentException> { RoutingWeights(quality = -1.0).validate() }
        assertFailsWith<IllegalArgumentException> { RoutingWeights(0.0, 0.0, 0.0, 0.0, 0.0).validate() }
    }

    @Test
    fun `scoring rewards quality for MaximumQuality profile`() {
        val profile = RoutingProfile.defaultFor(RoutingProfileId.MaximumQuality)
        val best = RoutingScoring.score(profile, ARABIC_VISION, qualityTier = 5, speedTier = 3, estimatedCostUsd = 0.1, tags = emptySet(), isLocal = false, supportsVision = true, supportsTools = true, arabicQuality = true, inputPricePerMTok = 1.0, outputPricePerMTok = 1.0, contextTokens = 1000)
        val weak = RoutingScoring.score(profile, ARABIC_VISION, qualityTier = 2, speedTier = 1, estimatedCostUsd = 0.1, tags = emptySet(), isLocal = false, supportsVision = true, supportsTools = true, arabicQuality = false, inputPricePerMTok = 1.0, outputPricePerMTok = 1.0, contextTokens = 1000)
        assertTrue(best > weak)
    }

    @Test
    fun `coding profile boosts models tagged coding`() {
        val profile = RoutingProfile.defaultFor(RoutingProfileId.Coding)
        val coding = RoutingScoring.score(profile, PLAIN, qualityTier = 3, speedTier = 3, estimatedCostUsd = 0.0, tags = setOf("coding"), isLocal = false, supportsVision = false, supportsTools = true, arabicQuality = false, inputPricePerMTok = null, outputPricePerMTok = null, contextTokens = 1000)
        val other = RoutingScoring.score(profile, PLAIN, qualityTier = 3, speedTier = 3, estimatedCostUsd = 0.0, tags = setOf("writing"), isLocal = false, supportsVision = false, supportsTools = true, arabicQuality = false, inputPricePerMTok = null, outputPricePerMTok = null, contextTokens = 1000)
        assertEquals(8.0, coding - other, 0.001)
    }

    @Test
    fun `privacy-local-only rejects cloud models with strong penalty`() {
        val profile = RoutingProfile.defaultFor(RoutingProfileId.PrivacyLocalOnly)
        val local = RoutingScoring.score(profile, PLAIN, qualityTier = 3, speedTier = 3, estimatedCostUsd = 0.0, tags = emptySet(), isLocal = true, supportsVision = false, supportsTools = true, arabicQuality = false, inputPricePerMTok = null, outputPricePerMTok = null, contextTokens = 1000)
        val cloud = RoutingScoring.score(profile, PLAIN, qualityTier = 5, speedTier = 1, estimatedCostUsd = 0.0, tags = emptySet(), isLocal = false, supportsVision = false, supportsTools = true, arabicQuality = false, inputPricePerMTok = null, outputPricePerMTok = null, contextTokens = 1000)
        assertTrue(local > cloud, "local score $local must beat cloud score $cloud under privacy profile")
    }

    @Test
    fun `hard rejections include no_vision when vision is required`() {
        val reasons = RoutingFiltering.hardRejections(
            requirements = RoutingRequirements(capabilities = setOf("vision"), needsArabic = false, contextTokens = 1000, hasVisionInput = true),
            constraints = RoutingConstraints(),
            supportsVision = false, supportsTools = true, isLocal = false,
            instanceId = "inst-1", allowedProviderIds = emptyList(), blockedProviderIds = emptyList(),
            isUnhealthy = false, estimatedCostUsd = 0.0,
        )
        assertTrue("no_vision" in reasons)
        assertTrue("no_tools" !in reasons)
    }

    @Test
    fun `blocked and unhealthy providers are rejected explicitly`() {
        val reasons = RoutingFiltering.hardRejections(
            requirements = RoutingRequirements(capabilities = emptySet(), needsArabic = false, contextTokens = 100, hasVisionInput = false),
            constraints = RoutingConstraints(blockCloud = true),
            supportsVision = true, supportsTools = true, isLocal = false,
            instanceId = "inst-2", allowedProviderIds = emptyList(), blockedProviderIds = listOf("inst-2"),
            isUnhealthy = true, estimatedCostUsd = 0.0,
        )
        assertEquals(listOf("cloud_blocked", "blocked", "unhealthy"), reasons)
    }

    @Test
    fun `requirement extractor detects arabic prompts without calling any LLM`() {
        val req = RequirementExtractor.extract("اكتب لي دالة لحساب الأعداد الأولية", hasVisionInput = false, requiresTools = true, contextTokens = 2000)
        assertTrue(req.needsArabic)
        assertTrue("tools" in req.capabilities)
        val english = RequirementExtractor.extract("write a unit test", hasVisionInput = false, requiresTools = false, contextTokens = 2000)
        assertTrue(!english.needsArabic)
    }

    @Test
    fun `arabic bonus raises score only when the prompt needs arabic`() {
        val profile = RoutingProfile.defaultFor(RoutingProfileId.Balanced)
        val arabicReq = RoutingRequirements(capabilities = emptySet(), needsArabic = true, contextTokens = 1000, hasVisionInput = false)
        val plainReq = RoutingRequirements(capabilities = emptySet(), needsArabic = false, contextTokens = 1000, hasVisionInput = false)
        val scoreArabicNeeded = RoutingScoring.score(profile, arabicReq, qualityTier = 3, speedTier = 3, estimatedCostUsd = 0.0, tags = emptySet(), isLocal = false, supportsVision = false, supportsTools = true, arabicQuality = true, inputPricePerMTok = null, outputPricePerMTok = null, contextTokens = 1000)
        val scorePlainNeeded = RoutingScoring.score(profile, plainReq, qualityTier = 3, speedTier = 3, estimatedCostUsd = 0.0, tags = emptySet(), isLocal = false, supportsVision = false, supportsTools = true, arabicQuality = true, inputPricePerMTok = null, outputPricePerMTok = null, contextTokens = 1000)
        assertEquals(2.0, scoreArabicNeeded - scorePlainNeeded, 0.001)
    }

    companion object {
        private val ARABIC_VISION = RoutingRequirements(capabilities = setOf("vision"), needsArabic = true, contextTokens = 1000, hasVisionInput = true)
        private val PLAIN = RoutingRequirements(capabilities = emptySet(), needsArabic = false, contextTokens = 1000, hasVisionInput = false)
    }
}
