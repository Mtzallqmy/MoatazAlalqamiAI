package com.inspiredandroid.kai.gateway

import com.inspiredandroid.kai.network.AllServicesFailedException
import com.inspiredandroid.kai.network.AnthropicApiException
import com.inspiredandroid.kai.network.AnthropicGenericException
import com.inspiredandroid.kai.network.AnthropicInsufficientCreditsException
import com.inspiredandroid.kai.network.AnthropicInvalidApiKeyException
import com.inspiredandroid.kai.network.AnthropicOverloadedException
import com.inspiredandroid.kai.network.AnthropicRateLimitExceededException
import com.inspiredandroid.kai.network.ContextWindowExceededException
import com.inspiredandroid.kai.network.FileTooLargeException
import com.inspiredandroid.kai.network.GeminiApiException
import com.inspiredandroid.kai.network.GeminiInvalidApiKeyException
import com.inspiredandroid.kai.network.GeminiRateLimitExceededException
import com.inspiredandroid.kai.network.OpenAICompatibleApiException
import com.inspiredandroid.kai.network.OpenAICompatibleBadRequestException
import com.inspiredandroid.kai.network.OpenAICompatibleConnectionException
import com.inspiredandroid.kai.network.OpenAICompatibleContentModerationException
import com.inspiredandroid.kai.network.OpenAICompatibleEmptyResponseException
import com.inspiredandroid.kai.network.OpenAICompatibleGenericException
import com.inspiredandroid.kai.network.OpenAICompatibleInvalidApiKeyException
import com.inspiredandroid.kai.network.OpenAICompatibleModelNotFoundException
import com.inspiredandroid.kai.network.OpenAICompatibleQuotaExhaustedException
import com.inspiredandroid.kai.network.OpenAICompatibleRateLimitExceededException
import com.inspiredandroid.kai.network.OpenAICompatibleRequestTooLargeException
import com.inspiredandroid.kai.network.OpenAICompatibleServiceUnavailableException
import com.inspiredandroid.kai.network.OpenAICompatibleTimeoutException
import com.inspiredandroid.kai.inference.InferenceTimeoutException
import com.inspiredandroid.kai.inference.InsufficientMemoryException
import com.inspiredandroid.kai.inference.ModelIntegrityException
import com.inspiredandroid.kai.inference.NoModelDownloadedException
import kotlinx.coroutines.CancellationException

/**
 * Stable internal error taxonomy for every AI request, independent of the
 * provider's raw exception type. Used by the fallback strategy, the provider
 * health registry, and the UI — so classification logic lives in one place.
 *
 * Categorized error: never stores raw response bodies or headers.
 */
sealed class AiRequestError(
    open val category: Category,
    override open val message: String? = null,
) : Exception(message) {
    enum class Category {
        /** Credential or authorization problem. Never retry with the same credentials. */
        Authentication,
        /** Provider asked to slow down. Apply cooldown, may fall back. */
        RateLimited,
        /** Transient network problem (socket, DNS, connection refused). Limited retry. */
        Network,
        /** The request timed out waiting for a response. Limited retry. */
        Timeout,
        /** The requested model no longer exists / is not available on this endpoint. */
        ModelUnavailable,
        /** Provider refused the content (moderation, safety filter). NOT transient. */
        ContentModeration,
        /** The prompt exceeds the model's context window. Compact before fallback. */
        ContextExceeded,
        /** The request itself is malformed (bad body, bad params). Do not fallback. */
        InvalidRequest,
        /** The user cancelled the request. Never treat as a generic failure. */
        Cancelled,
        /** Local/on-device model could not be used (missing, corrupt, OOM). */
        LocalModelProblem,
        /** Usage/budget guardrail blocked the request. */
        UsageLimit,
        /** Everything below failed or the provider rejected it for an opaque reason. */
        Unknown,
    }

    data class AuthenticationError(override val message: String? = null) :
        AiRequestError(Category.Authentication, message)

    data class RateLimitError(val retryAfterMs: Long? = null, override val message: String? = null) :
        AiRequestError(Category.RateLimited, message)

    data class NetworkError(override val message: String? = null) :
        AiRequestError(Category.Network, message)

    data class TimeoutError(override val message: String? = null) :
        AiRequestError(Category.Timeout, message)

    data class ModelUnavailableError(override val message: String? = null) :
        AiRequestError(Category.ModelUnavailable, message)

    data class ContentModerationError(override val message: String? = null) :
        AiRequestError(Category.ContentModeration, message)

    data class ContextExceededError(val contextTokens: Int? = null, override val message: String? = null) :
        AiRequestError(Category.ContextExceeded, message)

    data class InvalidRequestError(override val message: String? = null) :
        AiRequestError(Category.InvalidRequest, message)

    data class CancelledError(val userCancelled: Boolean = true) :
        AiRequestError(Category.Cancelled, if (userCancelled) "Cancelled by the user" else null)

    data class LocalModelProblemError(override val message: String? = null) :
        AiRequestError(Category.LocalModelProblem, message)

    data class UsageLimitError(override val message: String? = null) :
        AiRequestError(Category.UsageLimit, message)

    data class UnknownError(override val message: String? = null) :
        AiRequestError(Category.Unknown, message)
}

/**
 * Maps any thrown [Throwable] into the stable [AiRequestError] taxonomy.
 * Does not log or surface provider secrets.
 */
fun classifyRequestError(cause: Throwable): AiRequestError {
    if (cause is AiRequestError) return cause // already classified — no double-wrapping
    if (cause is CancellationException) return AiRequestError.CancelledError(userCancelled = true)

    return when (cause) {
        // --- OpenAI-compatible family ---
        is OpenAICompatibleInvalidApiKeyException -> AiRequestError.AuthenticationError(cause.message)
        is OpenAICompatibleRateLimitExceededException -> AiRequestError.RateLimitError(message = cause.message)
        is OpenAICompatibleQuotaExhaustedException -> AiRequestError.RateLimitError(message = cause.message)
        is OpenAICompatibleConnectionException -> AiRequestError.NetworkError(cause.message)
        is OpenAICompatibleTimeoutException -> AiRequestError.TimeoutError(cause.message)
        is OpenAICompatibleModelNotFoundException -> AiRequestError.ModelUnavailableError(cause.message)
        is OpenAICompatibleContentModerationException -> AiRequestError.ContentModerationError(cause.message)
        is OpenAICompatibleRequestTooLargeException -> AiRequestError.ContextExceededError(message = cause.message)
        is OpenAICompatibleBadRequestException -> AiRequestError.InvalidRequestError(cause.message)
        is OpenAICompatibleServiceUnavailableException -> AiRequestError.NetworkError(cause.message)

        // --- Gemini family ---
        is GeminiInvalidApiKeyException -> AiRequestError.AuthenticationError(cause.message)
        is GeminiRateLimitExceededException -> AiRequestError.RateLimitError(message = cause.message)

        // --- Anthropic family ---
        is AnthropicInvalidApiKeyException -> AiRequestError.AuthenticationError(cause.message)
        is AnthropicRateLimitExceededException -> AiRequestError.RateLimitError(message = cause.message)
        is AnthropicOverloadedException -> AiRequestError.RateLimitError(message = cause.message)
        is AnthropicInsufficientCreditsException -> AiRequestError.RateLimitError(message = cause.message)

        // --- Local inference family ---
        is InferenceTimeoutException -> AiRequestError.TimeoutError(cause.message)
        is ModelIntegrityException -> AiRequestError.LocalModelProblemError(cause.message)
        is NoModelDownloadedException -> AiRequestError.LocalModelProblemError(cause.message)
        is InsufficientMemoryException -> AiRequestError.LocalModelProblemError(cause.message)

        // --- Semantic errors ---
        is ContextWindowExceededException -> AiRequestError.ContextExceededError(message = cause.message)

        // --- Fallback exhausted ---
        is AllServicesFailedException -> AiRequestError.UnknownError(cause.message)

        else -> {
            // Map well-known transient exception shapes even when they are not
            // one of the typed families (keeps fallback correct across layers).
            val name = cause::class.simpleName.orEmpty()
            val lowerMessage = (cause.message ?: "").lowercase()
            when {
                "Timeout" in name || "timeout" in lowerMessage -> AiRequestError.TimeoutError(cause.message)
                name.startsWith("EOF") || "socket" in lowerMessage -> AiRequestError.NetworkError(cause.message)
                "Unauthorized" in name || "401" in cause.message.orEmpty() -> AiRequestError.AuthenticationError(cause.message)
                "429" in cause.message.orEmpty() -> AiRequestError.RateLimitError(message = cause.message)
                "Model not found" in cause.message.orEmpty() -> AiRequestError.ModelUnavailableError(cause.message)
                "context" in lowerMessage && ("length" in lowerMessage || "window" in lowerMessage || "exceed" in lowerMessage) ->
                    AiRequestError.ContextExceededError(message = cause.message)
                "Content moderation" in cause.message.orEmpty() || "content_filter" in lowerMessage ->
                    AiRequestError.ContentModerationError(cause.message)
                "400" in cause.message.orEmpty() -> AiRequestError.InvalidRequestError(cause.message)
                else -> AiRequestError.UnknownError(cause.message)
            }
        }
    }
}

/** Whether the error is transient enough that a retry (or fallback retry) makes sense. */
fun AiRequestError.isRetryable(): Boolean = when (this) {
    is AiRequestError.NetworkError -> true
    is AiRequestError.TimeoutError -> true
    is AiRequestError.RateLimitError -> true
    is AiRequestError.ModelUnavailableError -> true
    is AiRequestError.LocalModelProblemError -> false
    is AiRequestError.AuthenticationError -> false
    is AiRequestError.ContentModerationError -> false
    is AiRequestError.ContextExceededError -> false
    is AiRequestError.InvalidRequestError -> false
    is AiRequestError.CancelledError -> false
    is AiRequestError.UsageLimitError -> false
    is AiRequestError.UnknownError -> false
}

/**
 * Whether a provider failure justifies falling back to the next candidate
 * (transient provider-side problem). Authentication and malformed requests
 * must not silently move to another provider without surfacing the cause.
 */
fun AiRequestError.shouldTryFallback(): Boolean = when (this) {
    is AiRequestError.NetworkError -> true
    is AiRequestError.TimeoutError -> true
    is AiRequestError.RateLimitError -> true
    is AiRequestError.ModelUnavailableError -> true
    is AiRequestError.ContentModerationError -> false
    is AiRequestError.ContextExceededError -> false
    is AiRequestError.AuthenticationError -> false
    is AiRequestError.InvalidRequestError -> false
    is AiRequestError.CancelledError -> false
    is AiRequestError.LocalModelProblemError -> false
    is AiRequestError.UsageLimitError -> false
    is AiRequestError.UnknownError -> true
}

/** Errors where ALL fallbacks should be skipped immediately (hard failure). */
fun AiRequestError.isHardFailure(): Boolean = when (this) {
    is AiRequestError.InvalidRequestError -> true
    is AiRequestError.ContentModerationError -> true
    is AiRequestError.UsageLimitError -> true
    is AiRequestError.CancelledError -> true
    else -> false
}

/** Errors that indicate a context compaction before retrying/fallback. */
fun AiRequestError.requiresCompaction(): Boolean = this is AiRequestError.ContextExceededError
