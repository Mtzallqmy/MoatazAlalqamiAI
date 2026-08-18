package com.inspiredandroid.kai.security

import com.inspiredandroid.kai.data.AppSettings
import com.inspiredandroid.kai.data.Service
import com.inspiredandroid.kai.data.getConfiguredServiceInstances
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Persistent marker storage for the plaintext→encrypted secret migration.
 * Only booleans are stored (never secrets), so plain settings are acceptable.
 */
expect class MigrationMarkers {
    fun isMigrated(marker: String): Boolean
    fun markMigrated(marker: String)
}

/**
 * Moves legacy plaintext API keys out of `Settings` and into the encrypted
 * [SecretStore]. Designed to be invoked once at app startup.
 *
 * Migrated keys:
 * - Legacy single-instance service keys (`service_<prefix>_api_key`) for
 *   providers that still expose a top-level legacy slot.
 * - Legacy instance keys (`instance_<id>_api_key`) for every configured
 *   service instance.
 *
 * The runner never fails startup: exceptions are swallowed and logged to
 * stdout. Migration markers guarantee idempotency across relaunches.
 */
class SecretStoreMigrationRunner(
    private val secretStore: SecretStore,
    private val appSettings: AppSettings,
    private val markers: MigrationMarkers,
) {
    fun run() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                migrate()
            } catch (t: Throwable) {
                println("[SecretStoreMigration] failed: ${t.message}")
            }
        }
    }

    private suspend fun migrate() {
        // 1. Legacy instance keys (primary credential location today).
        val instanceIds: List<String> = appSettings.getConfiguredServiceInstances().map { it.instanceId }
        for (instanceId in instanceIds) {
            migrateKey("instance_${instanceId}_api_key")
        }

        // 2. Legacy single-instance service keys for providers that expose one.
        for (service in Service.all) {
            if (service.requiresApiKey || service.supportsOptionalApiKey) {
                migrateKey(service.apiKeyKey)
            }
        }
    }

    private suspend fun migrateKey(legacyKey: String) {
        if (markers.isMigrated(legacyKey)) return
        val plaintext: String = appSettings.getLegacyStringOrNull(legacyKey).orEmpty()
        if (plaintext.isBlank()) {
            markers.markMigrated(legacyKey)
            return
        }
        secretStore.put(legacyKey, plaintext)
        markers.markMigrated(legacyKey)
    }
}
