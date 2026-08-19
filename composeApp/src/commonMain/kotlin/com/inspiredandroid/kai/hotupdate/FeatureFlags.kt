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

    /** Default values when the remote document has no entry. */
    val DEFAULTS: Map<String, Boolean> = mapOf(
        AGENT_CHAT_ATTACHMENTS to true,
        REMOTE_PROMPT_LIBRARY to true,
        DYNAMIC_TOOLS to true,
        HOT_UPDATE_ANNOUNCEMENTS to true,
    )

    /** Human-readable names for the Settings page. */
    val DISPLAY_NAMES: Map<String, String> = mapOf(
        AGENT_CHAT_ATTACHMENTS to "Chat file attachments",
        REMOTE_PROMPT_LIBRARY to "Remote prompt library",
        DYNAMIC_TOOLS to "Dynamic tools",
        HOT_UPDATE_ANNOUNCEMENTS to "Update announcements",
    )

    /** Reads a flag through the active remote config with a baked-in default. */
    fun isOn(config: RemoteConfigService, flag: String): Boolean =
        config.isEnabled(flag, DEFAULTS[flag] ?: false)
}
