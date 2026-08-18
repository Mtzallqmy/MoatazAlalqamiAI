package com.inspiredandroid.kai.security

import com.inspiredandroid.kai.data.AppSettings
import com.inspiredandroid.kai.data.getInstanceApiKey
import com.inspiredandroid.kai.data.removeInstanceApiKey
import com.inspiredandroid.kai.data.getInstanceBaseUrl
import com.inspiredandroid.kai.data.getInstanceEffectiveModelId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Resolves credentials for a service instance.
 *
 * Resolution order:
 * 1. Encrypted [SecretStore] (canonical location after the plaintext migration).
 * 2. Legacy plaintext `Settings` slot (compatibility until the migration runs
 *    for the first time, or for keys written before migration existed).
 *
 * Consumers (chat, model discovery, connection testing) always go through
 * this class — never through `Settings.getString` directly for secrets.
 */
class ProviderCredentialsResolver(
    val secretStore: SecretStore,
    private val appSettings: AppSettings,
) {
    /** Reads the API key for a configured service instance. */
    suspend fun resolveInstanceApiKey(instanceId: String): String {
        val secretKey = SecretKeys.instanceApiKey(instanceId)
        val secret = secretStore.get(secretKey)
        if (!secret.isNullOrBlank()) return secret
        // Lazy promotion: legacy plaintext key is copied into the vault, then
        // the plaintext copy is erased so a key lives in exactly one place.
        val legacy = appSettings.getInstanceApiKey(instanceId)
        if (legacy.isNotBlank()) {
            val moved = runCatching { secretStore.put(secretKey, legacy) }.isSuccess
            if (moved) runCatching { appSettings.removeInstanceApiKey(instanceId) }
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { secretStore.put(secretKey, legacy) }
                runCatching { appSettings.removeInstanceApiKey(instanceId) }
            }
            return legacy
        }
        return ""
    }

    suspend fun resolveInstanceBaseUrl(instanceId: String): String =
        appSettings.getInstanceBaseUrl(instanceId)

    suspend fun resolveInstanceModelId(instanceId: String): String =
        appSettings.getInstanceEffectiveModelId(instanceId)
}

