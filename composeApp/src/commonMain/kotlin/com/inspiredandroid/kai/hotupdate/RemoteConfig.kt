package com.inspiredandroid.kai.hotupdate

import com.russhwolf.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import io.ktor.client.statement.bodyAsText
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import com.inspiredandroid.kai.httpClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.hours

/**
 * Remote configuration delivery (hot update) — fetches the feature/config
 * document from a trusted URL and keeps a validated, cached copy that the
 * rest of the app reads from. Designed so new prompts, personas, feature
 * flags and dynamic tool *definitions* (built-in executors only) ship without
 * a new APK; only changes requiring new code or native binaries need a
 * release.
 *
 * Guarantees:
 * - Never blocks app startup: starts with defaults/last cached config.
 * - Fails closed: an unparsable or malicious document is ignored and the
 *   previous good config stays in force.
 * - Refreshes in the background on a TTL (default 6h) and on explicit trigger.
 * - Persists the last-good config locally (encrypted prefs store).
 */

object RemoteConfigDefaults {
    /** The trusted document URL — a GitHub raw file or private endpoint. */
    const val DEFAULT_CONFIG_URL: String =
        "https://raw.githubusercontent.com/Mtzallqmy/MoatazAlalqamiAI/main/remote-config/config.json"
    const val CONFIG_STORAGE_KEY: String = "remote_config_json"
    const val CONFIG_TIMESTAMP_KEY: String = "remote_config_ts"
    val REFRESH_INTERVAL: kotlin.time.Duration = 6.hours
}

/**
 * The delivery layer — keeps the active, validated [RemoteConfig] document in
 * memory and persists the last-good copy to the encrypted settings store.
 * (Named `RemoteConfigService` to avoid clashing with the data class
 * [RemoteConfig] from `RemoteConfigModels`.)
 */
open class RemoteConfigService(
    private val settings: Settings,
    private val currentAppVersion: String,
    private val configUrl: String = RemoteConfigDefaults.DEFAULT_CONFIG_URL,
    private val refreshInterval: kotlin.time.Duration = RemoteConfigDefaults.REFRESH_INTERVAL,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val _active = MutableStateFlow<RemoteConfig>(emptyConfig())
    val active: StateFlow<RemoteConfig> = _active.asStateFlow()

    private var lastRefreshEpochMs: Long = 0L
    private val nowMs: () -> Long = { System.currentTimeMillis() }

    init {
        val cached = loadCached()
        if (cached != null) _active.value = cached
        scope.launch { refresh() }
    }

    /** Manual trigger (e.g. Settings → Check for updates). */
    suspend fun refresh() {
        mutex.withLock {
            if (nowMs() - lastRefreshEpochMs < refreshInterval.inWholeMilliseconds &&
                lastRefreshEpochMs != 0L
            ) return
            runCatching {
                val parsed: RemoteConfig = refreshInternal() ?: return@runCatching
                val validated = parsed.validated(SemVer.parse(currentAppVersion)).getOrNull() ?: return@runCatching
                persist(validated)
                _active.value = validated
            }
            lastRefreshEpochMs = nowMs()
        }
    }

    /**
     * Fetches and parses the raw document. Override in tests to feed a
     * hard-coded document without touching the network.
     */
    protected open suspend fun refreshInternal(): RemoteConfig? = runCatching {
        val client = httpClient { }
        val response = client.get(configUrl)
        if (!response.status.isSuccess()) {
            return@runCatching null
        }
        json.decodeFromString(RemoteConfig.serializer(), response.bodyAsText())
    }.getOrNull()

    // ---------- Read surface ----------
    fun isEnabled(flag: String, default: Boolean = false): Boolean =
        _active.value.feature_flags[flag] ?: default

    fun promptAdditions(): List<RemotePromptEntry> = _active.value.prompt_additions
    fun personaAdditions(): List<RemotePromptEntry> = _active.value.persona_additions
    fun dynamicTools(): List<RemoteToolDefinition> = _active.value.dynamic_tools
    fun systemPromptAppend(): String? = _active.value.system_prompt_append
    fun announcement(): RemoteAnnouncement? = _active.value.announcement

    fun dismissAnnouncement() {
        val current = _active.value
        val dismissed = current.copy(announcement = null)
        _active.value = dismissed
        persist(dismissed)
    }

    // ---------- Persistence ----------
    private fun persist(config: RemoteConfig) {
        try {
            settings.putString(RemoteConfigDefaults.CONFIG_STORAGE_KEY, json.encodeToString(RemoteConfig.serializer(), config))
            settings.putLong(RemoteConfigDefaults.CONFIG_TIMESTAMP_KEY, nowMs())
        } catch (_: Throwable) {
            // Storage failure must never crash the app.
        }
    }

    private fun loadCached(): RemoteConfig? {
        val raw = runCatching { settings.getString(RemoteConfigDefaults.CONFIG_STORAGE_KEY, "") }.getOrNull()
        if (raw.isNullOrBlank()) return null
        return try {
            json.decodeFromString(RemoteConfig.serializer(), raw)
        } catch (_: Throwable) {
            null
        }
    }

    private fun emptyConfig(): RemoteConfig = RemoteConfig()

    fun shutdown() {
        scope.cancel()
    }
}
