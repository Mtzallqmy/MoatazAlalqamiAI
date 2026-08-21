package com.inspiredandroid.kai.extensions

import kotlinx.coroutines.CancellationException

data class ExtensionArtifact(
    val bytes: ByteArray,
    val mediaType: String,
)

fun interface ExtensionArtifactSource {
    suspend fun fetch(manifest: ExtensionManifest): ExtensionArtifact
}

fun interface ExtensionIntegrityVerifier {
    suspend fun verify(manifest: ExtensionManifest, artifact: ExtensionArtifact): Boolean
}

fun interface ExtensionCompatibilityChecker {
    suspend fun check(manifest: ExtensionManifest): Result<Unit>
}

fun interface ExtensionHealthProbe {
    suspend fun check(manifest: ExtensionManifest): Result<Unit>
}

/** Storage adapter must stage away from the active revision and activate atomically. */
interface ExtensionStore {
    suspend fun stage(installed: InstalledExtension, artifact: ExtensionArtifact)
    suspend fun activate(installed: InstalledExtension)
    suspend fun rollback(extensionId: String)
}

sealed interface ExtensionInstallResult {
    data class Ready(val installed: InstalledExtension) : ExtensionInstallResult
    data class Rejected(val stage: Stage, val reason: String) : ExtensionInstallResult

    enum class Stage { Manifest, Permissions, Download, Integrity, Compatibility, Staging, Health, Activation }
}

/**
 * Provider-independent extension transaction. Fetching never grants access:
 * every requested permission must be approved for the exact manifest digest,
 * integrity and compatibility must pass, and activation follows health checks.
 */
class ExtensionInstaller(
    private val source: ExtensionArtifactSource,
    private val integrityVerifier: ExtensionIntegrityVerifier,
    private val compatibilityChecker: ExtensionCompatibilityChecker,
    private val healthProbe: ExtensionHealthProbe,
    private val store: ExtensionStore,
) {
    suspend fun install(
        installed: InstalledExtension,
        grant: ExtensionGrant?,
    ): ExtensionInstallResult {
        installed.validate().exceptionOrNull()?.let {
            return rejected(ExtensionInstallResult.Stage.Manifest, it)
        }
        installed.manifest.requestedPermissions.forEach { permission ->
            ExtensionPermissionPolicy.authorize(installed, permission, grant).exceptionOrNull()?.let {
                return rejected(ExtensionInstallResult.Stage.Permissions, it)
            }
        }

        val artifact = try {
            source.fetch(installed.manifest)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return rejected(ExtensionInstallResult.Stage.Download, failure)
        }
        val integrityOk = try {
            integrityVerifier.verify(installed.manifest, artifact)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (!integrityOk) {
            return ExtensionInstallResult.Rejected(ExtensionInstallResult.Stage.Integrity, "extension integrity verification failed")
        }
        compatibilityChecker.check(installed.manifest).exceptionOrNull()?.let {
            return rejected(ExtensionInstallResult.Stage.Compatibility, it)
        }

        var staged = false
        var healthPassed = false
        try {
            store.stage(installed, artifact)
            staged = true
            healthProbe.check(installed.manifest).getOrThrow()
            healthPassed = true
            store.activate(installed)
            return ExtensionInstallResult.Ready(installed)
        } catch (cancelled: CancellationException) {
            if (staged) store.rollback(installed.manifest.id)
            throw cancelled
        } catch (failure: Exception) {
            if (staged) store.rollback(installed.manifest.id)
            val stage = when {
                !staged -> ExtensionInstallResult.Stage.Staging
                !healthPassed -> ExtensionInstallResult.Stage.Health
                else -> ExtensionInstallResult.Stage.Activation
            }
            return rejected(stage, failure)
        }
    }

    private fun rejected(stage: ExtensionInstallResult.Stage, failure: Throwable) =
        ExtensionInstallResult.Rejected(stage, failure.message ?: failure::class.simpleName ?: "extension operation failed")
}
