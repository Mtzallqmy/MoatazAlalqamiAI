package com.inspiredandroid.kai.sandbox.backend

import com.inspiredandroid.kai.linux.LinuxDistro
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.hours

/**
 * Sizing profile for a sandbox — advisory on local PRoot (the device's own
 * resources apply) but enforced on remote VMs via the provider.
 */
enum class ResourceProfile(
    val vCpu: Int,
    val ramGiB: Int,
    val diskGiB: Int,
) {
    LIGHT(vCpu = 1, ramGiB = 2, diskGiB = 10),
    STANDARD(vCpu = 2, ramGiB = 4, diskGiB = 25),
    BUILD(vCpu = 4, ramGiB = 8, diskGiB = 50),
    ;
}

/**
 * Outbound/inbound network policy. `Offline` blocks everything; `Restricted`
 * pins outbound to package sources (GitHub, npm, PyPI); `Developer` allows
 * general outbound but keeps inbound gated through the port proxy; `Custom`
 * carries user-defined allowlists.
 */
enum class NetworkPolicy {
    OFFLINE,
    RESTRICTED,
    DEVELOPER,
    CUSTOM,
}

/**
 * Full configuration handed to [SandboxBackend.create]. Reasonable defaults
 * make it one-line-friendly for the orchestrator.
 */
data class SandboxConfig(
    val distro: LinuxDistro = LinuxDistro.UBUNTU,
    val resourceProfile: ResourceProfile = ResourceProfile.STANDARD,
    val networkPolicy: NetworkPolicy = NetworkPolicy.DEVELOPER,
    val workspaceRoot: String = "/workspace",
    val maxLifetime: Duration? = 24.hours,
    val idleTimeout: Duration? = 30.minutes,
)
