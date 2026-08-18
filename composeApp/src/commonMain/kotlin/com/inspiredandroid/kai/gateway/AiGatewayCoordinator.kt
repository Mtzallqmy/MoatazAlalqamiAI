package com.inspiredandroid.kai.gateway

import com.inspiredandroid.kai.data.AppSettings
import com.inspiredandroid.kai.security.ProviderCredentialsResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * Coordinates one complete AI request end-to-end: routing, credential
 * resolution, execution, error classification, fallback, usage recording and
 * provider-health updates.
 *
 * The coordinator intentionally does NOT perform the network call itself — the
 * repository injects an [AiRequestExecutor] lambda so request building stays
 * in [RemoteDataRepository] (which already owns protocol knowledge, retries at
 * the transport layer, and the tool-calling loop). The coordinator owns the
 * *decision* layer.
 */
class AiGatewayCoordinator(
    private val router: ModelRouter,
    private val usage: UsageRecorder,
    private val health: ProviderHealthRegistry,
    private val credentials: ProviderCredentialsResolver,
    private val strategy: FallbackStrategy = FallbackStrategy.chatDefaults(),
) {

    /**
     * Outcome of a coordinated AI request. [error] carries the final
     * classified error when [success] is false, along with the candidates
     * that were tried so the caller can surface an accurate message.
     */
    data class Outcome(
        val success: Boolean,
        val triedCandidates: List<TriedCandidate>,
        val finalError: AiRequestError?,
        val requiresCompaction: Boolean,
        val decision: RoutingDecision,
    )

    data class TriedCandidate(
        val candidate: ModelCandidate,
        val error: AiRequestError? = null,
    )

    /**
     * Resolves routing for [message] against the currently configured
     * instances and returns the ordered decision (primary + fallback chain).
     * No network activity — callers use this to preview the selected model.
     */
    fun planRequest(
        message: String,
        hasVisionInput: Boolean,
        requiresTools: Boolean,
        contextTokens: Int,
        configuredInstances: List<String>,
        instanceServiceIds: Map<String, String>,
        profileId: RoutingProfileId? = null,
    ): RoutingDecision {
        val taskType = TaskClassifier.classify(message)
        return router.selectAllCandidates(
            taskType = taskType,
            hasVisionInput = hasVisionInput || TaskClassifier.hasVisionHint(message),
            requiresTools = requiresTools,
            contextTokens = contextTokens,
            profileId = profileId ?: router.currentProfile().profileId,
            configuredInstances = configuredInstances,
            instanceServiceIds = instanceServiceIds,
        )
    }

    /**
     * Executes the request against the routing decision: tries the primary
     * candidate, falling back per [FallbackStrategy], until success, a hard
     * failure, or exhaustion. Updates provider health and usage records.
     *
     * @param executor performs one request attempt. Must translate its raw
     *   exceptions into [AiRequestError] via [classifyRequestError] before
     *   returning — it may also return `null` to indicate a hard transport
     *   failure that should still participate in fallback.
     * @throws CancellationException when the user cancels — never wrapped.
     */
    suspend fun execute(
        decision: RoutingDecision,
        executor: suspend (instanceId: String, modelId: String) -> AiRequestOutcome,
    ): Outcome {
        val tried = mutableListOf<TriedCandidate>()
        val candidates = buildList {
            val primary = decision.primary
            if (primary != null) add(primary)
            addAll(decision.fallbackChain)
        }
        if (candidates.isEmpty()) {
            return Outcome(
                success = false,
                triedCandidates = emptyList(),
                finalError = AiRequestError.UnknownError("No model candidates available"),
                requiresCompaction = false,
                decision = decision,
            )
        }

        var attemptOnCurrent = 0
        var totalAttempts = 0
        var requiresCompaction = false

        var index = 0
        while (index < candidates.size) {
            val candidate = candidates[index]
            try {
                val resolvedModel = resolveModelForCandidate(candidate)
                val attempt = executor(candidate.providerInstanceId, resolvedModel)

                if (attempt.success) {
                    health.recordSuccess(candidate.providerInstanceId, attempt.latencyMs)
                    recordUsage(candidate, resolvedModel, success = true,
                        inputTokens = attempt.inputTokens, outputTokens = attempt.outputTokens,
                        cachedTokens = attempt.cachedTokens, latencyMs = attempt.latencyMs,
                        actualCostUsd = attempt.costUsd)
                    return Outcome(
                        success = true,
                        triedCandidates = tried,
                        finalError = null,
                        requiresCompaction = false,
                        decision = decision,
                    )
                }

                val error = attempt.error ?: AiRequestError.NetworkError()
                health.recordError(candidate.providerInstanceId, error)

                val context = FallbackStrategy.AttemptContext(
                    attemptsOnCurrent = attemptOnCurrent,
                    totalAttempts = totalAttempts,
                    candidatesRemaining = candidates.size - index - 1,
                    lastError = error,
                )
                val action = strategy.decide(error, context)
                tried += TriedCandidate(candidate, error)
                totalAttempts++

                when (action) {
                    FallbackAction.Abort -> return Outcome(false, tried, error, requiresCompaction, decision)
                    FallbackAction.RetrySame -> {
                        attemptOnCurrent++
                        applyBackoff(index + 1)
                    }
                    FallbackAction.RetryWithAdjustment -> {
                        requiresCompaction = true
                        return Outcome(false, tried, error, requiresCompaction = true, decision)
                    }
                    FallbackAction.TryNextCandidate -> {
                        index++
                        attemptOnCurrent = 0
                    }
                }
            } catch (ce: CancellationException) {
                throw ce // never swallow user cancellation
            } catch (t: Throwable) {
                val error = classifyRequestError(t)
                health.recordError(candidates[index].providerInstanceId, error)
                tried += TriedCandidate(candidates[index], error)
                totalAttempts++
                if (error.isHardFailure()) {
                    return Outcome(false, tried, error, false, decision)
                }
                if (error.requiresCompaction()) {
                    return Outcome(false, tried, error, true, decision)
                }
                index++
                attemptOnCurrent = 0
            }
        }

        val lastError = tried.lastOrNull()?.error
            ?: AiRequestError.UnknownError("All candidates exhausted without a usable response")
        return Outcome(false, tried, lastError, requiresCompaction, decision)
    }

    /** The user's configured model (or the curated default) for this candidate. */
    private suspend fun resolveModelForCandidate(candidate: ModelCandidate): String {
        val configured = runCatching { credentials.resolveInstanceModelId(candidate.providerInstanceId) }.getOrNull().orEmpty()
        return configured.ifBlank { candidate.modelId }
    }

    /** Current routing profile config — exposed for pre-request budget gates. */
    val usageRecorder: UsageRecorder get() = usage

    /** Exposed so the repository can resolve credentials through the coordinator. */
    val credentialResolver: ProviderCredentialsResolver get() = innerCredentials

    private val innerCredentials: ProviderCredentialsResolver = credentials

    fun currentProfile(): RoutingProfileConfig = router.currentProfile()

    /** Convenience adapters so the repository never reaches past this layer. */
    fun recordProviderSuccess(instanceId: String) {
        health.recordSuccess(instanceId)
    }

    fun recordProviderError(instanceId: String, error: Throwable) {
        health.recordError(instanceId, classifyRequestError(error))
    }

    /**
     * Non-suspending usage entry point used by the repository's legacy paths.
     * Model id resolution is suspend, so the recorder gets "unknown" here — the
     * suspended [execute] path records the real model id instead.
     */
    fun recordUsageFor(instanceId: String, serviceId: String) {
        val config = router.currentProfile()
        val modelId = "unknown"
        usage.record(
            UsageRecord(
                id = "${System.currentTimeMillis()}-${Random.nextLong()}",
                epochMs = System.currentTimeMillis(),
                providerInstanceId = instanceId,
                modelId = modelId.ifBlank { "unknown" },
                taskType = "chat",
                routingProfile = config.profileId.name,
                inputTokens = 0L,
                outputTokens = 0L,
                cachedTokens = null,
                latencyMs = null,
                success = true,
                estimatedCostUsd = 0.0,
                isEstimate = true,
            ),
        )
    }

    private suspend fun applyBackoff(retryNumber: Int) {
        // Jittered linear backoff: ~1.5s, ~3s, capped — keeps UX responsive.
        val base = 1500L * retryNumber.coerceAtMost(3)
        val jitter = Random.nextLong(200L, 800L)
        delay(base + jitter)
    }

    private fun recordUsage(
        candidate: ModelCandidate,
        modelId: String,
        success: Boolean,
        inputTokens: Long,
        outputTokens: Long,
        cachedTokens: Long?,
        latencyMs: Long?,
        actualCostUsd: Double?,
    ) {
        val isEstimate = actualCostUsd == null || inputTokens == 0L && outputTokens == 0L
        val cost = actualCostUsd ?: usage.estimateCost(
            ModelCapabilityCatalog.lookup(modelId), inputTokens, outputTokens,
        )
        usage.record(
            UsageRecord(
                id = "${System.currentTimeMillis()}-${Random.nextLong()}",
                epochMs = System.currentTimeMillis(),
                providerInstanceId = candidate.providerInstanceId,
                modelId = modelId,
                taskType = candidate.profileId.name,
                routingProfile = candidate.profileId.name,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                cachedTokens = cachedTokens,
                latencyMs = latencyMs,
                success = success,
                estimatedCostUsd = cost,
                isEstimate = isEstimate,
            ),
        )
    }

    companion object {
        fun create(
            settings: AppSettings,
            credentials: ProviderCredentialsResolver,
            strategy: FallbackStrategy = FallbackStrategy.chatDefaults(),
        ): AiGatewayCoordinator = AiGatewayCoordinator(
            router = ModelRouter(settings, ProviderHealthRegistry(settings)),
            usage = UsageRecorder(settings),
            health = ProviderHealthRegistry(settings),
            credentials = credentials,
            strategy = strategy,
        )
    }
}

/**
 * The result of one request attempt, as returned by the injected executor.
 * [success]=false with [error]=null means the transport itself failed — the
 * coordinator still applies fallback logic.
 */
data class AiRequestOutcome(
    val success: Boolean,
    val inputTokens: Long = 0L,
    val outputTokens: Long = 0L,
    val cachedTokens: Long? = null,
    val latencyMs: Long? = null,
    val costUsd: Double? = null,
    val error: AiRequestError? = null,
)
