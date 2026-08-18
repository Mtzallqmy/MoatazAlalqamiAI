package com.inspiredandroid.kai.gateway

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for the protocol-aware URL normalization layer.
 *
 * These guards ensure the AI gateway never sends credentials in URLs and
 * never misroutes requests between HTTP, HTTPS, and protocol conventions.
 */
class UrlNormalizationTest {

    @Test
    fun `normalizeBaseUrl strips trailing slash`() {
        assertEquals("http://localhost:11434", UrlNormalization.normalizeBaseUrl("http://localhost:11434/"))
    }

    @Test
    fun `ensureVersionPath adds version exactly once`() {
        assertEquals("https://example.com/v1", UrlNormalization.ensureVersionPath("https://example.com"))
        assertEquals("https://example.com/v1", UrlNormalization.ensureVersionPath("https://example.com/v1"))
        assertEquals("https://example.com/v1", UrlNormalization.ensureVersionPath("https://example.com/v1/"))
    }

    @Test
    fun `ensureVersionPath preserves custom sub-paths`() {
        assertEquals("https://x.com/openai/v1", UrlNormalization.ensureVersionPath("https://x.com/openai/v1"))
    }

    @Test
    fun `joinPath handles empty base`() {
        assertEquals("chat/completions", UrlNormalization.joinPath("", "chat/completions"))
    }

    @Test
    fun `protocol adapters resolve chat endpoints per convention`() {
        val openai = Protocols.byId("openai_chat")
        assertEquals("https://api.openai.com/chat/completions", openai.chatPath("https://api.openai.com/"))
        val anthropic = Protocols.byId("anthropic_messages")
        assertEquals("https://api.anthropic.com/messages", anthropic.chatPath("https://api.anthropic.com"))
        val ollama = Protocols.byId("ollama")
        assertEquals("http://localhost:11434/api/chat", ollama.chatPath("http://localhost:11434/"))
        assertEquals("http://localhost:11434/api/tags", ollama.modelsPath("http://localhost:11434"))
    }

    @Test
    fun `unknown protocol id defaults to openai chat completions`() {
        val adapter = Protocols.byId("nonexistent")
        assertEquals(Protocols.OpenAIChatCompletions, adapter)
    }

    @Test
    fun `litert local adapter has no http endpoint`() {
        assertTrue(Protocols.LiteRt.chatPath("").isEmpty())
        assertEquals(null, Protocols.LiteRt.modelsPath(""))
    }

    @Test
    fun `all declared protocol ids resolve roundtrip`() {
        for (id in listOf("openai_chat", "openai_responses", "anthropic_messages", "gemini_native", "ollama", "litert_local")) {
            assertEquals(id, Protocols.byId(id).protocolId)
        }
    }
}
