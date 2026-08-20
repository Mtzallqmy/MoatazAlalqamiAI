package com.inspiredandroid.kai.sandbox.backend

/**
 * Declarative capability flags a backend publishes so the agent router and the
 * UI can decide what to offer without probing the environment.
 */
data class SandboxCapabilities(
    val exec: Boolean = true,
    val streamingExec: Boolean = true,
    val filesystem: Boolean = true,
    val fileSearch: Boolean = true,
    val processControl: Boolean = true,
    val portExposure: Boolean = true,
    val snapshots: Boolean = false,
    val idleTimeout: Boolean = false,
    val maxLifetime: Boolean = false,
    val networkPolicy: Boolean = false,
) {
    companion object {
        /** Local PRoot Debian 13 — on-device limits are advisory. */
        val LOCAL_PROOT = SandboxCapabilities(
            idleTimeout = true,
        )

        /** Remote Incus VM via gateway — full set including snapshots and policy. */
        val REMOTE_VM = SandboxCapabilities(
            snapshots = true,
            idleTimeout = true,
            maxLifetime = true,
            networkPolicy = true,
        )

        val NONE = SandboxCapabilities(
            exec = false,
            streamingExec = false,
            filesystem = false,
            fileSearch = false,
            processControl = false,
            portExposure = false,
            snapshots = false,
        )
    }
}
