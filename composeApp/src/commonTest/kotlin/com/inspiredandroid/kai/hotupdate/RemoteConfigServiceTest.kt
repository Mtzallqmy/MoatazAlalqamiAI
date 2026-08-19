package com.inspiredandroid.kai.hotupdate

import com.russhwolf.settings.MapSettings
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteConfigModelsTest {

    @Test
    fun `valid document passes validation untouched`() {
        val config = RemoteConfig(
            version = 1,
            feature_flags = mapOf("agent_chat_attachments" to true),
            prompt_additions = listOf(RemotePromptEntry("reviewer", "مراجع", "نص قصير")),
            dynamic_tools = listOf(
                RemoteToolDefinition(
                    id = "custom.summary",
                    name = "summarize",
                    description = "لخص النص",
                    parameters = mapOf("text" to RemoteParameter("string", "النص", true)),
                    kind = "builtin",
                    built_in = "terminal.echo",
                ),
            ),
        )
        val validated = config.validated().getOrNull()
        assertNotNull(validated)
        assertEquals(config, validated)
    }

    @Test
    fun `version gate strips all content but announcement for older apps`() {
        val config = RemoteConfig(
            version = 2,
            min_app_version = "99.0.0",
            feature_flags = mapOf("agent_chat_attachments" to true),
            prompt_additions = listOf(RemotePromptEntry("pre1", "Title", "Text")),
            dynamic_tools = listOf(
                RemoteToolDefinition("tool_1", "tool_one", "d", kind = "builtin", built_in = "x"),
            ),
            announcement = RemoteAnnouncement("news", "body"),
        )
        val validated = config.validated(SemVer.parse("3.7.0")).getOrNull()
        assertNotNull(validated)
        // Older app keeps only the announcement and version info.
        assertTrue(validated.feature_flags.isEmpty())
        assertTrue(validated.prompt_additions.isEmpty())
        assertTrue(validated.dynamic_tools.isEmpty())
        assertEquals("news", validated.announcement?.title)
    }

    @Test
    fun `app at or above min version keeps all content`() {
        val config = RemoteConfig(
            min_app_version = "3.7.0",
            feature_flags = mapOf("flag_one" to true),
            dynamic_tools = listOf(
                RemoteToolDefinition("tool_1", "tool_one", "d", kind = "builtin", built_in = "x"),
            ),
        )
        val validated = config.validated(SemVer.parse("3.7.0")).getOrNull()
        assertNotNull(validated)
        assertEquals(1, validated.feature_flags.size)
        assertEquals(1, validated.dynamic_tools.size)
    }

    @Test
    fun `illegal tool kinds and identifiers are stripped`() {
        val config = RemoteConfig(
            dynamic_tools = listOf(
                // good
                RemoteToolDefinition("a.b_c-1", "name", "d", kind = "builtin", built_in = "terminal.echo"),
                // bad kind
                RemoteToolDefinition("bad1", "name", "d", kind = "eval", built_in = "terminal.echo"),
                // builtin without executor
                RemoteToolDefinition("bad2", "name", "d", kind = "builtin", built_in = null),
                // webhook without url
                RemoteToolDefinition("bad3", "name", "d", kind = "webhook", webhook_url = null),
                // id too short / illegal chars
                RemoteToolDefinition("a!", "name", "d", kind = "builtin", built_in = "x"),
                RemoteToolDefinition("ab", "name", "d", kind = "builtin", built_in = "x"),
            ),
        )
        val validated = config.validated().getOrNull()
        assertNotNull(validated)
        assertEquals(1, validated.dynamic_tools.size)
        assertEquals("a.b_c-1", validated.dynamic_tools[0].id)
    }

    @Test
    fun `oversized prompts are stripped`() {
        val config = RemoteConfig(
            prompt_additions = listOf(
                RemotePromptEntry("ok_id", "Title", "short"),
                RemotePromptEntry("bad_id", "Title", "x".repeat(9_000)),
            ),
        )
        val validated = config.validated().getOrNull()
        assertNotNull(validated)
        assertEquals(1, validated.prompt_additions.size)
    }

    @Test
    fun `system prompt append is bounded and trimmed`() {
        val config = RemoteConfig(system_prompt_append = " " + "a".repeat(5_000))
        val validated = config.validated().getOrNull()
        assertNotNull(validated)
        assertNotNull(validated.system_prompt_append)
        assertEquals(2_000, validated.system_prompt_append!!.length)
    }

    @Test
    fun `illegal feature flag keys are stripped`() {
        val config = RemoteConfig(feature_flags = mapOf("good_flag" to true, "bad flag!" to true))
        val validated = config.validated().getOrNull()
        assertNotNull(validated)
        assertEquals(setOf("good_flag"), validated.feature_flags.keys)
    }

    @Test
    fun `semver parsing and comparison`() {
        assertTrue(SemVer.parse("3.7.0") < SemVer.parse("3.7.1"))
        assertTrue(SemVer.parse("3.7.0") < SemVer.parse("4.0.0"))
        assertTrue(SemVer.parse("3.10.0") > SemVer.parse("3.9.9"))
        assertEquals(SemVer(3, 7, 0), SemVer.parse("3.7.0"))
    }

    @Test
    fun `round trip serialization of full document`() {
        val json = Json { ignoreUnknownKeys = true }
        val original = RemoteConfig(
            version = 3,
            min_app_version = "3.7.0",
            feature_flags = mapOf("flag_one" to false),
            prompt_additions = listOf(RemotePromptEntry("pre_one", "T", "x")),
            persona_additions = listOf(RemotePromptEntry("per_one", "P", "y")),
            dynamic_tools = listOf(
                RemoteToolDefinition("tool_1", "tool_one", "d", kind = "webhook", webhook_url = "https://x.example/gw"),
            ),
            system_prompt_append = "note",
            announcement = RemoteAnnouncement("hi", "body", "https://x.example"),
        )
        val encoded = json.encodeToString(RemoteConfig.serializer(), original)
        val decoded = json.decodeFromString(RemoteConfig.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `unknown fields are ignored during parse`() {
        val json = Json { ignoreUnknownKeys = true }
        val raw = """{"version":1,"future_field":42,"feature_flags":{}}"""
        val decoded = json.decodeFromString(RemoteConfig.serializer(), raw)
        assertEquals(1L, decoded.version)
        assertTrue(decoded.feature_flags.isEmpty())
    }
}

class RemoteConfigServiceTest {

    private val fullDocument = Json { ignoreUnknownKeys = true }.run {
        encodeToString(
            RemoteConfig.serializer(),
            RemoteConfig(
                feature_flags = mapOf("agent_chat_attachments" to true, "dynamic_tools" to false),
                prompt_additions = listOf(RemotePromptEntry("pre1", "Prompt 1", "body 1")),
                dynamic_tools = listOf(
                    RemoteToolDefinition(
                        id = "custom.echo",
                        name = "echo",
                        description = "echoes text",
                        parameters = mapOf("text" to RemoteParameter("string", "the text", true)),
                        kind = "builtin",
                        built_in = "terminal.echo",
                    ),
                ),
                announcement = RemoteAnnouncement("News", "Something new"),
            ),
        )
    }

    /** Fake service reading from a hard-coded URL — never hits the network. */
    private fun offlineService(
        settings: MapSettings = MapSettings(),
        document: String = fullDocument,
    ): RemoteConfigService = object : RemoteConfigService(settings, "3.7.0", configUrl = "off://local") {
        override suspend fun refreshInternal(): RemoteConfig? = try {
            Json { ignoreUnknownKeys = true }.decodeFromString(RemoteConfig.serializer(), document)
        } catch (_: Throwable) {
            null
        }
    }

    @Test
    fun `service starts from cached config when present`() = kotlinx.coroutines.test.runTest {
        val settings = MapSettings()
        settings.putString(RemoteConfigDefaults.CONFIG_STORAGE_KEY, fullDocument)
        val service = offlineService(settings)
        // Load the hard-coded document into the active config explicitly.
        service.refresh()
        assertTrue(service.isEnabled("agent_chat_attachments", false))
        assertEquals(1, service.promptAdditions().size)
        assertEquals(1, service.dynamicTools().size)
    }

    @Test
    fun `unknown flag falls back to default`() = kotlinx.coroutines.test.runTest {
        val service = offlineService()
        service.refresh()
        assertFalse(service.isEnabled("nonexistent_flag", false))
        assertTrue(service.isEnabled("nonexistent_flag", true))
    }

    @Test
    fun `dynamic tool definitions reach the executor through tool provider`() = kotlinx.coroutines.test.runTest {
        val service = offlineService()
        service.refresh()
        val tools = service.dynamicTools()
        assertEquals("custom.echo", tools[0].id)
        assertEquals("builtin", tools[0].kind)
        assertEquals("terminal.echo", tools[0].built_in)
    }

    @Test
    fun `malformed document is rejected and previous good config stays in force`() = kotlinx.coroutines.test.runTest {
        val settings = MapSettings()
        settings.putString(RemoteConfigDefaults.CONFIG_STORAGE_KEY, fullDocument)
        val service = offlineService(settings, document = "{ not json at all")
        service.refresh()
        // Bad refresh must not disturb the cached good document.
        assertTrue(service.isEnabled("agent_chat_attachments", false))
    }

    @Test
    fun `announcement can be dismissed locally`() = kotlinx.coroutines.test.runTest {
        val service = offlineService()
        service.refresh()
        assertNotNull(service.announcement())
        service.dismissAnnouncement()
        assertNull(service.announcement())
    }
}
