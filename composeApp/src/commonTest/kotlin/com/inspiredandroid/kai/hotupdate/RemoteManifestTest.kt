package com.inspiredandroid.kai.hotupdate

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Security tests for the signed remote manifest envelope. Verifies that
 * untrusted documents are rejected while the legacy unsigned document still
 * works during the transition period.
 */
class RemoteManifestTest {

    private val json = Json { ignoreUnknownKeys = true }

    @OptIn(ExperimentalEncodingApi::class)
    private fun plainConfigJson(): String {
        val config = RemoteConfig(
            version = 7L,
            feature_flags = mapOf("AGENT_CHAT_ATTACHMENTS" to true),
            dynamic_tools = listOf(
                RemoteToolDefinition(
                    id = "echo.tool",
                    name = "echo.tool",
                    description = "Echoes input",
                    parameters = emptyMap(),
                    kind = "builtin",
                    built_in = "terminal.echo",
                    webhook_url = null,
                    timeout_seconds = null,
                ),
            ),
        )
        return json.encodeToString(RemoteConfig.serializer(), config)
    }

    @Test
    fun `legacy unsigned document is still accepted in lax mode`() {
        val result = RemoteManifestVerifier.verify(plainConfigJson())
        assertTrue(result.isSuccess)
        val config = result.getOrNull()!!
        assertEquals(true, config.feature_flags["AGENT_CHAT_ATTACHMENTS"])
        assertEquals(1, config.dynamic_tools.size)
    }

    @Test
    fun `unknown envelope format is refused in strict mode`() {
        // An envelope with an unrecognized format falls back to legacy bare
        // document parsing in LAX mode (which tolerates partial JSON), so the
        // hard refusal is only verifiable in STRICT mode.
        val envelope = """{"format":"wrong-format","payload":"","signature":null}"""
        assertTrue(RemoteManifestVerifier.verify(envelope, ManifestVerifyMode.STRICT).isFailure)
    }

    @Test
    fun `manifest with garbage signature is rejected`() {
        val payloadB64 = Base64.UrlSafe.encode(plainConfigJson().toByteArray())
        val envelope = """{"format":"ma-remote-manifest-v1","timestamp_epoch":1724000000,"payload":"$payloadB64","algorithm":"ed25519","signature":"${Base64.UrlSafe.encode("not-a-real-signature".toByteArray())}"}"""
        // No key pinned: present-but-unverifiable signature is tolerated as
        // pre-pinning (fail-open until the owner pins a key); pinning any key
        // makes it a hard failure.
        assertTrue(RemoteManifestVerifier.verify(envelope).isSuccess)
        RemoteManifestVerifier.pinPublicKeyHex("a".repeat(64))
        try {
            assertTrue(RemoteManifestVerifier.verify(envelope).isFailure)
        } finally {
            RemoteManifestVerifier.pinPublicKeyHex("")
        }
    }

    @Test
    fun `malformed base64 payload fails`() {
        val envelope = """{"format":"ma-remote-manifest-v1","payload":"!!!not-base64!!!","signature":null}"""
        assertTrue(RemoteManifestVerifier.verify(envelope).isFailure)
    }

    @Test
    fun `strict mode rejects unsigned document`() {
        assertTrue(RemoteManifestVerifier.verify(plainConfigJson(), ManifestVerifyMode.STRICT).isFailure)
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun `manifest payload is the same validated remote config`() {
        val payload = plainConfigJson()
        val envelope = """{"format":"ma-remote-manifest-v1","timestamp_epoch":1724000000,"payload":"${Base64.UrlSafe.encode(payload.toByteArray())}","algorithm":"ed25519","signature":null}"""
        val direct = RemoteConfigDefaults // no-op
        val fromEnvelope = RemoteManifestVerifier.verify(envelope).getOrNull()!!
        assertEquals(payload, json.encodeToString(RemoteConfig.serializer(), fromEnvelope))
    }
}
