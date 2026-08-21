package com.inspiredandroid.kai.extensions

import kotlin.test.Test
import kotlin.test.assertTrue

class ExtensionPlatformTest {
    private fun manifest(
        version: String = "1.0.0",
        permissions: Set<ExtensionPermission> = setOf(ExtensionPermission.WORKSPACE_READ),
    ) = ExtensionManifest(
        id = "moataz.example-cli",
        kind = ExtensionKind.CLI,
        version = version,
        displayName = "Example CLI",
        source = ExtensionSource(
            kind = ExtensionSourceKind.GIT,
            uri = "https://github.com/example/cli",
            immutableRef = "a".repeat(40),
        ),
        integrity = ExtensionIntegrity(sha256 = "b".repeat(64)),
        requestedPermissions = permissions,
        healthCheck = ExtensionHealthCheck(ExtensionHealthKind.COMMAND, "example --version"),
    )

    @Test
    fun `valid immutable manifest is accepted`() {
        assertTrue(manifest().validate().isSuccess)
    }

    @Test
    fun `mutable Git reference is rejected`() {
        val mutable = manifest().copy(source = manifest().source.copy(immutableRef = "main"))
        assertTrue(mutable.validate().isFailure)
    }

    @Test
    fun `remote artifact without integrity is rejected`() {
        val unsigned = manifest().copy(integrity = ExtensionIntegrity())
        assertTrue(unsigned.validate().isFailure)
    }

    @Test
    fun `permission is denied when no grant exists`() {
        val installed = InstalledExtension(manifest(), "c".repeat(64))
        assertTrue(
            ExtensionPermissionPolicy.authorize(
                installed,
                ExtensionPermission.WORKSPACE_READ,
                grant = null,
            ).isFailure,
        )
    }

    @Test
    fun `manifest tampering invalidates prior grant`() {
        val installed = InstalledExtension(manifest(), "d".repeat(64))
        val priorGrant = ExtensionGrant(
            extensionId = installed.manifest.id,
            extensionVersion = installed.manifest.version,
            manifestDigest = "c".repeat(64),
            permissions = setOf(ExtensionPermission.WORKSPACE_READ),
        )
        assertTrue(
            ExtensionPermissionPolicy.authorize(
                installed,
                ExtensionPermission.WORKSPACE_READ,
                priorGrant,
            ).isFailure,
        )
    }

    @Test
    fun `permission escalation requires a new exact grant`() {
        val upgraded = InstalledExtension(
            manifest(
                version = "2.0.0",
                permissions = setOf(ExtensionPermission.WORKSPACE_READ, ExtensionPermission.NETWORK),
            ),
            "e".repeat(64),
        )
        val oldGrant = ExtensionGrant(
            extensionId = upgraded.manifest.id,
            extensionVersion = "1.0.0",
            manifestDigest = "c".repeat(64),
            permissions = setOf(ExtensionPermission.WORKSPACE_READ),
        )
        assertTrue(ExtensionPermissionPolicy.authorize(upgraded, ExtensionPermission.NETWORK, oldGrant).isFailure)
    }

    @Test
    fun `exact grant authorizes only declared permission`() {
        val installed = InstalledExtension(manifest(), "f".repeat(64))
        val grant = ExtensionGrant(
            extensionId = installed.manifest.id,
            extensionVersion = installed.manifest.version,
            manifestDigest = installed.manifestDigest,
            permissions = setOf(ExtensionPermission.WORKSPACE_READ),
        )
        assertTrue(ExtensionPermissionPolicy.authorize(installed, ExtensionPermission.WORKSPACE_READ, grant).isSuccess)
        assertTrue(ExtensionPermissionPolicy.authorize(installed, ExtensionPermission.NETWORK, grant).isFailure)
    }
}
