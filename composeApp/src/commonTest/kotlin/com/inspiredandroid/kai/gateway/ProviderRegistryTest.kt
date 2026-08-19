package com.inspiredandroid.kai.gateway

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProviderRegistryTest {

    @AfterTest
    fun cleanup() {
        ProviderRegistry.applyRemoteCatalog(emptyList())
    }

    @Test
    fun `builtins include every core provider at least once`() {
        val ids = ProviderRegistry.builtins.map { it.id }.toSet()
        for (core in listOf("openai", "anthropic", "gemini", "openrouter", "groq", "deepseek", "openai-compatible", "litert")) {
            assertTrue(core in ids, "core provider missing: $core")
        }
    }

    @Test
    fun `each builtin resolves to a registered protocol`() {
        for (def in ProviderRegistry.builtins) {
            assertNotNull(Protocols.byId(def.protocolId), "unknown protocol for ${def.id}: ${def.protocolId}")
        }
    }

    @Test
    fun `constructor rejects invalid ids`() {
        assertFailsWith<IllegalArgumentException> { ProviderDefinition("", "Empty") }
        assertFailsWith<IllegalArgumentException> { ProviderDefinition("CamelCase", "Bad") }
        assertFailsWith<IllegalArgumentException> { ProviderDefinition("x".repeat(65), "Long") }
        assertFailsWith<IllegalArgumentException> { ProviderDefinition("ok", "", baseUrl = "https://x.com") }
        assertFailsWith<IllegalArgumentException> { ProviderDefinition("ok", "ok", baseUrl = "ftp://evil.example.com") }
        assertFailsWith<IllegalArgumentException> { ProviderDefinition("ok", "ok", baseUrl = "http://evil.example.com") }
    }

    @Test
    fun `localhost and file schemes are accepted as safe`() {
        assertNotNull(ProviderDefinition("local", "Local", baseUrl = "http://localhost:11434/v1"))
        assertNotNull(ProviderDefinition("localhost", "Localhost", baseUrl = "http://127.0.0.1:8000/v1"))
        assertFailsWith<IllegalArgumentException> { ProviderDefinition("fileprov", "File", baseUrl = "file://local") }
    }

    @Test
    fun `all merges builtins with remote additions and builtins win on collision`() {
        val remote = listOf(
            ProviderDefinition("my-provider", "Mine", baseUrl = "https://mine.example.com/v1"),
            ProviderDefinition("openai", "Shadow OpenAI", baseUrl = "https://evil.example.com/v1"),
        )
        ProviderRegistry.applyRemoteCatalog(remote)
        val catalog = ProviderRegistry.all
        assertTrue(catalog.any { it.id == "my-provider" })
        val openai = catalog.find { it.id == "openai" }
        assertNotNull(openai)
        assertEquals("https://api.openai.com/v1", openai.baseUrl)
        assertEquals(1, catalog.count { it.id == "openai" })
    }

    @Test
    fun `applyRemoteCatalogFromMaps drops malformed rows without throwing`() {
        val rows = listOf(
            mapOf("id" to "", "displayName" to "Empty id"),
            mapOf("id" to "Bad Id", "displayName" to "Spaces"),
            mapOf("id" to "ok-remote", "displayName" to "Good", "baseUrl" to "https://good.example.com/v1"),
            mapOf("id" to "ftp-evil", "displayName" to "Evil", "baseUrl" to "ftp://evil.example.com"),
        )
        ProviderRegistry.applyRemoteCatalogFromMaps(rows)
        assertEquals(1, ProviderRegistry.remote.size)
        assertEquals("ok-remote", ProviderRegistry.remote.single().id)
    }

    @Test
    fun `protocol adapters resolve endpoints without double slashes`() {
        val adapter = Protocols.OpenAIChatCompletions
        val chat = adapter.chatPath("https://host/v1/")
        assertEquals("https://host/v1/chat/completions", chat)
        val models = adapter.modelsPath("https://host/v1")
        assertEquals("https://host/v1/models", models)
        val noVersion = adapter.chatPath("https://host")
        assertEquals("https://host/chat/completions", noVersion)
    }

    @Test
    fun `anthropic protocol has no models endpoint`() {
        assertNull(Protocols.AnthropicMessages.modelsPath("https://api.anthropic.com/v1"))
    }

    @Test
    fun `remote definitions can extend registry capabilities`() {
        val ext = ProviderDefinition(
            "custom-llm", "Custom LLM", protocolId = "openai_responses",
            baseUrl = "https://custom.example.com/api/v1", capabilities = setOf("coding"),
        )
        ProviderRegistry.applyRemoteCatalog(listOf(ext))
        val found = ProviderRegistry.get("custom-llm")
        assertNotNull(found)
        assertTrue("coding" in found.capabilities)
        assertEquals("openai_responses", found.protocolId)
    }

    @Test
    fun `has returns false for unknown ids`() {
        assertFalse(ProviderRegistry.has("nonexistent-provider-xyz"))
        assertTrue(ProviderRegistry.has("openai"))
    }
}
