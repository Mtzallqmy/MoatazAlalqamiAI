package com.inspiredandroid.kai.hotupdate

/**
 * The app's feature-flag dictionary. Flags shipped remotely can only *enable*
 * capabilities that already exist in the compiled code — a remote config can
 * never switch on something the APK does not contain, which is what keeps
 * hot updates safe and deterministic.
 *
 * When adding a new feature behind a flag:
 * 1. Ship the feature code (behind the flag) in the APK.
 * 2. Read the flag via [isOn] at the feature's decision point.
 * 3. Flip the flag remotely whenever you want to roll it out — no release.
 *
 * Rolling out without a remote config works too: baked-in `DEFAULTS` win when
 * the remote document has no entry for the flag, so development builds can
 * enable flags locally.
 */
object FeatureFlags {
    // ---------- Flag registry ----------
    const val AGENT_CHAT_ATTACHMENTS = "agent_chat_attachments"
    const val REMOTE_PROMPT_LIBRARY = "remote_prompt_library"
    const val DYNAMIC_TOOLS = "dynamic_tools"
    const val HOT_UPDATE_ANNOUNCEMENTS = "hot_update_announcements"
    const val EXTENSION_PLATFORM = "extension_platform"
    const val EXTENSION_REMOTE_CATALOG = "extension_remote_catalog"
    const val RUNTIME_LITE_DOWNLOAD = "runtime_lite_download"
    const val RUNTIME_STAGED_UPDATES = "runtime_staged_updates"
    const val REMOTE_RUNTIME = "remote_runtime"
    const val ENCRYPTED_SETTINGS_SYNC = "encrypted_settings_sync"
    const val ENCRYPTED_CONVERSATION_BACKUP = "encrypted_conversation_backup"
    const val TEAM_WORKSPACES = "team_workspaces"
    const val CLOUD_GATEWAY = "cloud_gateway"
    const val CLOUD_AUDIT_EXPORT = "cloud_audit_export"
    const val CRASH_REPORTING = "crash_reporting"
    const val USAGE_TELEMETRY = "usage_telemetry"

    /** Default values when the remote document has no entry. */
    val DEFAULTS: Map<String, Boolean> = mapOf(
        AGENT_CHAT_ATTACHMENTS to false,
        REMOTE_PROMPT_LIBRARY to false,
        DYNAMIC_TOOLS to false,
        HOT_UPDATE_ANNOUNCEMENTS to false,
        EXTENSION_PLATFORM to false,
        EXTENSION_REMOTE_CATALOG to false,
        RUNTIME_LITE_DOWNLOAD to false,
        RUNTIME_STAGED_UPDATES to false,
        REMOTE_RUNTIME to false,
        ENCRYPTED_SETTINGS_SYNC to false,
        ENCRYPTED_CONVERSATION_BACKUP to false,
        TEAM_WORKSPACES to false,
        CLOUD_GATEWAY to false,
        CLOUD_AUDIT_EXPORT to false,
        CRASH_REPORTING to false,
        USAGE_TELEMETRY to false,
    )

    /** Human-readable names for the Settings page. */
    val DISPLAY_NAMES: Map<String, String> = mapOf(
        AGENT_CHAT_ATTACHMENTS to "Chat file attachments",
        REMOTE_PROMPT_LIBRARY to "Remote prompt library",
        DYNAMIC_TOOLS to "Dynamic tools",
        HOT_UPDATE_ANNOUNCEMENTS to "Update announcements",
        EXTENSION_PLATFORM to "Extension platform",
        EXTENSION_REMOTE_CATALOG to "Remote extension catalog",
        RUNTIME_LITE_DOWNLOAD to "Moataz Lite runtime downloads",
        RUNTIME_STAGED_UPDATES to "Staged runtime updates",
        REMOTE_RUNTIME to "Remote runtime (experimental)",
        ENCRYPTED_SETTINGS_SYNC to "Encrypted settings sync",
        ENCRYPTED_CONVERSATION_BACKUP to "Encrypted conversation backup",
        TEAM_WORKSPACES to "Team workspaces",
        CLOUD_GATEWAY to "Moataz cloud gateway",
        CLOUD_AUDIT_EXPORT to "Cloud audit export",
        CRASH_REPORTING to "Optional crash reporting",
        USAGE_TELEMETRY to "Optional usage telemetry",
    )

    /** Reads a flag through the active remote config with a baked-in default. */
    fun isOn(config: RemoteConfigService, flag: String): Boolean =
        config.isEnabled(flag, DEFAULTS[flag] ?: false)
}
