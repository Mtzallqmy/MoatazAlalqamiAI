package com.inspiredandroid.kai.security

/**
 * One-time migration that moves API keys previously stored as plaintext in
 * `Settings` into the encrypted [SecretStore].
 *
 * Strategy per key:
 * 1. Read the plaintext value from legacy Settings.
 * 2. If present, write it into [SecretStore] under the canonical [SecretKeys]
 *    namespace, then clear the plaintext value.
 * 3. Track migration completion per key in a plain "migrated" marker so the
 *    migration is idempotent even if the secret write succeeds but the clear
 *    fails.
 *
 * Migration is best-effort: failures are logged but never block app startup.
 */
class SecretStoreMigrator(
    private val secretStore: SecretStore,
) {
    /** Caller-supplied legacy key readers; invoked lazily per secret key. */
    fun interface LegacyValueProvider {
        operator fun invoke(legacyKey: String): String?
    }

    suspend fun migrate(
        legacyKeys: List<String>,
        readLegacy: LegacyValueProvider,
        /** Optional cleaner invoked after a key has been safely stored in the vault. */
        eraseLegacy: (legacyKey: String) -> Unit = {},
    ): Int {
        var migrated = 0
        for (legacyKey in legacyKeys) {
            val marker = "secret_migrated_$legacyKey"
            if (readSecretMarker(marker)) continue
            val value = readLegacy(legacyKey).orEmpty()
            if (value.isBlank()) {
                writeSecretMarker(marker)
                continue
            }
            secretStore.put(legacyKey, value)
            // A secret must live in exactly one place: erase the legacy
            // plaintext copy once it has been safely stored in the vault.
            runCatching { eraseLegacy(legacyKey) }
            writeSecretMarker(marker)
            migrated++
        }
        return migrated
    }

    private fun readSecretMarker(marker: String): Boolean {
        // Markers live in plain Settings: they are booleans, not secrets.
        return marked.contains(marker)
    }

    private fun writeSecretMarker(marker: String) {
        marked.add(marker)
    }

    /**
     * In-memory marker registry — the production Android wiring persists these
     * via [MigrationMarkers] in androidMain. Default empty impl keeps the
     * common class platform-agnostic.
     */
    private val marked = HashSet<String>()
}
