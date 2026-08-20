package com.inspiredandroid.kai.runtime

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class RuntimeContractTest {
    @Test fun `debian 13 arm64 is accepted`() {
        val issues = validateRuntimeIdentity(OsRelease.parse("ID=debian\nVERSION_ID=13\nVERSION_CODENAME=trixie\n"), "arm64\n")
        assertTrue(issues.isEmpty())
    }

    @Test fun `wrong distro is rejected`() {
        val issues = validateRuntimeIdentity(OsRelease.parse("ID=ubuntu\nVERSION_ID=26.04\n"), "arm64")
        assertTrue(issues.any { it is EnvironmentIssue.WrongDistro })
    }

    @Test fun `wrong architecture is rejected`() {
        assertIs<EnvironmentIssue.WrongArchitecture>(
            validateRuntimeIdentity(OsRelease.parse("ID=debian\nVERSION_ID=13\n"), "amd64").first(),
        )
    }

    @Test fun `bookworm manifest cannot masquerade as production`() {
        val manifest = RootfsManifest(
            1, "debian", "12", "bookworm", "arm64", "legacy", "a".repeat(64),
            MoatazRuntimeContract.requiredCli, "2026-08-20T00:00:00Z",
        )
        assertFalse(manifest.isProductionRuntime())
    }

    @Test fun `missing cli gets targeted apt repair`() {
        val health = EnvironmentHealth(listOf(EnvironmentIssue.MissingCli("rg", "missing")))
        val plan = EnvironmentRepairPlanner.plan(health)
        val install = assertIs<EnvironmentRepairAction.InstallPackages>(plan.actions.single())
        assertTrue("ripgrep" in install.packages)
        assertFalse(plan.requiresReinstall)
    }

    @Test fun `marker is not written before health checks pass`() {
        var written = false
        val health = EnvironmentHealth(listOf(EnvironmentIssue.BrokenShell("missing sh")))
        assertFailsWith<IllegalStateException> {
            RuntimeReadinessGate.commit(health, "marker") { written = true }
        }
        assertFalse(written)
    }
}
