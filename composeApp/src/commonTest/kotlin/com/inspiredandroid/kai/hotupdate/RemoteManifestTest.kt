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
 * untrusted documents are rejected while an explicit migration-only LAX mode
 * remains available for legacy local data.
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
    fun `legacy unsigned document is accepted only in explicit lax mode`() {
        val result = RemoteManifestVerifier.verify(plainConfigJson(), ManifestVerifyMode.LAX)
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
        assertTrue(RemoteManifestVerifier.verify(envelope).isFailure)
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
        assertTrue(RemoteManifestVerifier.verify(plainConfigJson()).isFailure)
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun `default mode rejects signature when no key is pinned`() {
        val payloadB64 = Base64.UrlSafe.encode(plainConfigJson().toByteArray())
        val signatureB64 = Base64.UrlSafe.encode("present-but-unverifiable".toByteArray())
        val envelope = """{"format":"ma-remote-manifest-v1","payload":"$payloadB64","signature":"$signatureB64"}"""
        RemoteManifestVerifier.pinPublicKeyHex("")
        assertTrue(RemoteManifestVerifier.verify(envelope).isFailure)
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun `manifest payload is the same validated remote config`() {
        val payload = plainConfigJson()
        val keyPair = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val signer = java.security.Signature.getInstance("Ed25519").apply {
            initSign(keyPair.private)
            update(payload.encodeToByteArray())
        }
        val envelope = """{"format":"ma-remote-manifest-v1","timestamp_epoch":1724000000,"payload":"${Base64.UrlSafe.encode(payload.toByteArray())}","algorithm":"ed25519","signature":"${Base64.UrlSafe.encode(signer.sign())}"}"""
        val publicKeyHex = keyPair.public.encoded.joinToString("") { "%02x".format(it) }

        RemoteManifestVerifier.pinPublicKeyHex(publicKeyHex)
        try {
            val fromEnvelope = RemoteManifestVerifier.verify(envelope).getOrThrow()
            assertEquals(payload, json.encodeToString(RemoteConfig.serializer(), fromEnvelope))
        } finally {
            RemoteManifestVerifier.pinPublicKeyHex("")
        }
    }
}
