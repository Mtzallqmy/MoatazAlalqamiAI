package com.inspiredandroid.kai.gateway

import com.inspiredandroid.kai.testutil.TestSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Unit tests for the deterministic [TaskClassifier] and [ModelRouter].
 * The router is pure and network-free, so these tests run with an empty
 * in-memory settings store.
 */
class ModelRouterTest {

    private fun router(): ModelRouter {
        val settings = TestSettings.appSettings()
        return ModelRouter(settings, ProviderHealthRegistry(settings))
    }

    // ---------------------------------------------------------------
    // TaskClassifier
    // ---------------------------------------------------------------

    @Test
    fun `plain question classifies as fast answer`() {
        assertEquals(TaskType.FastAnswer, TaskClassifier.classify("How are you doing today?"))
    }

    @Test
    fun `empty message classifies as chat`() {
        assertEquals(TaskType.Chat, TaskClassifier.classify(""))
        assertEquals(TaskType.Chat, TaskClassifier.classify("Hello there"))
    }

    @Test
    fun `code keywords classify as coding`() {
        assertEquals(TaskType.Coding, TaskClassifier.classify("Fix the bug in the login flow"))
        assertEquals(TaskType.Coding, TaskClassifier.classify("Write a test for the auth service"))
    }

    @Test
    fun `math keywords classify as reasoning`() {
        assertEquals(TaskType.Reasoning, TaskClassifier.classify("Think step by step through this logic puzzle"))
        assertEquals(TaskType.Reasoning, TaskClassifier.classify("Prove this theorem step by step"))
    }

    @Test
    fun `search keywords classify as research`() {
        assertEquals(TaskType.Research, TaskClassifier.classify("Research the latest LLM benchmarks"))
        assertEquals(TaskType.Research, TaskClassifier.classify("Compare the pricing of OpenAI and Anthropic"))
    }

    @Test
    fun `vision keywords classify as vision`() {
        assertEquals(TaskType.Vision, TaskClassifier.classify("Analyze this image and describe what you see"))
    }

    @Test
    fun `summarize keywords classify as summarization`() {
        assertEquals(TaskType.Summarization, TaskClassifier.classify("Summarize this article in key points"))
    }

    // ---------------------------------------------------------------
    // ModelRouter selection
    // ---------------------------------------------------------------

    @Test
    fun `no configured instances means no selection`() {
        val router = router()
        assertNull(router.selectModel(TaskType.Chat, false, false, 1000, configuredInstances = emptyList(), instanceServiceIds = emptyMap()))
    }

    @Test
    fun `blocked provider is never selected`() {
        val router = router()
        router.saveProfile(
            RoutingProfileConfig(
                profileId = RoutingProfileId.Balanced,
                blockedProviderIds = listOf("inst-1"),
            ),
        )
        assertNull(
            router.selectModel(
                taskType = TaskType.Chat,
                hasVisionInput = false,
                requiresTools = false,
                contextTokens = 1000,
                configuredInstances = listOf("inst-1"),
                instanceServiceIds = mapOf("inst-1" to "openai"),
            ),
        )
    }

    @Test
    fun `allow list restricts candidates`() {
        val router = router()
        router.saveProfile(
            RoutingProfileConfig(
                profileId = RoutingProfileId.Balanced,
                allowedProviderIds = listOf("inst-2"),
            ),
        )
        val selected = router.selectModel(
            taskType = TaskType.Chat,
            hasVisionInput = false,
            requiresTools = false,
            contextTokens = 1000,
            configuredInstances = listOf("inst-1", "inst-2"),
            instanceServiceIds = mapOf("inst-1" to "openai", "inst-2" to "anthropic"),
        )
        assertNotNull(selected)
        assertEquals("inst-2", selected.providerInstanceId)
    }

    @Test
    fun `privacy local only rejects cloud services`() {
        val router = router()
        router.saveProfile(RoutingProfileConfig(profileId = RoutingProfileId.PrivacyLocalOnly))
        // openai-compatible generic service has no curated local entry → cloud_blocked
        assertNull(
            router.selectModel(
                taskType = TaskType.Chat,
                hasVisionInput = false,
                requiresTools = false,
                contextTokens = 1000,
                configuredInstances = listOf("inst-1"),
                instanceServiceIds = mapOf("inst-1" to "free"),
            ),
        )
    }

    @Test
    fun `vision task rejects models without vision capability`() {
        val router = router()
        // ollama default models lack curated vision entries → rejected under a vision task
        assertNull(
            router.selectModel(
                taskType = TaskType.Vision,
                hasVisionInput = true,
                requiresTools = false,
                contextTokens = 1000,
                configuredInstances = listOf("inst-1"),
                instanceServiceIds = mapOf("inst-1" to "ollama_cloud"),
            ),
        )
    }

    @Test
    fun `local first profile prefers local services`() {
        val router = router()
        router.saveProfile(RoutingProfileConfig(profileId = RoutingProfileId.LocalFirst))
        val selected = router.selectModel(
            taskType = TaskType.Chat,
            hasVisionInput = false,
            requiresTools = false,
            contextTokens = 1000,
            configuredInstances = listOf("inst-local", "inst-cloud"),
            instanceServiceIds = mapOf("inst-local" to "litert", "inst-cloud" to "openai"),
        )
        assertNotNull(selected)
        // Local models score +10 under LocalFirst even when cloud models have
        // a higher curated quality tier — locality is the dominant weight.
        assertEquals("inst-local", selected.providerInstanceId)
    }

    @Test
    fun `explicit per-task model wins outright`() {
        val router = router()
        router.saveProfile(
            RoutingProfileConfig(
                profileId = RoutingProfileId.Balanced,
                codingModelId = "claude-sonnet-4.5",
            ),
        )
        val selected = router.selectModel(
            taskType = TaskType.Coding,
            hasVisionInput = false,
            requiresTools = true,
            contextTokens = 50000,
            configuredInstances = listOf("inst-1", "inst-2"),
            instanceServiceIds = mapOf("inst-1" to "openai", "inst-2" to "anthropic"),
        )
        assertNotNull(selected)
        assertEquals(100.0, selected.score)
        assertEquals("claude-sonnet-4.5", selected.modelId)
    }

    @Test
    fun `coding model wins even when service ids are swapped`() {
        val router = router()
        router.saveProfile(
            RoutingProfileConfig(
                profileId = RoutingProfileId.Coding,
                codingModelId = "claude-opus-4-5",
            ),
        )
        val selected = router.selectModel(
            taskType = TaskType.Coding,
            hasVisionInput = false,
            requiresTools = false,
            contextTokens = 50000,
            configuredInstances = listOf("inst-x"),
            instanceServiceIds = mapOf("inst-x" to "anthropic"),
        )
        assertNotNull(selected)
        assertEquals("claude-opus-4-5", selected.modelId)
    }

    @Test
    fun `profile config survives a JSON roundtrip through settings`() {
        val router = router()
        val config = RoutingProfileConfig(
            profileId = RoutingProfileId.Coding,
            codingModelId = "claude-opus-4.5",
            maxCostPerRunUsd = 1.5,
            localPreferred = true,
            fallbackChain = listOf("inst-a", "inst-b"),
        )
        router.saveProfile(config)
        val loaded = router.currentProfile()
        assertEquals(RoutingProfileId.Coding, loaded.profileId)
        assertEquals("claude-opus-4.5", loaded.codingModelId)
        assertEquals(1.5, loaded.maxCostPerRunUsd)
        assertEquals(true, loaded.localPreferred)
        assertEquals(listOf("inst-a", "inst-b"), loaded.fallbackChain)
    }
}
