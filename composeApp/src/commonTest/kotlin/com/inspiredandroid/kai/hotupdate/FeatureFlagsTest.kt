package com.inspiredandroid.kai.hotupdate

import kotlin.test.Test
import kotlin.test.assertFalse

class FeatureFlagsTest {
    @Test
    fun `experimental and remotely controlled capabilities default off`() {
        val experimentalFlags = setOf(
            FeatureFlags.AGENT_CHAT_ATTACHMENTS,
            FeatureFlags.REMOTE_PROMPT_LIBRARY,
            FeatureFlags.DYNAMIC_TOOLS,
            FeatureFlags.HOT_UPDATE_ANNOUNCEMENTS,
            FeatureFlags.EXTENSION_PLATFORM,
            FeatureFlags.EXTENSION_REMOTE_CATALOG,
            FeatureFlags.RUNTIME_LITE_DOWNLOAD,
            FeatureFlags.RUNTIME_STAGED_UPDATES,
            FeatureFlags.REMOTE_RUNTIME,
            FeatureFlags.ENCRYPTED_SETTINGS_SYNC,
            FeatureFlags.ENCRYPTED_CONVERSATION_BACKUP,
            FeatureFlags.TEAM_WORKSPACES,
            FeatureFlags.CLOUD_GATEWAY,
            FeatureFlags.CLOUD_AUDIT_EXPORT,
            FeatureFlags.CRASH_REPORTING,
            FeatureFlags.USAGE_TELEMETRY,
        )
        experimentalFlags.forEach { flag ->
            assertFalse(FeatureFlags.DEFAULTS.getValue(flag), "$flag must remain opt-in")
        }
    }

    @Test
    fun `unknown feature defaults off`() {
        assertFalse(FeatureFlags.DEFAULTS["unknown-experimental-feature"] ?: false)
    }
}
