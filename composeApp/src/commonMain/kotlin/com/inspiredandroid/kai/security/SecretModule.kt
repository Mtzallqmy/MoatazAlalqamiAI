package com.inspiredandroid.kai.security

import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Common binding for [SecretStore]. The platform-specific `secretStoreModule`
 * (e.g. `AndroidSecretStore` on Android) is appended to Koin at startup, so
 * common code can inject [SecretStore] without platform imports.
 */
fun secretModule(platformModule: Module): Module = module {
    includes(platformModule)
    single { SecretStoreMigrator(get()) }
}
