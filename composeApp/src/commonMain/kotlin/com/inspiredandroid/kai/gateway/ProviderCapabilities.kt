package com.inspiredandroid.kai.gateway

import com.inspiredandroid.kai.runtime.RuntimeDiagnosticRedactor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random
import kotlin.time.TimeSource

/** Capabilities that must be observed from the configured endpoint, not assumed from its brand. */
enum class GatewayCapability { Chat, Tools, Vision, Streaming, ContextWindow }

enum class CapabilityProbeState { Supported, Unsupported, Failed, Skipped }

/** Evidence accepted as a real capability observation. */
enum class CapabilityEvidence { LiveInteraction, LiveDiscovery, None }

/**
 * A protocol adapter returns this only after performing the corresponding live request.
 * Credentials and request/response bodies deliberately have no fields in this contract.
 */
data class CapabilityProbeObservation(
    val state: CapabilityProbeState,
    val detail: String,
    val evidence: CapabilityEvidence,
    val contextLimitTokens: Int? = null,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cachedTokens: Long? = null,
    val actualCostUsd: Double? = null,
    /** Stable category only; raw provider exceptions/bodies are never retained. */
    val failureCategory: AiRequestError.Category? = null,
)

data class CapabilityProbeRequest(
    val providerInstanceId: String,
    val modelId: String,
    val capability: GatewayCapability,
)

/** Implemented by OpenAI/Anthropic/Gemini/local adapters using their real network/model calls. */
fun interface ProviderCapabilityTransport {
    suspend fun probe(request: CapabilityProbeRequest): CapabilityProbeObservation
}

data class CapabilityProbeResult(
    val capability: GatewayCapability,
    val state: CapabilityProbeState,
    val detail: String,
    val evidence: CapabilityEvidence,
    val latencyMs: Long,
    val contextLimitTokens: Int? = null,
)

data class ProviderCapabilityReport(
    val providerInstanceId: String,
    val modelId: String,
    val checkedAtEpochMs: Long,
    val totalLatencyMs: Long,
    val results: Map<GatewayCapability, CapabilityProbeResult>,
) {
    fun supports(capability: GatewayCapability): Boolean =
        results[capability]?.let {
            it.state == CapabilityProbeState.Supported && it.evidence != CapabilityEvidence.None
        } == true

    val contextLimitTokens: Int?
        get() = results[GatewayCapability.ContextWindow]
            ?.takeIf {
                it.state == CapabilityProbeState.Supported &&
                    it.evidence == CapabilityEvidence.LiveDiscovery
            }
            ?.contextLimitTokens
}

/** Latest live evidence, isolated by configured provider instance and model. */
class ProviderCapabilityRegistry {
    private val _reports = MutableStateFlow<Map<String, ProviderCapabilityReport>>(emptyMap())
    val reports: StateFlow<Map<String, ProviderCapabilityReport>> = _reports.asStateFlow()

    fun update(report: ProviderCapabilityReport) {
        _reports.value = _reports.value + (key(report.providerInstanceId, report.modelId) to report)
    }

    fun reportFor(providerInstanceId: String, modelId: String): ProviderCapabilityReport? =
        _reports.value[key(providerInstanceId, modelId)]

    private fun key(instanceId: String, modelId: String) = "$instanceId\u0000$modelId"
}

/**
 * Executes explicit capability probes, updates health, and records their real usage/cost.
 * A transport exception becomes a failed, redacted result; cancellation always propagates.
 */
class ProviderCapabilityProber(
    private val transport: ProviderCapabilityTransport,
    private val health: ProviderHealthRegistry,
    private val usage: UsageRecorder,
    private val registry: ProviderCapabilityRegistry,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
) {
    suspend fun probe(
        providerInstanceId: String,
        modelId: String,
        capabilities: Set<GatewayCapability> = GatewayCapability.entries.toSet(),
    ): ProviderCapabilityReport {
        require(providerInstanceId.isNotBlank()) { "providerInstanceId must not be blank" }
        require(modelId.isNotBlank()) { "modelId must not be blank" }

        val total = TimeSource.Monotonic.markNow()
        val results = linkedMapOf<GatewayCapability, CapabilityProbeResult>()
        var chatFailureCategory: AiRequestError.Category? = null
        for (capability in GatewayCapability.entries) {
            if (capability !in capabilities) {
                results[capability] = CapabilityProbeResult(
                    capability, CapabilityProbeState.Skipped, "Not requested",
                    CapabilityEvidence.None, 0,
                )
                continue
            }

            val mark = TimeSource.Monotonic.markNow()
            val observation = try {
                transport.probe(CapabilityProbeRequest(providerInstanceId, modelId, capability))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cause: Throwable) {
                CapabilityProbeObservation(
                    state = CapabilityProbeState.Failed,
                    detail = cause.message ?: cause::class.simpleName.orEmpty(),
                    evidence = CapabilityEvidence.None,
                    failureCategory = classifyRequestError(cause).category,
                )
            }
            val latency = mark.elapsedNow().inWholeMilliseconds.coerceAtLeast(0)
            val validated = validateEvidence(capability, observation)
            if (capability == GatewayCapability.Chat) chatFailureCategory = validated.failureCategory
            results[capability] = CapabilityProbeResult(
                capability = capability,
                state = validated.state,
                detail = RuntimeDiagnosticRedactor.redact(validated.detail),
                evidence = validated.evidence,
                latencyMs = latency,
                contextLimitTokens = validated.contextLimitTokens,
            )
            recordProbeUsage(providerInstanceId, modelId, capability, validated, latency)
        }

        val report = ProviderCapabilityReport(
            providerInstanceId = providerInstanceId,
            modelId = modelId,
            checkedAtEpochMs = nowEpochMs(),
            totalLatencyMs = total.elapsedNow().inWholeMilliseconds.coerceAtLeast(0),
            results = results,
        )
        registry.update(report)

        val chat = report.results[GatewayCapability.Chat]
        if (chat?.state == CapabilityProbeState.Supported) {
            health.recordSuccess(providerInstanceId, chat.latencyMs)
        } else if (chat?.state == CapabilityProbeState.Failed) {
            // Results intentionally do not retain throwable messages or bodies.
            when (chatFailureCategory) {
                AiRequestError.Category.Authentication -> health.recordAuthError(providerInstanceId)
                AiRequestError.Category.RateLimited -> health.recordRateLimit(providerInstanceId)
                AiRequestError.Category.Timeout -> health.recordTimeout(providerInstanceId)
                AiRequestError.Category.ModelUnavailable -> health.update(providerInstanceId, HealthState.ModelUnavailable)
                else -> health.recordNetworkError(providerInstanceId)
            }
        }
        return report
    }

    private fun validateEvidence(
        capability: GatewayCapability,
        observation: CapabilityProbeObservation,
    ): CapabilityProbeObservation {
        if (observation.state != CapabilityProbeState.Supported) return observation
        val validEvidence = when (capability) {
            GatewayCapability.ContextWindow -> observation.evidence == CapabilityEvidence.LiveDiscovery &&
                observation.contextLimitTokens != null && observation.contextLimitTokens > 0
            else -> observation.evidence == CapabilityEvidence.LiveInteraction
        }
        return if (validEvidence) observation else observation.copy(
            state = CapabilityProbeState.Failed,
            detail = "Provider returned support without verifiable live evidence",
            evidence = CapabilityEvidence.None,
            contextLimitTokens = null,
        )
    }

    private fun recordProbeUsage(
        providerInstanceId: String,
        modelId: String,
        capability: GatewayCapability,
        observation: CapabilityProbeObservation,
        latencyMs: Long,
    ) {
        if (observation.inputTokens == 0L && observation.outputTokens == 0L && observation.actualCostUsd == null) return
        val estimated = observation.actualCostUsd ?: usage.estimateCost(
            ModelCapabilityCatalog.lookup(modelId), observation.inputTokens, observation.outputTokens,
        )
        usage.record(
            UsageRecord(
                id = "probe-${nowEpochMs()}-${Random.nextLong()}",
                epochMs = nowEpochMs(),
                providerInstanceId = providerInstanceId,
                modelId = modelId,
                taskType = "capability_probe:${capability.name.lowercase()}",
                inputTokens = observation.inputTokens,
                outputTokens = observation.outputTokens,
                cachedTokens = observation.cachedTokens,
                latencyMs = latencyMs,
                success = observation.state == CapabilityProbeState.Supported,
                estimatedCostUsd = estimated,
                isEstimate = observation.actualCostUsd == null,
            ),
        )
    }
}
