package com.inspiredandroid.kai.runtime.distribution

import com.inspiredandroid.kai.runtime.MoatazRuntimeContract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RuntimeDistributionTest {
    private val appVersion = ReleaseVersion(4, 2, 0)

    private fun manifest(
        distro: String = "debian",
        architecture: String = "arm64",
        parts: List<RuntimeBundlePart> = listOf(
            RuntimeBundlePart("runtime.part-00", 0, 5, "b".repeat(64)),
            RuntimeBundlePart("runtime.part-01", 5, 5, "c".repeat(64)),
        ),
    ) = RuntimeReleaseManifest(
        schemaVersion = RuntimeReleaseManifest.CURRENT_SCHEMA_VERSION,
        releaseId = "runtime-4.1.0",
        versions = ProductVersions(
            app = appVersion,
            runtime = ReleaseVersion(4, 1, 0),
            rootfs = ReleaseVersion(13, 0, 2),
            cliBundle = ReleaseVersion(1, 18, 19),
        ),
        minimumAppVersion = ReleaseVersion(4, 0, 0),
        maximumAppVersionExclusive = ReleaseVersion(5, 0, 0),
        distro = distro,
        distroVersionMajor = 13,
        codename = "trixie",
        architecture = architecture,
        requiredCli = (MoatazRuntimeContract.requiredCli + MoatazRuntimeContract.requiredEmbeddedAgent)
            .associateWith { ReleaseVersion(1, 0, 0) },
        bundle = RuntimeBundleDescriptor(
            artifactName = "runtime.tar.xz",
            sizeBytes = 10,
            sha256 = "a".repeat(64),
            parts = parts,
        ),
        createdAtEpochSeconds = 1_787_200_000,
    )

    @Test
    fun `app runtime rootfs and cli versions are independent and strict`() {
        val versions = manifest().versions
        assertNotEquals(versions.app, versions.runtime)
        assertNotEquals(versions.runtime, versions.rootfs)
        assertEquals(ReleaseVersion(4, 2, 0), ReleaseVersion.parse("4.2.0"))
        assertFailsWith<IllegalStateException> { ReleaseVersion.parse("4.2") }
        assertFailsWith<IllegalStateException> { ReleaseVersion.parse("04.2.0") }
    }

    @Test
    fun `production manifest accepts only Debian 13 arm64 and compatible app`() {
        assertTrue(manifest().validateFor(appVersion).isSuccess)
        assertTrue(manifest(distro = "ubuntu").validateFor(appVersion).isFailure)
        assertTrue(manifest(architecture = "amd64").validateFor(appVersion).isFailure)
        assertTrue(manifest().validateFor(ReleaseVersion(5, 0, 0)).isFailure)
    }

    @Test
    fun `bundle parts must be ordered contiguous and cover the artifact`() {
        val gap = listOf(
            RuntimeBundlePart("runtime.part-00", 0, 5, "b".repeat(64)),
            RuntimeBundlePart("runtime.part-01", 6, 4, "c".repeat(64)),
        )
        assertTrue(manifest(parts = gap).validateFor(appVersion).isFailure)
    }

    @Test
    fun `runtime manifest envelope always requires a trusted valid signature`() {
        val unsigned = SignedRuntimeManifestEnvelope(
            format = RuntimeManifestEnvelopeGate.FORMAT,
            keyId = "release-2026",
            algorithm = RuntimeManifestEnvelopeGate.ALGORITHM,
            payloadBase64Url = "payload",
            signatureBase64Url = "",
        )
        assertTrue(
            RuntimeManifestEnvelopeGate.verify(unsigned, setOf("release-2026")) { _, _, _ -> true }.isFailure,
        )

        val signed = unsigned.copy(signatureBase64Url = "signature")
        assertTrue(RuntimeManifestEnvelopeGate.verify(signed, emptySet()) { _, _, _ -> true }.isFailure)
        assertTrue(
            RuntimeManifestEnvelopeGate.verify(signed, setOf("release-2026")) { _, _, _ -> false }.isFailure,
        )
        assertEquals(
            "payload",
            RuntimeManifestEnvelopeGate.verify(signed, setOf("release-2026")) { _, _, _ -> true }.getOrThrow(),
        )
    }

    @Test
    fun `full uses embedded source while lite requires credential-free HTTPS and resumable cursor`() {
        val descriptor = manifest().bundle
        val request = RuntimeBundleRequest(descriptor.artifactName, offsetBytes = 0, maxBytes = 5)
        val chunk = RuntimeBundleChunk(offsetBytes = 0, bytes = byteArrayOf(1, 2, 3, 4, 5), nextOffset = 5, complete = false)
        val embedded = fakeSource(RuntimeBundleLocation.Embedded(descriptor.artifactName))
        val remote = fakeSource(RuntimeBundleLocation.Remote("https://releases.moataz.example/runtime.tar.xz"))

        RuntimeBundleSourceGate.validate(AppDistribution.FullOffline, descriptor, embedded, request, chunk)
        RuntimeBundleSourceGate.validate(
            AppDistribution.Lite,
            descriptor,
            remote,
            request,
            chunk,
            trustedRemoteHosts = setOf("releases.moataz.example"),
        )
        assertFailsWith<IllegalArgumentException> {
            RuntimeBundleSourceGate.validate(AppDistribution.Lite, descriptor, embedded, request, chunk)
        }
        assertFailsWith<IllegalArgumentException> {
            RuntimeBundleSourceGate.validate(
                AppDistribution.Lite,
                descriptor,
                fakeSource(RuntimeBundleLocation.Remote("https://token@example.com/runtime.tar.xz")),
                request,
                chunk,
                trustedRemoteHosts = setOf("example.com"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RuntimeBundleSourceGate.validate(
                AppDistribution.Lite,
                descriptor,
                remote,
                request,
                chunk.copy(nextOffset = 4),
                trustedRemoteHosts = setOf("releases.moataz.example"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RuntimeBundleSourceGate.validate(AppDistribution.Lite, descriptor, remote, request, chunk)
        }
    }

    @Test
    fun `packaging contract separates full assets from lite without changing legacy CI default`() {
        val release = manifest()
        val runtimeParts = release.bundle.parts.map { it.name }.toSet()
        assertEquals(AppDistribution.FullOffline, RuntimePackagingContract.legacyCiDistribution)
        assertTrue(RuntimePackagingContract.validate(AppDistribution.FullOffline, release, runtimeParts).isSuccess)
        assertTrue(RuntimePackagingContract.validate(AppDistribution.FullOffline, release, runtimeParts.drop(1).toSet()).isFailure)
        assertTrue(RuntimePackagingContract.validate(AppDistribution.Lite, release, emptySet()).isSuccess)
        assertTrue(RuntimePackagingContract.validate(AppDistribution.Lite, release, runtimeParts).isFailure)
    }

    @Test
    fun `staged rollout allocation is deterministic and fail closed`() {
        val rollout = StagedRollout("runtime-4.1.0", basisPoints = 2_500)
        val first = DeterministicRolloutGate.isEligible("install-123", rollout)
        repeat(20) { assertEquals(first, DeterministicRolloutGate.isEligible("install-123", rollout)) }
        assertFalse(DeterministicRolloutGate.isEligible("install-123", rollout.copy(enabled = false)))
        assertFalse(DeterministicRolloutGate.isEligible("install-123", rollout.copy(basisPoints = 0)))
        assertTrue(DeterministicRolloutGate.isEligible("install-123", rollout.copy(basisPoints = 10_000)))
    }

    @Test
    fun `candidate cannot activate before verification and rollback restores prior healthy slot`() {
        val old = installed("old", RuntimeSlotHealth.Staged)
        var state = RuntimeSlotCoordinator.stage(RuntimeActivationState(), old)
        assertFailsWith<IllegalArgumentException> { RuntimeSlotCoordinator.activate(state, RuntimeSlot.A) }
        state = RuntimeSlotCoordinator.markVerified(state, RuntimeSlot.A)
        state = RuntimeSlotCoordinator.activate(state, RuntimeSlot.A)

        val candidate = installed("new", RuntimeSlotHealth.Staged)
        state = RuntimeSlotCoordinator.stage(state, candidate)
        state = RuntimeSlotCoordinator.markVerified(state, RuntimeSlot.B)
        state = RuntimeSlotCoordinator.activate(state, RuntimeSlot.B)
        assertEquals(RuntimeSlot.B, state.activeSlot)
        assertEquals(RuntimeSlot.A, state.rollbackSlot)

        assertFailsWith<IllegalArgumentException> {
            RuntimeSlotCoordinator.stage(state, installed("too-early", RuntimeSlotHealth.Staged))
        }

        state = RuntimeSlotCoordinator.rollback(state)
        assertEquals(RuntimeSlot.A, state.activeSlot)
        assertEquals("old", state.slots.getValue(RuntimeSlot.A).releaseId)
        assertEquals(RuntimeSlotHealth.Active, state.slots.getValue(RuntimeSlot.A).health)
    }

    @Test
    fun `failed candidate leaves active runtime and project storage untouched`() {
        var state = RuntimeSlotCoordinator.stage(RuntimeActivationState(), installed("old", RuntimeSlotHealth.Staged))
        state = RuntimeSlotCoordinator.activate(
            RuntimeSlotCoordinator.markVerified(state, RuntimeSlot.A),
            RuntimeSlot.A,
        )
        state = RuntimeSlotCoordinator.stage(state, installed("broken", RuntimeSlotHealth.Staged))
        state = RuntimeSlotCoordinator.markFailed(state, RuntimeSlot.B)

        assertEquals(RuntimeSlot.A, state.activeSlot)
        assertEquals(RuntimeSlotHealth.Active, state.slots.getValue(RuntimeSlot.A).health)
        assertNotEquals(RuntimeStorageLayout.projects, RuntimeStorageLayout.slotA)
        assertNotEquals(RuntimeStorageLayout.projects, RuntimeStorageLayout.slotB)
    }

    private fun installed(releaseId: String, health: RuntimeSlotHealth) = InstalledRuntimeSlot(
        releaseId = releaseId,
        runtimeVersion = ReleaseVersion(4, 1, 0),
        bundleSha256 = "d".repeat(64),
        health = health,
    )

    private fun fakeSource(location: RuntimeBundleLocation): RuntimeBundleSource = object : RuntimeBundleSource {
        override val location: RuntimeBundleLocation = location
        override suspend fun read(request: RuntimeBundleRequest): RuntimeBundleChunk = error("not used")
    }
}
