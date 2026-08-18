package com.inspiredandroid.kai.sandbox.remote

import com.inspiredandroid.kai.sandbox.backend.ResourceProfile
import kotlin.time.Duration

/**
 * VM provider abstraction — the layer between the `RemoteSandboxBackend`
 * (Ktor client + gateway protocol) and whatever actually creates Ubuntu VMs
 * (Incus today; the interface deliberately holds no Incus types so a Libvirt,
 * cloud or bare-metal provider can be swapped in later).
 *
 * Enforced security invariant (per TARGET_ARCHITECTURE §4): the phone NEVER
 * talks to the provider's admin API directly and NEVER holds admin
 * credentials. All management flows through the authenticated Sandbox
 * Gateway, which owns the admin plane.
 */
interface SandboxProvider {

    /** Stable provider id (e.g. "incus"). */
    val providerId: String

    /** Whether this provider is configured and reachable. */
    suspend fun isAvailable(): Boolean

    /** Resource quotas the provider enforces per VM. */
    suspend fun quotas(): ProviderQuotas

    suspend fun createVm(template: VmTemplate, profile: ResourceProfile): VmHandle

    suspend fun startVm(id: String)

    suspend fun stopVm(id: String)

    suspend fun destroyVm(id: String)

    /** List VMs the current user may see — never other users'. */
    suspend fun listVms(): List<VmHandle>
}

/** Provider-level quotas (what RemoteSandboxBackend can request). */
data class ProviderQuotas(
    val maxConcurrentVms: Int = 4,
    val maxDiskGiB: Int = 50,
    val maxRamGiB: Int = 16,
    val maxLifetime: Duration? = null,
    val networkPolicies: Set<String> = setOf("offline", "restricted", "developer"),
)

/** VM creation request. */
data class VmTemplate(
    val distro: String = "ubuntu",
    val distroRelease: String = "26.04",
    val arch: String = "arm64",
    val imageAlias: String = "ubuntu-26.04",
    val tags: Map<String, String> = emptyMap(),
)

/** An existing or newly created VM handle — scoped credentials only. */
data class VmHandle(
    val id: String,
    val name: String,
    val status: VmStatus = VmStatus.CREATED,
    val ip: String? = null,
    val profile: ResourceProfile? = null,
    val expiresEpochMs: Long? = null,
)

enum class VmStatus {
    CREATED,
    RUNNING,
    STOPPED,
    ERROR,
    DESTROYED,
}
