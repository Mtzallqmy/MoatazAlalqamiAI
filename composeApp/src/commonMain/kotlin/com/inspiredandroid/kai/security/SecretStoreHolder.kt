package com.inspiredandroid.kai.security

/**
 * Single-writer reference to the platform [SecretStore].
 *
 * Set once at app startup (the Koin module writes it right after the store is
 * created) so that code paths that cannot receive the store through the
 * constructor — import/export flows, migration helpers — still write secrets
 * to the encrypted vault instead of plaintext settings.
 *
 * This is deliberately NOT a service locator: it holds exactly one dependency
 * that most code should receive through injection, and it is never queried
 * from inside hot request paths.
 */
object SecretStoreHolder {
    @Volatile
    var store: SecretStore? = null
        private set

    fun install(secretStore: SecretStore) {
        store = secretStore
    }

    /** Test-only helper: replaces the installed store (e.g. an in-memory vault). */
    fun installForTesting(secretStore: SecretStore) {
        store = secretStore
    }
}
