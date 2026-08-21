package com.inspiredandroid.kai.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderDiagnosticsTest {
    @Test
    fun `agent readiness requires connection chat and verified tool call`() {
        val base = ProviderDiagnosticReport(
            instanceId = "openai",
            providerName = "OpenAI",
            modelId = "test-model",
            endpoint = "https://api.example.test/v1/chat/completions",
            connection = DiagnosticCheck.passed("connected"),
            modelDiscovery = DiagnosticCheck.passed("models", 2),
            chatCompletion = DiagnosticCheck.passed("chat"),
            toolCalling = DiagnosticCheck.failed("no function call"),
            latencyMs = 10,
            checkedAtEpochMs = 1,
        )

        assertTrue(base.isUsableForChat)
        assertFalse(base.isUsableForAgents)
        assertTrue(base.copy(toolCalling = DiagnosticCheck.passed("verified")).isUsableForAgents)
    }

    @Test
    fun `unsupported tools never report agent ready`() {
        val report = ProviderDiagnosticReport(
            "id", "Provider", "text-only", "https://example.test",
            DiagnosticCheck.passed("connected"),
            DiagnosticCheck.skipped("no catalog"),
            DiagnosticCheck.passed("chat"),
            DiagnosticCheck.unsupported("text only"),
            1, 1,
        )
        assertFalse(report.isUsableForAgents)
    }
}
