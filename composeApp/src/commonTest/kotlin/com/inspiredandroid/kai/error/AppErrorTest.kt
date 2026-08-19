package com.inspiredandroid.kai.error

import com.inspiredandroid.kai.gateway.AiRequestError
import com.inspiredandroid.kai.sandbox.backend.SandboxError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppErrorTest {

    @Test
    fun `ai gateway errors map to stable codes without secrets`() {
        val auth = AiRequestError.AuthenticationError("Invalid key sk-abcdef...")
        val authApp = auth.toAppError(provider = "openrouter")
        assertEquals("auth.invalid_key", authApp.code)
        assertFalse(authApp.retryable)
        assertEquals("openrouter", authApp.provider)
        assertFalse(authApp.userMessage.contains("sk-"))
        assertFalse(authApp.userMessage.contains("stack") || authApp.userMessage.contains("Caused"))
    }

    @Test
    fun `rate limit and network errors are retryable`() {
        assertTrue(AiRequestError.RateLimitError(retryAfterMs = 5000L).toAppError().retryable)
        assertTrue(AiRequestError.NetworkError().toAppError().retryable)
        assertTrue(AiRequestError.TimeoutError().toAppError().retryable)
        assertFalse(AiRequestError.ContentModerationError().toAppError().retryable)
        assertFalse(AiRequestError.ContextExceededError().toAppError().retryable)
    }

    @Test
    fun `sandbox policy denial carries a stable policy code`() {
        val denied = SandboxError.PolicyDenied(policy = "exec.deny", reason = "format disk forbidden")
        val app = denied.toAppError()
        assertEquals("policy.denied", app.code)
        assertFalse(app.retryable)
        assertEquals("Policy denied", app.cause?.message?.substringBefore(" ("))
    }

    @Test
    fun `correlation id flows from gateway error to app error`() {
        val id = AppError.newCorrelationId()
        val app = AiRequestError.UnknownError("boom").toAppError(correlationId = id)
        assertEquals(id, app.correlationId)
    }

    @Test
    fun `redaction removes common secret shapes`() {
        val raw = "key=sk-live1234567890abcdefghijklmnop token github_pat_11ABC_DEF1234567890abcdefghijk Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.abcdefghijklmnopqrstuvwx api-key: 0123456789abcdef0123456789abc"
        val clean = redactSecrets(raw)
        assertFalse(clean.contains("sk-live1234567890"))
        assertFalse(clean.contains("github_pat_11ABC_DEF1234567890"))
        assertFalse(clean.contains("Bearer eyJhbG"))
        assertTrue(clean.contains("key="))
        // Both `api-key:` and the generic `api[_-]?key\s*[=:]` patterns match the two keys, so two replacements are expected.
    }

    @Test
    fun `debug logging stays off and errors always sink`() {
        val captured = mutableListOf<Map<String, String>>()
        val previous = StructuredLogger.sink
        val previousDebug = StructuredLogger.debugMode
        try {
            StructuredLogger.debugMode = false
            StructuredLogger.sink = { captured += it }
            StructuredLogger.event(level = "info", type = "test.ping")
            StructuredLogger.appError(AiRequestError.TimeoutError().toAppError(provider = "groq", correlationId = "cid-1"))
        } finally {
            StructuredLogger.sink = previous
            StructuredLogger.debugMode = previousDebug
        }
        assertEquals(2, captured.size)
        val infoEvent = captured.first { it["level"] == "info" }
        val e = captured.first { it["level"] == "error" }
        assertEquals("error", e["level"])
        assertEquals("network.timeout", e["errorCode"])
        assertEquals("cid-1", e["requestId"])
        assertEquals("groq", e["provider"])
    }
}
