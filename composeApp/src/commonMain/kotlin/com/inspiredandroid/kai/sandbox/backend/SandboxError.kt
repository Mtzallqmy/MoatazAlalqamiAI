package com.inspiredandroid.kai.sandbox.backend

import kotlin.time.Duration

/**
 * Unified error hierarchy for every sandbox backend. Agent tooling and the
 * orchestrator pattern-match on this rather than backend-specific exceptions,
 * which keeps retry/escalation logic backend-agnostic.
 */
sealed class SandboxError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    class AuthError(message: String) : SandboxError(message)
    class RateLimitError(val retryAfter: Duration?) : SandboxError("Rate limited${retryAfter?.let { ", retry after $it" } ?: ""}")
    class ProviderUnavailable(message: String) : SandboxError(message)
    class ModelUnavailable(val modelId: String, message: String) : SandboxError(message)
    class NetworkError(cause: Throwable? = null) : SandboxError("Network error", cause)
    class SandboxUnavailable(val sandboxId: String, message: String) : SandboxError(message)
    class SandboxTimeout(val sandboxId: String, val elapsed: Duration, val limit: Duration) : SandboxError("Sandbox $sandboxId timed out after $elapsed (limit $limit)")
    class SandboxResourceLimit(val resource: String, val current: Long, val limit: Long) : SandboxError("$resource limit exceeded: $current > $limit")
    class CommandFailed(val exitCode: Int, val stdout: String, val stderr: String) : SandboxError("Command exited with code $exitCode")
    class ProcessFailed(val pid: Long, val signal: String) : SandboxError("Process $pid failed with signal $signal")
    class PermissionDenied(val path: String) : SandboxError("Permission denied: $path")
    class PolicyDenied(val policy: String, val reason: String) : SandboxError("Policy denied ($policy): $reason")
    class IntegrityError(val expected: String, val actual: String) : SandboxError("Integrity mismatch: expected $expected, got $actual")
    class ConfigurationError(val field: String, val reason: String) : SandboxError("Configuration error on '$field': $reason")
}
