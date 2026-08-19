package com.inspiredandroid.kai.hotupdate

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Remote configuration pushed to every device — enables shipping new features,
 * tool definitions, system-prompt tweaks and prompt-library entries without a
 * new APK release. Behaviour-changing values are always validated at parse
 * time and rejected when malformed (failing closed to the baked-in defaults),
 * so a bad remote config can never break the running app.
 *
 * The config document is intentionally provider-agnostic: it is fetched from a
 * URL the app already trusts (e.g. a GitHub raw file or a private endpoint)
 * and cached locally with a TTL.
 *
 * Example document:
 * ```json
 * {
 *   "version": 2,
 *   "min_app_version": "3.7.0",
 *   "feature_flags": { "agent_chat_attachments": true, "new_skill_mode": false },
 *   "prompt_additions": [
 *     { "id": "reviewer", "title": "مراجع الأكواد", "text": "راجع الكود بدقة..." }
 *   ],
 *   "persona_additions": [
 *     { "id": "dev_mentor", "title": "مرشد تطوير", "text": "أنت مرشد..." }
 *   ],
 *   "dynamic_tools": [
 *     {
 *       "id": "custom.echo",
 *       "name": "echo",
 *       "description": "Returns whatever text it is given.",
 *       "parameters": { "text": { "type": "string", "required": true } },
 *       "kind": "builtin",
 *       "built_in": "terminal.echo"
 *     }
 *   ],
 *   "system_prompt_append": "",
 *   "announcement": { "title": "إصدار ديناميكي", "body": "ميزات جديدة..." }
 * }
 * ```
 */

/** One parameter in a dynamic tool's JSON-Schema-style shape. */
@Serializable
data class RemoteParameter(
    val type: String, // string | integer | boolean | array
    val description: String,
    val required: Boolean = true,
)

/**
 * A tool definition delivered over the air. `kind` selects the executor:
 * - `builtin` → mapped onto an already compiled-in executor (safe; no code ships
 *   remotely — only routing + schema).
 * - `webhook` → POSTs to a user-configured gateway endpoint (user-controlled).
 * Anything else is rejected. Remote code execution is never possible.
 */
@Serializable
data class RemoteToolDefinition(
    val id: String,
    val name: String,
    val description: String,
    val parameters: Map<String, RemoteParameter> = emptyMap(),
    val kind: String, // builtin | webhook
    /** For kind=builtin: the compiled-in executor name (e.g. "terminal.echo"). */
    val built_in: String? = null,
    /** For kind=webhook: the user-approved gateway endpoint. */
    val webhook_url: String? = null,
    val timeout_seconds: Long? = null,
)

/** A remotely-deliverable prompt/preset (chat presets + personas share this shape). */
@Serializable
data class RemotePromptEntry(
    val id: String,
    val title: String,
    val text: String,
)

/** A banner announcement shown once in the chat/settings until dismissed. */
@Serializable
data class RemoteAnnouncement(
    val title: String = "",
    val body: String = "",
    val url: String? = null,
)

/** Full remote configuration document. */
@Serializable
data class RemoteConfig(
    val version: Long = 1,
    val min_app_version: String? = null,
    val feature_flags: Map<String, Boolean> = emptyMap(),
    val prompt_additions: List<RemotePromptEntry> = emptyList(),
    val persona_additions: List<RemotePromptEntry> = emptyList(),
    val dynamic_tools: List<RemoteToolDefinition> = emptyList(),
    val system_prompt_append: String? = null,
    val announcement: RemoteAnnouncement? = null,
) {
    /**
     * Parse-time validation: reject documents that would corrupt runtime state.
     * Returns a validated copy with illegal entries stripped, or an error reason.
     */
    fun validated(minSemver: SemVer? = null): Result<RemoteConfig> = runCatching {
        // 1. Version gating: if the app is older than the document requires,
        //    drop everything except the announcement (old clients still see news).
        val appOlderThanRequired = minSemver != null && min_app_version != null &&
            minSemver < SemVer.parse(min_app_version)
        if (appOlderThanRequired) {
            return Result.success(
                RemoteConfig(version = version, min_app_version = min_app_version, announcement = announcement),
            )
        }

        // 2. Tool gating: only whitelisted executor kinds; identifiers must be
        //    sane (letters/digits/dots/dashes/underscores, capped length).
        val identifier = Regex("^[A-Za-z0-9._-]{3,64}\$")
        val allowedKinds = setOf("builtin", "webhook")
        val legalTools = dynamic_tools.filter { tool ->
            tool.kind in allowedKinds &&
                identifier.matches(tool.id) && identifier.matches(tool.name) &&
                (tool.kind != "builtin" || !tool.built_in.isNullOrBlank()) &&
                (tool.kind != "webhook" || !tool.webhook_url.isNullOrBlank())
        }

        // 3. Prompt gating: sane ids and bounded sizes (prevent megabyte blobs).
        val textLimit = 8_000
        val legalPrompts = prompt_additions.filter {
            identifier.matches(it.id) && it.text.length <= textLimit && it.title.length <= 120
        }
        val legalPersonas = persona_additions.filter {
            identifier.matches(it.id) && it.text.length <= textLimit && it.title.length <= 120
        }

        // 4. System prompt append: bounded single string.
        val append = (system_prompt_append?.take(2_000))?.takeIf { it.isNotBlank() }

        // 5. Feature flags: only string→boolean maps with sane keys.
        val legalFlags = feature_flags.filter { (k, _) -> identifier.matches(k) }

        copy(
            feature_flags = legalFlags,
            prompt_additions = legalPrompts,
            persona_additions = legalPersonas,
            dynamic_tools = legalTools,
            system_prompt_append = append,
        )
    }
}

/** Trivial semver for the min_app_version gate. */
data class SemVer(val major: Int, val minor: Int, val patch: Int) : Comparable<SemVer> {
    override fun compareTo(other: SemVer): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val REGEX = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)")
        fun parse(raw: String): SemVer {
            val m = REGEX.find(raw) ?: error("invalid semver: $raw")
            return SemVer(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        }
    }
}
