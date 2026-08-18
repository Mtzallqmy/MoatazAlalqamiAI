package com.inspiredandroid.kai.security

import java.util.concurrent.ConcurrentHashMap

/**
 * Fail-closed in-memory [SecretStore] used ONLY as a last-resort fallback when no
 * platform-encrypted store was injected (e.g. unit tests constructing the
 * repository directly). Never used in production builds: the Koin module injects
 * the platform `SecretStore` everywhere, so this class keeps secrets out of the
 * file system even in the fallback path rather than silently degrading to
 * plaintext SharedPreferences.
 *
 * Contents are intentionally lost on process death — a secret that cannot be
 * stored securely should not be stored at all.
 */
internal object FallbackSecretStore : SecretStore {
    private val store = ConcurrentHashMap<String, String>()

    override suspend fun put(key: String, value: String) {
        store[key] = value
    }

    override suspend fun get(key: String): String? = store[key]

    override suspend fun remove(key: String) {
        store.remove(key)
    }

    override suspend fun contains(key: String): Boolean = store.containsKey(key)
}
