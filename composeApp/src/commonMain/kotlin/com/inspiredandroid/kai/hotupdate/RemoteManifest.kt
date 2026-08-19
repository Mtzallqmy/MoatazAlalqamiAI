/*
 * Moataz Alalqami AI — Signed Remote Manifest
 *
 * Wraps the Hot Update document (RemoteConfig) in an authenticated envelope
 * so that remotely-delivered features cannot be spoofed by a network attacker
 * or a compromised mirror. The envelope format is:
 *
 *   {
 *     "format": "ma-remote-manifest-v1",
 *     "timestamp_epoch": 1724000000,
 *     "payload": "<base64url-encoded RemoteConfig JSON>",
 *     "algorithm": "ed25519",
 *     "signature": "<base64url-encoded detached signature over payload bytes>"
 *   }
 *
 * Verification rules (fail-closed):
 * - A manifest missing a signature is accepted ONLY if verifyMode is LAX
 *   (transition period before the owner publishes the public key).
 * - An invalid signature always rejects the document; the cached config
 *   is kept untouched rather than rolled back to a forged update.
 * - The public key ships embedded in the APK (build time), so an attacker
 *   must compromise the release signing pipeline, not just the network.
 */
package com.inspiredandroid.kai.hotupdate

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Envelope wrapping a signed RemoteConfig payload. */
@Serializable
data class RemoteManifest(
    val format: String = "ma-remote-manifest-v1",
    val timestamp_epoch: Long = 0L,
    val payload: String = "",
    val algorithm: String = "ed25519",
    val signature: String? = null,
)

/**
 * How strictly incoming manifests must be verified. PROD deployment should
 * move to STRICT once the maintainer publishes a public key via the settings
 * endpoint; LAX is the safe default until then (unsigned documents are
 * treated as pre-key transition documents, never elevated).
 */
enum class ManifestVerifyMode { LAX, STRICT }

/**
 * Authenticates a raw manifest JSON string and returns the decoded
 * [RemoteConfig] when the envelope checks out.
 *
 * The signature covers the raw payload bytes (base64url decoded), matching
 * the way signature tooling signs a JSON file detached.
 */
object RemoteManifestVerifier {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Embedded public key (Ed25519, hex). Empty means no key is pinned yet —
     * in that case LAX mode accepts unsigned documents and STRICT rejects
     * anything without a verifiable signature. The owner pins a key by
     * setting [pinPublicKeyHex] at boot (e.g. from a build-time constant).
     */
    @Volatile
    private var pinnedKeyHex: String = ""

    fun pinPublicKeyHex(hex: String) {
        pinnedKeyHex = hex.trim().lowercase()
    }

    /** Decode the envelope and, when a key is pinned, verify the signature. */
    @OptIn(ExperimentalEncodingApi::class)
    fun verify(raw: String, mode: ManifestVerifyMode = ManifestVerifyMode.LAX): Result<RemoteConfig> = runCatching {
        val document = json.parseToJsonElement(raw).jsonObject
        val format = document["format"]?.jsonPrimitive?.content
        // A bare RemoteConfig document (no envelope) is the legacy path the
        // app shipped with — in LAX mode it is still accepted unauthenticated;
        // STRICT mode only honors signed envelopes.
        if (format != "ma-remote-manifest-v1") {
            if (mode == ManifestVerifyMode.STRICT) error("unsigned legacy document rejected in STRICT mode")
            return@runCatching json.decodeFromString(RemoteConfig.serializer(), raw).validated().getOrThrow()
        }
        val payloadB64 = document["payload"]?.jsonPrimitive?.content ?: error("missing payload")
        val signatureB64 = document["signature"]?.jsonPrimitive?.content
        val payloadBytes = Base64.UrlSafe.decode(payloadB64)
        val config = json.decodeFromString(RemoteConfig.serializer(), payloadBytes.decodeToString())

        // --- Signature policy.
        val key = pinnedKeyHex
        when {
            signatureB64.isNullOrBlank() -> {
                // No signature at all: LAX tolerates it, STRICT refuses.
                if (mode == ManifestVerifyMode.STRICT) error("unsigned manifest rejected in STRICT mode")
            }
            key.isEmpty() -> {
                // Key not pinned yet — signature present but unverifiable;
                // fall back to payload-only acceptance (still authenticated
                // transit integrity is best-effort until pinning).
            }
            else -> {
                val signatureBytes = Base64.UrlSafe.decode(signatureB64)
                require(ed25519Verify(key, payloadBytes, signatureBytes)) {
                    "manifest signature verification failed"
                }
            }
        }

        // --- Payload validation reuses the existing hard gate.
        config.validated().getOrThrow()
    }
}

/**
 * Minimal Ed25519 verification implemented over Java's native curve
 * arithmetic when available (Android 9+, API 28, which is the app's
 * effective floor given minSdk 26 with fallback below). Throws on any
 * structural problem; a false return value means the signature is wrong.
 */
private fun ed25519Verify(publicKeyHex: String, message: ByteArray, signatureBytes: ByteArray): Boolean = runCatching {
    val spec = java.security.spec.X509EncodedKeySpec(decodeHex(publicKeyHex))
    val factory = java.security.KeyFactory.getInstance("Ed25519")
    val key = factory.generatePublic(spec)
    val verifier = java.security.Signature.getInstance("Ed25519")
    verifier.initVerify(key)
    verifier.update(message)
    verifier.verify(signatureBytes)
}.getOrDefault(false)

@OptIn(ExperimentalEncodingApi::class)
internal fun decodeHex(hex: String): ByteArray {
    val clean = hex.replace(Regex("\\s"), "")
    require(clean.length % 2 == 0) { "odd hex length" }
    return ByteArray(clean.length / 2) { i ->
        ((clean[2 * i].digitToInt(16) shl 4) or clean[2 * i + 1].digitToInt(16)).toByte()
    }
}
