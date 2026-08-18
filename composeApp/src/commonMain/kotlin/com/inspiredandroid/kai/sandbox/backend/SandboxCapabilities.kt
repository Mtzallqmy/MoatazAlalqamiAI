package com.inspiredandroid.kai.sandbox.backend

/**
 * Declarative capability flags a backend publishes so the agent router and the
 * UI can decide what to offer without probing the environment.
 */
data class SandboxCapabilities(
    /** Exec commands (one-shot). */
    val exec: Boolean = true,
    /** Long-lived streaming sessions with stdin. */
    val streamingExec: Boolean = true,
    /** Filesystem read/write/delete/rename. */
    val filesystem: Boolean = true,
    /** Recursive directory search. */
    val fileSearch: Boolean = true,
    /** Process listing + signalling. */
    val processControl: Boolean = true,
    /** Port exposure + in-app preview. */
    val portExposure: Boolean = true,
    /** Snapshot creation + restore. */
    val snapshots: Boolean = false,
    /** Idle timeout / auto-stop support. */
    val idleTimeout: Boolean = false,
    /** Max-lifetime enforcement. */
    val maxLifetime: Boolean = false,
    /** Network policy enforcement (offline / restricted / developer). */
    val networkPolicy: Boolean = false,
) {
    companion object {
        /** Local PRoot Ubuntu — everything except snapshots/limits (advisory on-device). */
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

        /** Minimal backend used by no-op/fallback paths. */
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
