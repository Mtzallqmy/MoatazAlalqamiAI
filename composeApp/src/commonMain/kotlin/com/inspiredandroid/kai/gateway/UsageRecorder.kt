package com.inspiredandroid.kai.gateway

import com.inspiredandroid.kai.data.AppSettings
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * One recorded LLM request. Never stores secrets or message contents — only
 * operational metrics used by the usage dashboard and budget guardrails.
 */
@Serializable
data class UsageRecord(
    val id: String,
    val epochMs: Long,
    val providerInstanceId: String,
    val modelId: String,
    val projectId: String? = null,
    val agentId: String? = null,
    val taskType: String? = null,
    val routingProfile: String? = null,
    val inputTokens: Long = 0L,
    val outputTokens: Long = 0L,
    val cachedTokens: Long? = null,
    val latencyMs: Long? = null,
    val success: Boolean = true,
    val estimatedCostUsd: Double = 0.0,
    /** True when token counts / cost were estimated rather than reported by the provider. */
    val isEstimate: Boolean = false,
)

/**
 * Rolling usage window used for the dashboard and per-run budget checks.
 */
data class UsageWindow(
    val records: List<UsageRecord>,
    val totalInputTokens: Long,
    val totalOutputTokens: Long,
    val totalCostUsd: Double,
    val avgLatencyMs: Double?,
    val successRate: Double,
)

/**
 * Usage & cost tracking subsystem (section 23).
 *
 * Storage: a JSON list under one settings key with a capped history (last
 * [MAX_RECORDS] entries ≈ last few thousand requests). Aggregation is done on
 * read with simple filters — no database dependency required.
 */
class UsageRecorder(settings: AppSettings) {
    private val settings: com.russhwolf.settings.Settings = settings.settings

    fun record(record: UsageRecord) {
        try {
            val list = loadAll().toMutableList()
            list.add(record)
            val capped = if (list.size > MAX_RECORDS) list.takeLast(MAX_RECORDS) else list
            settings.putString(KEY_USAGE, Json.encodeToString<List<UsageRecord>>(capped))
        } catch (_: Exception) {
            // Recording failure must never break chat flow.
        }
    }

    fun loadAll(): List<UsageRecord> {
        val raw = try { settings.getStringOrNull(KEY_USAGE) } catch (_: Exception) { null }
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { Json.decodeFromString<List<UsageRecord>>(raw) }.getOrNull() ?: emptyList()
    }

    fun window(startEpochMs: Long): UsageWindow {
        val records = loadAll().filter { it.epochMs >= startEpochMs }
        val successful = records.filter { it.success }
        return UsageWindow(
            records = records,
            totalInputTokens = successful.sumOf { it.inputTokens },
            totalOutputTokens = successful.sumOf { it.outputTokens },
            totalCostUsd = successful.sumOf { it.estimatedCostUsd },
            avgLatencyMs = records.mapNotNull { it.latencyMs }.takeIf { it.isNotEmpty() }
                ?.let { it.sum().toDouble() / it.size },
            successRate = if (records.isEmpty()) 1.0 else records.count { it.success }.toDouble() / records.size,
        )
    }

    fun today(): UsageWindow = window(startOfDay())
    fun week(): UsageWindow = window(System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L)
    fun month(): UsageWindow = window(System.currentTimeMillis() - 30 * 24 * 60 * 60 * 1000L)

    /**
     * Per-provider usage with real aggregates (tokens, cost, latency, success
     * rate) — empty window per provider means the provider was not used in the
     * observed period.
     */
    fun byProvider(): Map<String, UsageWindow> {
        val all = loadAll()
        val providers = all.map { it.providerInstanceId }.distinct()
        return providers.associateWith { pid ->
            val records = all.filter { it.providerInstanceId == pid }
            val successful = records.filter { it.success }
            UsageWindow(
                records = records,
                totalInputTokens = successful.sumOf { it.inputTokens },
                totalOutputTokens = successful.sumOf { it.outputTokens },
                totalCostUsd = successful.sumOf { it.estimatedCostUsd },
                avgLatencyMs = records.mapNotNull { it.latencyMs }.takeIf { it.isNotEmpty() }
                    ?.let { it.sum().toDouble() / it.size },
                successRate = if (records.isEmpty()) 1.0 else records.count { it.success }.toDouble() / records.size,
            )
        }
    }

    fun byTaskType(): Map<String, UsageWindow> {
        val all = loadAll().filter { !it.taskType.isNullOrBlank() }
        return all.map { it.taskType!! }.distinct().associateWith { tt ->
            val filtered = all.filter { it.taskType == tt }
            val successful = filtered.filter { it.success }
            UsageWindow(
                records = filtered,
                totalInputTokens = successful.sumOf { it.inputTokens },
                totalOutputTokens = successful.sumOf { it.outputTokens },
                totalCostUsd = successful.sumOf { it.estimatedCostUsd },
                avgLatencyMs = filtered.mapNotNull { it.latencyMs }.takeIf { it.isNotEmpty() }
                    ?.let { it.sum().toDouble() / it.size },
                successRate = if (filtered.isEmpty()) 1.0 else filtered.count { it.success }.toDouble() / filtered.size,
            )
        }
    }

    /** Monthly budget check — caller decides whether to warn or block. */
    fun monthlyCostExceeds(limitUsd: Double): Boolean = month().totalCostUsd > limitUsd

    /** Estimated cost for a request against a curated capability entry. */
    fun estimateCost(curated: ModelCapability?, inputTokens: Long, outputTokens: Long): Double {
        if (curated == null) return 0.0
        val input = (curated.inputPricePerMTok ?: 0.0) * inputTokens / 1_000_000.0
        val output = (curated.outputPricePerMTok ?: 0.0) * outputTokens / 1_000_000.0
        return input + output
    }

    companion object {
        private const val KEY_USAGE = "usage_records_v1"
        private const val MAX_RECORDS = 5_000

        fun startOfDay(): Long {
            val now = System.currentTimeMillis()
            val msPerDay = 24 * 60 * 1000L
            return now - now % msPerDay
        }
    }
}
