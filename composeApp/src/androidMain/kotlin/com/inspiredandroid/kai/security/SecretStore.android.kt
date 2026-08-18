package com.inspiredandroid.kai.security

import android.content.Context
import com.russhwolf.settings.SharedPreferencesSettings
import dev.spght.encryptedprefs.EncryptedSharedPreferences
import dev.spght.encryptedprefs.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Android implementation of [SecretStore] backed by `dev.spght:encryptedprefs`
 * (a maintained fork of the deprecated androidx.security:security-crypto),
 * which stores the AES-256-GCM master key in the Android Keystore
 * (hardware-backed when available).
 *
 * This replaces the previous behaviour of storing API keys as plaintext in
 * plain SharedPreferences. Existing plaintext keys are transparently migrated
 * on first app launch via [SecretStoreMigrationRunner].
 */
class AndroidSecretStore(context: Context) : SecretStore {

    private val prefs: android.content.SharedPreferences =
        EncryptedSharedPreferences.create(
            context,
            SECRET_PREFS_NAME,
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

    private val settingsAdapter by lazy { SharedPreferencesSettings(prefs) }

    override suspend fun put(key: String, value: String): Unit = withContext(Dispatchers.IO) {
        settingsAdapter.putString(key, value)
    }

    override suspend fun get(key: String): String? = withContext(Dispatchers.IO) {
        settingsAdapter.getStringOrNull(key)
    }

    override suspend fun remove(key: String): Unit = withContext(Dispatchers.IO) {
        settingsAdapter.remove(key)
    }

    override suspend fun contains(key: String): Boolean = withContext(Dispatchers.IO) {
        settingsAdapter.getStringOrNull(key) != null
    }

    companion object {
        const val SECRET_PREFS_NAME = "moataz_ai_secrets"
    }
}

fun secretStoreModule(context: Context): Module = module {
    single<SecretStore> { AndroidSecretStore(context) }
}

/**
 * Persistent migration markers. Stored in plain SharedPreferences (booleans
 * only) so the migration from plaintext API keys to the encrypted store is
 * idempotent across restarts.
 */
actual class MigrationMarkers(context: Context) {
    private val prefs by lazy {
        context.getSharedPreferences(MARKERS_PREFS_NAME, Context.MODE_PRIVATE)
    }

    actual fun isMigrated(marker: String): Boolean = prefs.getBoolean(marker, false)

    actual fun markMigrated(marker: String) {
        prefs.edit().putBoolean(marker, true).apply()
    }

    companion object {
        const val MARKERS_PREFS_NAME = "moataz_ai_secret_migration"
    }
}
