package com.inspiredandroid.kai.gateway

import com.inspiredandroid.kai.testutil.TestSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProviderCapabilitiesTest {
    @Test
    fun `live probes produce health latency usage cost and context evidence`() = runTest {
        val settings = TestSettings.appSettings()
        val health = ProviderHealthRegistry(settings)
        val usage = UsageRecorder(settings)
        val registry = ProviderCapabilityRegistry()
        val calls = mutableListOf<GatewayCapability>()
        val prober = ProviderCapabilityProber(
            transport = ProviderCapabilityTransport { request ->
                calls += request.capability
                if (request.capability == GatewayCapability.ContextWindow) {
                    CapabilityProbeObservation(
                        CapabilityProbeState.Supported, "discovered", CapabilityEvidence.LiveDiscovery,
                        contextLimitTokens = 128_000,
                    )
                } else {
                    CapabilityProbeObservation(
                        CapabilityProbeState.Supported, "verified", CapabilityEvidence.LiveInteraction,
                        inputTokens = 3, outputTokens = 1, actualCostUsd = 0.0001,
                    )
                }
            },
            health = health,
            usage = usage,
            registry = registry,
            nowEpochMs = { 1234L },
        )

        val report = prober.probe("provider-1", "model-1")

        assertEquals(GatewayCapability.entries.toSet(), calls.toSet())
        assertTrue(report.supports(GatewayCapability.Chat))
        assertTrue(report.supports(GatewayCapability.Tools))
        assertTrue(report.supports(GatewayCapability.Vision))
        assertTrue(report.supports(GatewayCapability.Streaming))
        assertEquals(128_000, report.contextLimitTokens)
        assertTrue(report.totalLatencyMs >= 0)
        assertEquals(HealthState.Connected, health.recordFor("provider-1").state)
        assertEquals(4, usage.loadAll().size)
        assertEquals(0.0004, usage.loadAll().sumOf { it.estimatedCostUsd }, 0.0000001)
        assertNotNull(registry.reportFor("provider-1", "model-1"))
    }

    @Test
    fun `unsupported claims without live evidence fail closed`() = runTest {
        val settings = TestSettings.appSettings()
        val report = ProviderCapabilityProber(
            transport = ProviderCapabilityTransport {
                CapabilityProbeObservation(
                    CapabilityProbeState.Supported,
                    "claimed by config",
                    CapabilityEvidence.None,
                    contextLimitTokens = 999_999,
                )
            },
            health = ProviderHealthRegistry(settings),
            usage = UsageRecorder(settings),
            registry = ProviderCapabilityRegistry(),
        ).probe("provider-1", "model-1")

        assertFalse(report.supports(GatewayCapability.Chat))
        assertFalse(report.supports(GatewayCapability.Tools))
        assertEquals(null, report.contextLimitTokens)
        assertEquals(CapabilityProbeState.Failed, report.results.getValue(GatewayCapability.Chat).state)
    }

    @Test
    fun `probe failures are redacted and mark chat health without leaking keys`() = runTest {
        val settings = TestSettings.appSettings()
        val health = ProviderHealthRegistry(settings)
        val secret = "sk-abcdefghijklmnopqrstuvwxyz123456"
        val report = ProviderCapabilityProber(
            transport = ProviderCapabilityTransport { request ->
                if (request.capability == GatewayCapability.Chat) error("Authorization: Bearer $secret")
                CapabilityProbeObservation(CapabilityProbeState.Unsupported, "not available", CapabilityEvidence.LiveInteraction)
            },
            health = health,
            usage = UsageRecorder(settings),
            registry = ProviderCapabilityRegistry(),
        ).probe("provider-1", "model-1")

        val exported = report.results.values.joinToString { it.detail }
        assertFalse(secret in exported)
        assertTrue("[REDACTED]" in exported)
        assertEquals(HealthState.NetworkError, health.recordFor("provider-1").state)
    }

    @Test
    fun `cancellation is never converted into a failed probe`() = runTest {
        val settings = TestSettings.appSettings()
        val prober = ProviderCapabilityProber(
            transport = ProviderCapabilityTransport { throw CancellationException("cancel") },
            health = ProviderHealthRegistry(settings),
            usage = UsageRecorder(settings),
            registry = ProviderCapabilityRegistry(),
        )

        assertFailsWith<CancellationException> { prober.probe("provider-1", "model-1") }
    }

    @Test
    fun `router rejects streaming and oversized context from live evidence`() {
        val settings = TestSettings.appSettings()
        val registry = ProviderCapabilityRegistry()
        registry.update(
            ProviderCapabilityReport(
                "inst", "coding-model", 1, 1,
                mapOf(
                    GatewayCapability.Streaming to CapabilityProbeResult(
                        GatewayCapability.Streaming, CapabilityProbeState.Unsupported,
                        "no stream", CapabilityEvidence.LiveInteraction, 1,
                    ),
                    GatewayCapability.ContextWindow to CapabilityProbeResult(
                        GatewayCapability.ContextWindow, CapabilityProbeState.Supported,
                        "limit", CapabilityEvidence.LiveDiscovery, 1, 1_024,
                    ),
                ),
            ),
        )
        val router = ModelRouter(settings, ProviderHealthRegistry(settings), registry)
        router.saveProfile(RoutingProfileConfig(codingModelId = "coding-model"))

        val decision = router.selectAllCandidates(
            TaskType.Coding, false, false, 2_000,
            configuredInstances = listOf("inst"),
            instanceServiceIds = mapOf("inst" to "openai"),
            requiresStreaming = true,
        )

        assertEquals(null, decision.primary)
        assertTrue(decision.warnings.any { "no_streaming" in it && "context_exceeded" in it })
    }
}
