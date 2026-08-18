package com.inspiredandroid.kai.gateway

/**
 * Decision taken for the current error before considering fallback.
 * Keeps fallback logic explicit: every error maps to exactly one action.
 */
enum class FallbackAction {
    /** The candidate can be retried directly (same endpoint, possibly after delay). */
    RetrySame,
    /** Move to the next candidate in the router's fallback chain. */
    TryNextCandidate,
    /** Stop immediately — the error is hard and falling back would mislead. */
    Abort,
    /** The request itself must change first (compact context, drop attachment). */
    RetryWithAdjustment,
}

/**
 * Pure fallback decision maker. Converts an [AiRequestError] and the current
 * attempt context into a single [FallbackAction] without side effects.
 */
class FallbackStrategy(
    /** Maximum attempts against one candidate before moving to the next. */
    val maxAttemptsPerCandidate: Int = 2,
    /** Absolute cap on total attempts (all candidates) before aborting. */
    val maxTotalAttempts: Int = 4,
    /** Whether retries are enabled at all (user preference / offline flag). */
    val retriesEnabled: Boolean = true,
) {
    /** Attempts used against the current candidate so far. */
    data class AttemptContext(
        val attemptsOnCurrent: Int,
        val totalAttempts: Int,
        val candidatesRemaining: Int,
        val lastError: AiRequestError?,
    )

    fun decide(error: AiRequestError, context: AttemptContext): FallbackAction {
        if (!retriesEnabled) return FallbackAction.Abort
        if (error.isHardFailure()) return FallbackAction.Abort

        if (error.requiresCompaction()) return FallbackAction.RetryWithAdjustment

        // Network/timeout errors are safe to retry on the same candidate once.
        if ((error is AiRequestError.NetworkError || error is AiRequestError.TimeoutError) &&
            context.attemptsOnCurrent < maxAttemptsPerCandidate &&
            context.totalAttempts < maxTotalAttempts
        ) {
            return FallbackAction.RetrySame
        }

        // Anything else with fallback remaining: move on.
        if (error.shouldTryFallback() && context.candidatesRemaining > 0) {
            return FallbackAction.TryNextCandidate
        }

        return FallbackAction.Abort
    }

    companion object {
        /** Reasonable default for interactive chat (fast feedback, no burn). */
        fun chatDefaults() = FallbackStrategy(maxAttemptsPerCandidate = 1, maxTotalAttempts = 3)

        /** Agent runs tolerate more retries since they are long-running. */
        fun agentDefaults() = FallbackStrategy(maxAttemptsPerCandidate = 2, maxTotalAttempts = 4)
    }
}
