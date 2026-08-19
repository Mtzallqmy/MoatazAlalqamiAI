package com.inspiredandroid.kai.hotupdate

import com.inspiredandroid.kai.skills.SkillManifest

/**
 * Turns remotely-delivered prompt/persona entries into in-memory skill
 * manifests so the chat prompt builder can inject them through the existing
 * skill pipeline — no sandbox write, no APK update.
 *
 * Remote skill manifests are always *additive* and *memory-only*: they never
 * persist to the sandbox filesystem and never replace built-in or
 * sandbox-installed skills sharing the same id.
 */
object RemoteSkillProvider {

    /** Prefix reserved for remote entries so ids can never collide with real skills. */
    private const val REMOTE_ID_PREFIX = "remote/"

    /**
     * Converts remote prompt/persona entries into skill manifests ready to be
     * merged into the skill list. `variant` tags the manifest so the prompt
     * builder can render persona vs prompt entries differently if needed.
     */
    fun toSkillManifests(
        remote: List<RemotePromptEntry>,
        variant: String = "prompt",
    ): List<SkillManifest> = remote.map { entry ->
        SkillManifest(
            id = "$REMOTE_ID_PREFIX${entry.id}",
            displayName = entry.title,
            description = "Remote ${variant}: delivered through the feature-update system.",
            body = entry.text,
            isBuiltIn = true,
        )
    }
}
