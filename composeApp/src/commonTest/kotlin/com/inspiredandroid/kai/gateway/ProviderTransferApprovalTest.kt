package com.inspiredandroid.kai.gateway

import com.inspiredandroid.kai.security.ProviderCredentialsResolver
import com.inspiredandroid.kai.security.SecretStore
import com.inspiredandroid.kai.testutil.TestSettings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProviderTransferApprovalTest {
    @Test
    fun `provider boundary treats custom endpoints as distinct data controllers`() {
        assertTrue(ProviderBoundary.crosses("openai", "", "anthropic", ""))
        assertTrue(
            ProviderBoundary.crosses(
                "openai-compatible", "https://one.example/v1",
                "openai-compatible", "https://two.example/v1",
            ),
        )
        assertFalse(
            ProviderBoundary.crosses(
                "openai-compatible", "https://same.example/v1/",
                "openai-compatible", "https://same.example/v1",
            ),
        )
        assertFalse(ProviderBoundary.crosses("openai", "", "openai", ""))
    }

    @Test
    fun `cross provider fallback stops before execution without explicit approval`() = runTest {
        val harness = harness(ProviderTransferApprovalGate.DenyByDefault)
        val calls = mutableListOf<String>()

        val outcome = harness.coordinator.execute(decision()) { instanceId, _ ->
            calls += instanceId
            if (instanceId == "primary") AiRequestOutcome(false, error = AiRequestError.NetworkError("offline"))
            else AiRequestOutcome(true)
        }

        assertFalse(outcome.success)
        assertEquals(listOf("primary"), calls)
        assertNotNull(outcome.pendingTransferApproval)
        assertEquals("fallback", outcome.pendingTransferApproval?.destinationProviderInstanceId)
    }

    @Test
    fun `one explicit approval permits exactly the requested provider transfer`() = runTest {
        val approvals = mutableListOf<ProviderTransferRequest>()
        val harness = harness(ProviderTransferApprovalGate {
            approvals += it
            ProviderTransferDecision.ApprovedOnce
        })
        val calls = mutableListOf<String>()

        val outcome = harness.coordinator.execute(decision()) { instanceId, _ ->
            calls += instanceId
            if (instanceId == "primary") AiRequestOutcome(false, error = AiRequestError.TimeoutError())
            else AiRequestOutcome(true, inputTokens = 10, outputTokens = 2, costUsd = 0.01)
        }

        assertTrue(outcome.success)
        assertEquals(listOf("primary", "fallback"), calls)
        assertEquals(1, approvals.size)
        assertEquals(AiRequestError.Category.Timeout, approvals.single().reason)
        assertEquals(null, outcome.pendingTransferApproval)
    }

    @Test
    fun `same provider instance retry never asks for transfer approval`() = runTest {
        var approvalCalls = 0
        var executionCalls = 0
        val harness = harness(ProviderTransferApprovalGate {
            approvalCalls++
            ProviderTransferDecision.Denied
        })
        val sameInstanceDecision = decision().copy(
            fallbackChain = listOf(
                ModelCandidate("model-b", "primary", RoutingProfileId.Coding, 9.0),
            ),
        )

        val outcome = harness.coordinator.execute(sameInstanceDecision) { _, _ ->
            executionCalls++
            if (executionCalls == 1) AiRequestOutcome(false, error = AiRequestError.NetworkError())
            else AiRequestOutcome(true)
        }

        assertTrue(outcome.success)
        assertEquals(2, executionCalls)
        assertEquals(0, approvalCalls)
    }

    private data class Harness(val coordinator: AiGatewayCoordinator)

    private fun harness(gate: ProviderTransferApprovalGate): Harness {
        val settings = TestSettings.appSettings()
        val health = ProviderHealthRegistry(settings)
        val secretStore = object : SecretStore {
            override suspend fun put(key: String, value: String) = Unit
            override suspend fun get(key: String): String? = null
            override suspend fun remove(key: String) = Unit
            override suspend fun contains(key: String): Boolean = false
        }
        return Harness(
            AiGatewayCoordinator(
                router = ModelRouter(settings, health),
                usage = UsageRecorder(settings),
                health = health,
                credentials = ProviderCredentialsResolver(secretStore, settings),
                strategy = FallbackStrategy(maxAttemptsPerCandidate = 0, maxTotalAttempts = 3),
                transferApproval = gate,
            ),
        )
    }

    private fun decision() = RoutingDecision(
        taskType = TaskType.Coding,
        profileId = RoutingProfileId.Coding,
        primary = ModelCandidate("model-a", "primary", RoutingProfileId.Coding, 10.0),
        fallbackChain = listOf(ModelCandidate("model-b", "fallback", RoutingProfileId.Coding, 9.0)),
    )
}
