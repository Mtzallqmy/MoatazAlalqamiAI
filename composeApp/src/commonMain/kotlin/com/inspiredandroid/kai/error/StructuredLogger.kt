package com.inspiredandroid.kai.error

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Structured logger with request-level correlation IDs and secret redaction.
 *
 * Every run/request is traced through a chain of IDs:
 * `conversationId -> runId -> requestId -> (stepId / toolCallId)`
 *
 * Release builds receive redacted, safe entries only; a debug build can
 * enable [debugMode] for richer payloads that stay off device by default.
 */
object StructuredLogger {
    var debugMode: Boolean = false

    /** Registered sink for structured events (replace in tests). */
    @Volatile
    var sink: (Map<String, String>) -> Unit = { entry ->
        val level = entry["level"] ?: "info"
        if (debugMode || level == "error") {
            val json = Json.encodeToString(buildJsonObject { entry.forEach { (k, v) -> put(k, v) } })
            println("[MA-AI][$level] $json")
        }
    }

    fun event(
        level: String = "info",
        type: String,
        conversationId: String? = null,
        runId: String? = null,
        requestId: String? = null,
        stepId: String? = null,
        toolCallId: String? = null,
        provider: String? = null,
        model: String? = null,
        durationMs: Long? = null,
        fallback: Boolean? = null,
        policy: String? = null,
        errorCode: String? = null,
        extra: Map<String, String> = emptyMap(),
    ) {
        val entry = buildMap<String, String> {
            put("level", level)
            put("type", type)
            conversationId?.let { put("conversationId", it) }
            runId?.let { put("runId", it) }
            requestId?.let { put("requestId", it) }
            stepId?.let { put("stepId", it) }
            toolCallId?.let { put("toolCallId", it) }
            provider?.let { put("provider", it) }
            model?.let { put("model", it) }
            durationMs?.let { put("durationMs", it.toString()) }
            fallback?.let { put("fallback", it.toString()) }
            policy?.let { put("policy", it) }
            errorCode?.let { put("errorCode", it) }
            extra.forEach { (k, v) -> put(k, redactSecrets(v)) }
        }
        sink(entry)
    }

    /** Log an [AppError] as a structured event without ever exposing secrets. */
    fun appError(appError: AppError, type: String = "app.error") {
        event(
            level = "error",
            type = type,
            requestId = appError.correlationId,
            provider = appError.provider,
            errorCode = appError.code,
            extra = appError.debugMetadata,
        )
    }
}
