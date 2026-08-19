package com.inspiredandroid.kai.error

import com.inspiredandroid.kai.gateway.AiRequestError
import com.inspiredandroid.kai.sandbox.backend.SandboxError
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Unified application error model. Strings never flow across layers raw:
 * every error carries a stable machine [code], a safe [userMessage] suitable
 * for direct display, optional debug-only [debugMetadata] and a
 * [correlationId] that ties a UI error back to the underlying request run.
 *
 * No stack traces and no secrets are exposed to the user-facing message.
 */
data class AppError(
    val code: String,
    val userMessage: String,
    val debugMetadata: Map<String, String> = emptyMap(),
    val retryable: Boolean = false,
    val correlationId: String? = null,
    val provider: String? = null,
    override val cause: Throwable? = null,
) : Exception("[$code] $userMessage", cause) {
    companion object {
        @OptIn(ExperimentalUuidApi::class)
        fun newCorrelationId(): String = Uuid.random().toString()
    }
}

/** Convert an AI gateway error into the unified model with a stable code. */
fun AiRequestError.toAppError(provider: String? = null, correlationId: String? = null): AppError =
    when (this) {
        is AiRequestError.AuthenticationError -> AppError(
            code = "auth.invalid_key",
            userMessage = "مفتاح API غير صالح أو منتهي الصلاحية. تحقق من إعدادات المزود.",
            debugMetadata = mapOf("provider" to (provider ?: "unknown")),
            retryable = false, provider = provider, correlationId = correlationId,
        )
        is AiRequestError.RateLimitError -> AppError(
            code = "rate_limited",
            userMessage = "تم تجاوز حد الاستخدام للمزود. انتظر قليلاً ثم أعد المحاولة.",
            debugMetadata = buildMap { put("provider", provider ?: "unknown"); if (retryAfterMs != null) put("retryAfterMs", retryAfterMs.toString()) },
            retryable = true, provider = provider, correlationId = correlationId,
        )
        is AiRequestError.NetworkError -> AppError(
            code = "network.error",
            userMessage = "تعذر الاتصال بالشبكة. تحقق من اتصال الإنترنت والمزود.",
            debugMetadata = mapOf("provider" to (provider ?: "unknown")),
            retryable = true, provider = provider, correlationId = correlationId, cause = cause,
        )
        is AiRequestError.TimeoutError -> AppError(
            code = "network.timeout",
            userMessage = "انتهت مهلة الطلب. أعد المحاولة.",
            debugMetadata = mapOf("provider" to (provider ?: "unknown")),
            retryable = true, provider = provider, correlationId = correlationId,
        )
        is AiRequestError.ModelUnavailableError -> AppError(
            code = "model.unavailable",
            userMessage = "النموذج غير متاح حاليًا. جرّب نموذجًا آخر.",
            debugMetadata = mapOf("provider" to (provider ?: "unknown")),
            retryable = false, provider = provider, correlationId = correlationId,
        )
        is AiRequestError.ContentModerationError -> AppError(
            code = "content.moderation",
            userMessage = "رفض المزود المحتوى لأسباب تتعلق بسياسة الاستخدام.",
            debugMetadata = mapOf("provider" to (provider ?: "unknown")),
            retryable = false, provider = provider, correlationId = correlationId,
        )
        is AiRequestError.ContextExceededError -> AppError(
            code = "context.exceeded",
            userMessage = "تجاوزت المحادثة حد السياق. ابدأ محادثة جديدة أو فعّل الضغط التلقائي.",
            debugMetadata = buildMap { put("provider", provider ?: "unknown"); if (contextTokens != null) put("contextTokens", contextTokens.toString()) },
            retryable = false, provider = provider, correlationId = correlationId,
        )
        is AiRequestError.InvalidRequestError -> AppError(
            code = "request.invalid",
            userMessage = "طلب غير صالح. راجع الإعدادات وأعد المحاولة.",
            debugMetadata = mapOf("provider" to (provider ?: "unknown")),
            retryable = false, provider = provider, correlationId = correlationId,
        )
        is AiRequestError.CancelledError -> AppError(
            code = "request.cancelled",
            userMessage = "تم إلغاء الطلب.",
            retryable = false, provider = provider, correlationId = correlationId,
        )
        is AiRequestError.LocalModelProblemError -> AppError(
            code = "local_model.problem",
            userMessage = "تعذر استخدام النموذج المحلي. تحقق من التثبيت والذاكرة المتاحة.",
            debugMetadata = mapOf("provider" to (provider ?: "local")),
            retryable = false, provider = provider, correlationId = correlationId, cause = cause,
        )
        is AiRequestError.UsageLimitError -> AppError(
            code = "usage.limit",
            userMessage = "تم تجاوز الحد المسموح للاستخدام في هذه الجلسة.",
            debugMetadata = mapOf("provider" to (provider ?: "unknown")),
            retryable = false, provider = provider, correlationId = correlationId,
        )
        is AiRequestError.UnknownError -> AppError(
            code = "request.unknown",
            userMessage = "حدث خطأ غير متوقع مع المزود. أعد المحاولة.",
            debugMetadata = mapOf("provider" to (provider ?: "unknown")),
            retryable = true, provider = provider, correlationId = correlationId, cause = cause,
        )
    }

/** Convert a sandbox error into the unified model with a stable code. */
fun SandboxError.toAppError(correlationId: String? = null): AppError =
    when (this) {
        is SandboxError.AuthError -> AppError(code = "sandbox.auth", userMessage = "فشل المصادقة مع بيئة التشغيل البعيدة.", retryable = true, correlationId = correlationId, cause = this)
        is SandboxError.RateLimitError -> AppError(code = "sandbox.rate_limited", userMessage = "بيئة التشغيل قيد حد الاستخدام. أعد المحاولة لاحقًا.", retryable = true, correlationId = correlationId)
        is SandboxError.ProviderUnavailable -> AppError(code = "sandbox.unavailable", userMessage = "بيئة التشغيل غير متاحة حاليًا.", retryable = true, correlationId = correlationId, cause = this)
        is SandboxError.ModelUnavailable -> AppError(code = "sandbox.model_missing", userMessage = "النموذج المحلي غير مثبت. حمّله من الإعدادات.", retryable = false, correlationId = correlationId)
        is SandboxError.NetworkError -> AppError(code = "network.error", userMessage = "تعذر الاتصال ببيئة التشغيل. تحقق من الشبكة.", retryable = true, correlationId = correlationId, cause = this)
        is SandboxError.SandboxUnavailable -> AppError(code = "sandbox.unavailable", userMessage = "بيئة التشغيل غير متاحة. أعد المحاولة.", retryable = true, correlationId = correlationId)
        is SandboxError.SandboxTimeout -> AppError(code = "sandbox.timeout", userMessage = "انتهت مهلة بيئة التشغيل.", retryable = true, correlationId = correlationId)
        is SandboxError.SandboxResourceLimit -> AppError(code = "sandbox.resource_limit", userMessage = "تجاوزت مهمة بيئية حد مواردها ($resource).", debugMetadata = mapOf("resource" to resource), retryable = false, correlationId = correlationId)
        is SandboxError.CommandFailed -> AppError(code = "sandbox.command_failed", userMessage = "فشل تنفيذ الأمر بشفرة خروج $exitCode.", retryable = false, correlationId = correlationId)
        is SandboxError.ProcessFailed -> AppError(code = "sandbox.process_failed", userMessage = "انتهت العملية بخلل.", retryable = true, correlationId = correlationId)
        is SandboxError.PermissionDenied -> AppError(code = "sandbox.permission_denied", userMessage = "عملية مرفوضة بسبب سياسة الصلاحيات.", retryable = false, correlationId = correlationId)
        is SandboxError.PolicyDenied -> AppError(code = "policy.denied", userMessage = "تم رفض العملية لأن سياسة الأمان تمنعها: $reason.", retryable = false, correlationId = correlationId, cause = this)
        is SandboxError.IntegrityError -> AppError(code = "integrity.failed", userMessage = "فشل التحقق من سلامة البيانات.", retryable = false, correlationId = correlationId)
        is SandboxError.ConfigurationError -> AppError(code = "config.error", userMessage = "خطأ في الإعدادات: $field.", retryable = false, correlationId = correlationId)
    }

/** Redact secrets from a free-form message before it reaches release logs. */
internal fun redactSecrets(message: String): String {
    val cleaned = SECRET_PATTERNS.fold(message) { acc, pattern -> pattern.replace(acc, "") }
    return cleaned
}

private val SECRET_PATTERNS = listOf(
    Regex("sk-[A-Za-z0-9_-]{20,}"),
    Regex("ghp_[A-Za-z0-9]{20,}"),
    Regex("github_pat_[A-Za-z0-9_]{20,}"),
    Regex("Bearer [A-Za-z0-9._-]{20,}"),
    Regex("x-api-key:\\s*[A-Za-z0-9._-]{20,}"),
    Regex("AIza[\\w-]{30,}"),
    Regex("(?i)api[_\\-]?key\\s*[=:]\\s*[A-Za-z0-9._-]{8,}"),
)
