package com.inspiredandroid.kai.hotupdate

import com.inspiredandroid.kai.gateway.ProviderRegistry
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertNull

class RemoteCatalogTest {

    @AfterTest
    fun cleanup() {
        ProviderRegistry.applyRemoteCatalog(emptyList())
        RemoteManifestVerifier.pinPublicKeyHex("")
    }

    @Test
    fun `validated catalog accepts sane entries and strips hostile ones`() {
        val catalog = RemoteCatalog(
            providers = listOf(
                RemoteProviderCatalogEntry("good-provider", "Good", baseUrl = "https://good.example.com/v1"),
                RemoteProviderCatalogEntry("", "Empty id", baseUrl = "https://x.example.com"),
                RemoteProviderCatalogEntry("ftp-evil", "Evil", baseUrl = "ftp://evil.example.com"),
                RemoteProviderCatalogEntry("http-evil", "Evil2", baseUrl = "http://evil.example.com"),
            ),
            model_catalog = listOf(
                RemoteModelCatalogEntry("good-provider", "fast", qualityTier = 3, speedTier = 2),
                RemoteModelCatalogEntry("good-provider", "bad", qualityTier = 99, speedTier = 0),
                RemoteModelCatalogEntry("good-provider", "expensive", inputPricePerMTok = 99999.0),
            ),
        )
        val validated = catalog.validated().getOrNull()
        assertNotNull(validated)
        assertEquals(1, validated.providers.size)
        assertEquals(1, validated.model_catalog.size)
        assertEquals("good-provider", validated.providers.single().id)
        assertEquals("fast", validated.model_catalog.single().id)
    }

    @Test
    fun `version gate drops a catalog when the app is too old`() {
        val catalog = RemoteCatalog(catalog_version = 5, min_app_version = "9.9.9", providers = listOf(RemoteProviderCatalogEntry("p", "P", baseUrl = "https://p.example.com/v1")))
        val validated = catalog.validated(SemVer(3, 9, 0)).getOrNull()
        assertNotNull(validated)
        assertTrue(validated.providers.isEmpty(), "old app must not receive new catalog entries")
    }

    @Test
    fun `local loopback URLs are accepted, everything else is rejected`() {
        val catalog = RemoteCatalog(providers = listOf(
            RemoteProviderCatalogEntry("ollama-local", "Ollama Local", baseUrl = "http://localhost:11434/v1"),
            RemoteProviderCatalogEntry("localhost-ip", "Localhost IP", baseUrl = "http://127.0.0.1:8000/v1"),
            RemoteProviderCatalogEntry("http-remote", "Http Remote", baseUrl = "http://remote.example.com"),
        ))
        val validated = catalog.validated().getOrNull()
        assertNotNull(validated)
        assertEquals(2, validated.providers.size)
        assertTrue(validated.providers.all { it.id.startsWith("ollama") || it.id.startsWith("localhost") })
    }

    @Test
    fun `unknown protocol ids are stripped from remote providers`() {
        val catalog = RemoteCatalog(providers = listOf(
            RemoteProviderCatalogEntry("ok-provider", "OK", protocolId = "openai_chat", baseUrl = "https://ok.example.com/v1"),
            RemoteProviderCatalogEntry("weird", "Weird", protocolId = "gopher_protocol", baseUrl = "https://weird.example.com"),
        ))
        val validated = catalog.validated().getOrNull()
        assertNotNull(validated)
        assertEquals(1, validated.providers.size)
        assertEquals("ok-provider", validated.providers.single().id)
    }

    @Test
    fun `applyToRegistry merges without overriding builtins`() {
        val catalog = RemoteCatalog(providers = listOf(
            RemoteProviderCatalogEntry("custom-llm", "Custom LLM", baseUrl = "https://custom.example.com/api/v1"),
            RemoteProviderCatalogEntry("openai", "Shadow OpenAI", baseUrl = "https://evil.example.com/v1"),
        ))
        catalog.applyToRegistry()
        assertTrue(ProviderRegistry.has("custom-llm"))
        assertEquals("https://api.openai.com/v1", ProviderRegistry.get("openai")?.baseUrl)
    }

    @Test
    fun `unsigned bare documents are rejected by verifyCatalogPayload`() {
        val plain = """{"catalog_version":1,"providers":[]}"""
        val result = RemoteManifestVerifier.verifyCatalogPayload(plain)
        assertTrue(result.isFailure, "unsigned catalog must never be accepted")
    }

    @Test
    fun `malformed envelopes are rejected with descriptive errors`() {
        val missingPayload = """{"format":"ma-remote-manifest-v1","signature":"abc"}"""
        val result = RemoteManifestVerifier.verifyCatalogPayload(missingPayload)
        assertTrue(result.isFailure)
        assertTrue((result.exceptionOrNull()?.message ?: "").contains("payload"))
    }

    @Test
    fun `signed catalog envelope round-trips the payload correctly`() {
        val payload = """{"catalog_version":1,"providers":[],"model_catalog":[]}"""
        val (privateKey, publicKey) = generateEd25519KeypairForTest()
        val signature = signEd25519ForTest(privateKey, payload.encodeToByteArray())
        val envelope = signedCatalogEnvelope(publicKey, payload, signature)
        RemoteManifestVerifier.pinPublicKeyHex(publicKey)
        val result = RemoteManifestVerifier.verifyCatalogPayload(envelope)
        if (result.isFailure) println("DIAG-FAILURE: ${result.exceptionOrNull()?.message}")
        assertTrue(result.isSuccess)
        assertEquals(payload, result.getOrNull())
        RemoteManifestVerifier.pinPublicKeyHex("")
    }

    @Test
    fun `wrong signature on a catalog envelope is rejected`() {
        val payload = """{"catalog_version":1,"providers":[]}"""
        val (privateKey, publicKey) = generateEd25519KeypairForTest()
        val wrongPayload = payload.replace("1", "2")
        val signature = signEd25519ForTest(privateKey, wrongPayload.encodeToByteArray())
        val envelope = signedCatalogEnvelope(publicKey, payload, signature)
        RemoteManifestVerifier.pinPublicKeyHex(publicKey)
        val result = RemoteManifestVerifier.verifyCatalogPayload(envelope)
        if (result.isSuccess) println("DIAG-UNEXPECTED-SUCCESS with payload tampered")
        assertTrue(result.isFailure)
        RemoteManifestVerifier.pinPublicKeyHex("")
    }
}

// Minimal Ed25519 test helpers using java.security when available.
private fun generateEd25519KeypairForTest(): Pair<String, String> {
    val kpg = java.security.KeyPairGenerator.getInstance("Ed25519")
    val pair = kpg.generateKeyPair()
    return pair.private.encoded.toHex() to pair.public.encoded.toHex()
}

private fun signEd25519ForTest(privateKeyHex: String, message: ByteArray): String {
    val spec = java.security.spec.PKCS8EncodedKeySpec(decodeHex(privateKeyHex))
    val key = java.security.KeyFactory.getInstance("Ed25519").generatePrivate(spec)
    val signer = java.security.Signature.getInstance("Ed25519")
    signer.initSign(key)
    signer.update(message)
    return signer.sign().toBase64UrlSafe()
}

private fun signedCatalogEnvelope(publicKeyHex: String, payload: String, signatureBase64Url: String): String =
    """{"format":"ma-remote-manifest-v1","payload":"${payload.encodeToByteArray().toBase64UrlSafe()}","signature":"$signatureBase64Url"}"""

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
private fun ByteArray.toBase64UrlSafe(): String = java.util.Base64.getUrlEncoder().encodeToString(this)
private fun String.fromBase64UrlSafe(): ByteArray = java.util.Base64.getUrlDecoder().decode(this)
private fun decodeHex(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
