package com.inspiredandroid.kai.mcp

import com.inspiredandroid.kai.data.AppSettings
import com.inspiredandroid.kai.security.SecretKeys
import com.inspiredandroid.kai.security.SecretStore
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpServerSecurityTest {
    private class MemorySecretStore : SecretStore {
        private val secrets = mutableMapOf<String, String>()
        override suspend fun put(key: String, value: String) { secrets[key] = value }
        override suspend fun get(key: String): String? = secrets[key]
        override suspend fun remove(key: String) { secrets.remove(key) }
        override suspend fun contains(key: String): Boolean = key in secrets
    }

    @Test fun `sensitive MCP headers are encrypted and restored without plaintext settings`() {
        val settings = AppSettings(MapSettings())
        val vault = MemorySecretStore()
        val manager = McpServerManager(settings, vault)

        val server = manager.addServer(
            name = "Private server",
            url = "https://example.com/mcp",
            headers = mapOf("Authorization" to "Bearer secret-canary", "X-Region" to "aden"),
        )

        assertFalse(settings.getMcpServersJson().contains("secret-canary"))
        assertTrue(settings.getMcpServersJson().contains("X-Region"))
        assertEquals("Bearer secret-canary", manager.getServers().single().headers["Authorization"])
        assertTrue(runBlocking { vault.contains(SecretKeys.mcpHeader(server.id, "Authorization")) })
    }

    @Test fun `legacy plaintext MCP authorization migrates on manager initialization`() {
        val settings = AppSettings(MapSettings())
        settings.setMcpServersJson(
            """[{"id":"legacy","name":"Legacy","url":"https://example.com/mcp","headers":{"Authorization":"Bearer legacy-canary"}}]""",
        )
        val vault = MemorySecretStore()

        val manager = McpServerManager(settings, vault)

        assertFalse(settings.getMcpServersJson().contains("legacy-canary"))
        assertEquals("Bearer legacy-canary", manager.getServers().single().headers["Authorization"])
        assertTrue(runBlocking { vault.contains(SecretKeys.mcpHeader("legacy", "Authorization")) })
    }
}
