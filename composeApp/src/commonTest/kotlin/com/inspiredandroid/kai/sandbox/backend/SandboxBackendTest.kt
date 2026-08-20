package com.inspiredandroid.kai.sandbox.backend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

class SandboxConfigTest {

    @Test
    fun `standard config is well formed`() {
        val config = SandboxConfig(
            distro = com.inspiredandroid.kai.linux.LinuxDistro.UBUNTU,
            resourceProfile = ResourceProfile.STANDARD,
            networkPolicy = NetworkPolicy.DEVELOPER,
            workspaceRoot = "/workspace",
            maxLifetime = 1.hours,
        )
        assertEquals(com.inspiredandroid.kai.linux.LinuxDistro.UBUNTU, config.distro)
        assertEquals(2, config.resourceProfile.vCpu)
        assertEquals(4, config.resourceProfile.ramGiB)
        assertEquals(25, config.resourceProfile.diskGiB)
    }

    @Test
    fun `default config picks Debian and DEVELOPER policy`() {
        val config = SandboxConfig()
        assertEquals(com.inspiredandroid.kai.linux.LinuxDistro.DEBIAN, config.distro)
        assertEquals(NetworkPolicy.DEVELOPER, config.networkPolicy)
    }

    @Test
    fun `build profile is the strongest`() {
        assertTrue(ResourceProfile.BUILD.vCpu >= ResourceProfile.STANDARD.vCpu)
        assertTrue(ResourceProfile.BUILD.ramGiB >= ResourceProfile.STANDARD.ramGiB)
    }

    @Test
    fun `network policies are distinct`() {
        assertEquals(4, NetworkPolicy.entries.size)
        assertNotNull(NetworkPolicy.OFFLINE)
    }
}

class SandboxStateTest {

    @Test
    fun `exec request carries command and environment`() {
        val req = ExecRequest(
            command = "make",
            args = listOf("-j4"),
            workingDirectory = "/workspace/app",
            environment = mapOf("CI" to "true"),
        )
        assertEquals("make", req.command)
        assertEquals(listOf("-j4"), req.args)
        assertEquals("/workspace/app", req.workingDirectory)
        assertEquals(mapOf("CI" to "true"), req.environment)
    }
}

class SandboxErrorTest {

    @Test
    fun `auth error distinguishes from provider error`() {
        val auth: SandboxError = SandboxError.AuthError("denied")
        val provider: SandboxError = SandboxError.ProviderUnavailable("down")
        assertFalse(auth is SandboxError.ProviderUnavailable)
        assertFalse(provider is SandboxError.AuthError)
        assertTrue(auth is SandboxError.AuthError)
    }

    @Test
    fun `rate limit carries optional retry after`() {
        val err = SandboxError.RateLimitError(null)
        assertEquals("Rate limited", err.message)
    }
}
