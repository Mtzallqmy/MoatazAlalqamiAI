package com.inspiredandroid.kai.security

/**
 * Secure storage abstraction for credentials.
 *
 * API keys, GitHub tokens, Telegram bot tokens and any other secret must
 * *never* be stored as plaintext in `Settings`/SharedPreferences or SQLite.
 * All credential access flows through this interface.
 *
 * Platform implementations:
 * - Android: `EncryptedPrefs` (Android Keystore-backed AES-GCM) with a
 *   `SecretStore.kt` actual in `androidMain`.
 *
 * Secrets are referenced by a stable [key]; values are opaque to the caller
 * except when explicitly decrypted (e.g. before an outbound API call).
 */
interface SecretStore {
    /** Store a secret value (encrypted at rest on Android). */
    suspend fun put(key: String, value: String)

    /** Retrieve a secret, or `null` if none stored. */
    suspend fun get(key: String): String?

    /** Remove a stored secret. */
    suspend fun remove(key: String)

    /** True if a value is stored under [key]. */
    suspend fun contains(key: String): Boolean
}

/**
 * A lightweight facade that hides secrets behind a redacted mask for logging,
 * debug screens and crash reports. Callers rendering credential state should
 * always use [redacted] instead of the raw value.
 */
object SecretMasking {
    fun redacted(raw: String?): String {
        if (raw.isNullOrBlank()) return "(none)"
        if (raw.length <= 6) return "••••••"
        return raw.take(4) + "•".repeat(8) + raw.takeLast(3)
    }
}

/**
 * Canonical key namespaces so secret keys can be generated deterministically
 * and audited in one place.
 */
object SecretKeys {
    private const val PROVIDER_NS = "provider"
    private const val GITHUB_NS = "github"
    private const val TELEGRAM_NS = "telegram"
    private const val EMAIL_NS = "email"
    private const val MCP_NS = "mcp"

    fun providerApiKey(providerInstanceId: String): String = "$PROVIDER_NS.$providerInstanceId"

    /** Legacy service-instance API key namespace (`instance_<id>_api_key`). */
    fun instanceApiKey(instanceId: String): String = "instance_$instanceId.api_key"
    fun providerBaseUrlRef(providerInstanceId: String): String = "${PROVIDER_NS}BaseUrl.$providerInstanceId"

    fun githubToken(accountId: String): String = "$GITHUB_NS.token.$accountId"
    fun githubRefreshToken(accountId: String): String = "$GITHUB_NS.refresh.$accountId"

    fun telegramBotToken(): String = "$TELEGRAM_NS.bot_token"
    fun emailPassword(accountId: String): String = "$EMAIL_NS.password.$accountId"
    fun mcpHeader(serverId: String, headerName: String): String =
        "$MCP_NS.$serverId.header.${headerName.lowercase()}"
}
