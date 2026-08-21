package com.inspiredandroid.kai.extensions

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ExtensionInstallerTest {
    @Test
    fun `healthy verified extension activates atomically`() = runTest {
        val store = RecordingStore()
        val installed = installed()
        val result = installer(store = store).install(installed, grant(installed))

        assertIs<ExtensionInstallResult.Ready>(result)
        assertEquals(listOf("stage", "activate"), store.events)
    }

    @Test
    fun `integrity failure never stages extension`() = runTest {
        val store = RecordingStore()
        val result = installer(store = store, integrity = false).install(installed(), grant(installed()))

        assertEquals(ExtensionInstallResult.Stage.Integrity, assertIs<ExtensionInstallResult.Rejected>(result).stage)
        assertEquals(emptyList(), store.events)
    }

    @Test
    fun `health failure rolls staged revision back`() = runTest {
        val store = RecordingStore()
        val installed = installed()
        val result = installer(store = store, healthy = false).install(installed, grant(installed))

        assertEquals(ExtensionInstallResult.Stage.Health, assertIs<ExtensionInstallResult.Rejected>(result).stage)
        assertEquals(listOf("stage", "rollback"), store.events)
    }

    private fun installer(
        store: RecordingStore,
        integrity: Boolean = true,
        healthy: Boolean = true,
    ) = ExtensionInstaller(
        source = ExtensionArtifactSource { ExtensionArtifact(byteArrayOf(1, 2, 3), "application/octet-stream") },
        integrityVerifier = ExtensionIntegrityVerifier { _, _ -> integrity },
        compatibilityChecker = ExtensionCompatibilityChecker { Result.success(Unit) },
        healthProbe = ExtensionHealthProbe {
            if (healthy) Result.success(Unit) else Result.failure(IllegalStateException("probe failed"))
        },
        store = store,
    )

    private fun installed() = InstalledExtension(
        manifest = ExtensionManifest(
            id = "moataz.test-cli",
            kind = ExtensionKind.CLI,
            version = "1.0.0",
            displayName = "Test CLI",
            source = ExtensionSource(
                kind = ExtensionSourceKind.GIT,
                uri = "https://github.com/example/test-cli",
                immutableRef = "a".repeat(40),
            ),
            integrity = ExtensionIntegrity(sha256 = "b".repeat(64)),
            requestedPermissions = setOf(ExtensionPermission.WORKSPACE_READ),
            healthCheck = ExtensionHealthCheck(ExtensionHealthKind.COMMAND, "test-cli --version"),
        ),
        manifestDigest = "c".repeat(64),
    )

    private fun grant(installed: InstalledExtension) = ExtensionGrant(
        extensionId = installed.manifest.id,
        extensionVersion = installed.manifest.version,
        manifestDigest = installed.manifestDigest,
        permissions = installed.manifest.requestedPermissions,
    )

    private class RecordingStore : ExtensionStore {
        val events = mutableListOf<String>()

        override suspend fun stage(installed: InstalledExtension, artifact: ExtensionArtifact) {
            events += "stage"
        }

        override suspend fun activate(installed: InstalledExtension) {
            events += "activate"
        }

        override suspend fun rollback(extensionId: String) {
            events += "rollback"
        }
    }
}
