package com.inspiredandroid.kai.gateway

import com.inspiredandroid.kai.data.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

typealias Settings = com.russhwolf.settings.Settings

/**
 * Provider health snapshot. Used by the router (health-aware selection), the
 * settings dashboard, and the Home provider-health summary.
 */
enum class HealthState {
    Unknown,
    Connected,
    AuthError,
    NetworkError,
    RateLimited,
    ModelUnavailable,
    Disabled,
}

/**
 * Health record for one service instance. Persisted to settings on update so
 * the dashboard survives restarts without polling on every launch.
 */
@Serializable
data class ProviderHealthRecord(
    val instanceId: String,
    val state: HealthState = HealthState.Unknown,
    val latencyMs: Long? = null,
    val lastCheckEpochMs: Long = 0L,
    val modelsCount: Int? = null,
    val isRateLimited: Boolean = false,
    /** Cooldown: skip health checks until this epoch (protects paid quotas). */
    val cooldownUntilEpochMs: Long = 0L,
)

/**
 * Tracks per-instance provider health with an in-memory state flow plus
 * a persisted, throttled snapshot in [AppSettings].
 *
 * Health checks are *never* scheduled automatically — they only happen when
 * the user explicitly runs Test Connection or a live request observes an
 * error. This keeps the app from burning the user's API quota on polling.
 */
class ProviderHealthRegistry(
    settings: AppSettings,
) {
    private val settings: Settings = settings.settings
    private val _records = MutableStateFlow<Map<String, ProviderHealthRecord>>(emptyMap())
    val records: StateFlow<Map<String, ProviderHealthRecord>> = _records.asStateFlow()

    fun recordFor(instanceId: String): ProviderHealthRecord {
        val flowRecord = _records.value[instanceId]
        if (flowRecord != null) return flowRecord
        return loadPersisted(instanceId)
    }

    fun update(
        instanceId: String,
        state: HealthState,
        latencyMs: Long? = null,
        modelsCount: Int? = null,
    ) {
        val now = System.currentTimeMillis()
        val record = ProviderHealthRecord(
            instanceId = instanceId,
            state = state,
            latencyMs = latencyMs,
            lastCheckEpochMs = now,
            modelsCount = modelsCount ?: recordFor(instanceId).modelsCount,
            isRateLimited = state == HealthState.RateLimited,
            cooldownUntilEpochMs = if (state == HealthState.RateLimited) now + RATE_LIMIT_COOLDOWN_MS else 0L,
        )
        _records.value = _records.value + (instanceId to record)
        persist(record)
    }

    fun recordRateLimit(instanceId: String) {
        update(instanceId, HealthState.RateLimited)
    }

    fun recordAuthError(instanceId: String) {
        update(instanceId, HealthState.AuthError)
    }

    fun recordNetworkError(instanceId: String) {
        update(instanceId, HealthState.NetworkError)
    }

    /** Whether a request to this instance should route elsewhere due to health. */
    fun isUnhealthy(instanceId: String): Boolean {
        val record = recordFor(instanceId)
        if (record.state == HealthState.AuthError || record.state == HealthState.ModelUnavailable) return true
        if (record.isRateLimited && System.currentTimeMillis() < record.cooldownUntilEpochMs) return true
        return false
    }

    /** Seconds until a rate-limited instance can be retried, or 0. */
    fun rateLimitRetryInMs(instanceId: String): Long {
        val record = recordFor(instanceId)
        val now = System.currentTimeMillis()
        return if (record.isRateLimited && record.cooldownUntilEpochMs > now) record.cooldownUntilEpochMs - now else 0L
    }

    private fun persist(record: ProviderHealthRecord) {
        // Best-effort persistence: a single latest snapshot per instance, inline format.
        try {
            settings.putString(KEY_HEALTH_PREFIX + record.instanceId, healthJson(record))
        } catch (_: Exception) {
            // Persistence failure must never break the in-memory registry.
        }
    }

    private fun loadPersisted(instanceId: String): ProviderHealthRecord {
        val raw = try { settings.getStringOrNull(KEY_HEALTH_PREFIX + instanceId) } catch (_: Exception) { null }
        return if (!raw.isNullOrBlank()) parseHealthJson(raw) else ProviderHealthRecord(instanceId)
    }

    /** Serialize with a minimal inline format to avoid pulling another dependency. */
    private fun healthJson(record: ProviderHealthRecord): String =
        "${record.state.name}|${record.latencyMs ?: ""}|${record.lastCheckEpochMs}|${record.modelsCount ?: ""}|${record.cooldownUntilEpochMs}"

    private fun parseHealthJson(raw: String): ProviderHealthRecord {
        val parts = raw.split("|")
        val state = runCatching { HealthState.valueOf(parts.getOrElse(0) { "Unknown" }) }.getOrDefault(HealthState.Unknown)
        val latency = parts.getOrElse(1) { "" }.toLongOrNull()
        val lastCheck = parts.getOrElse(2) { "0" }.toLong()
        val modelsCount = parts.getOrElse(3) { "" }.toIntOrNull()
        val cooldown = parts.getOrElse(4) { "0" }.toLong()
        val instanceId = ""
        return ProviderHealthRecord(instanceId, state, latency, lastCheck, modelsCount, state == HealthState.RateLimited, cooldown)
    }

    companion object {
        private const val KEY_HEALTH_PREFIX = "provider_health_"
        /** Default cooldown after a 429: 5 minutes (or use Retry-After when known). */
        private const val RATE_LIMIT_COOLDOWN_MS = 5 * 60 * 1000L
    }
}
