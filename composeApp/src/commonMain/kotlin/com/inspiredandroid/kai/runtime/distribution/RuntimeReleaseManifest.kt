package com.inspiredandroid.kai.runtime.distribution

import com.inspiredandroid.kai.runtime.MoatazRuntimeContract
import kotlinx.serialization.Serializable

private val sha256Pattern = Regex("^[0-9a-f]{64}$")
private val identifierPattern = Regex("^[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}$")
private val base64UrlPattern = Regex("^[A-Za-z0-9_-]+={0,2}$")

@Serializable
data class RuntimeBundlePart(
    val name: String,
    val offsetBytes: Long,
    val sizeBytes: Long,
    val sha256: String,
)

@Serializable
data class RuntimeBundleDescriptor(
    val artifactName: String,
    val sizeBytes: Long,
    val sha256: String,
    val parts: List<RuntimeBundlePart>,
)

@Serializable
data class RuntimeReleaseManifest(
    val schemaVersion: Int,
    val releaseId: String,
    val versions: ProductVersions,
    val minimumAppVersion: ReleaseVersion,
    val maximumAppVersionExclusive: ReleaseVersion? = null,
    val distro: String,
    val distroVersionMajor: Int,
    val codename: String,
    val architecture: String,
    val requiredCli: Map<String, ReleaseVersion>,
    val bundle: RuntimeBundleDescriptor,
    val createdAtEpochSeconds: Long,
) {
    fun validateFor(appVersion: ReleaseVersion): Result<RuntimeReleaseManifest> = runCatching {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) { "Unsupported runtime manifest schema: $schemaVersion" }
        require(identifierPattern.matches(releaseId)) { "Invalid release id" }
        require(appVersion >= minimumAppVersion) { "Runtime requires app $minimumAppVersion or newer" }
        require(maximumAppVersionExclusive == null || appVersion < maximumAppVersionExclusive) {
            "Runtime is incompatible with app $appVersion"
        }
        require(distro == MoatazRuntimeContract.distro) { "Runtime distro must be Debian" }
        require(distroVersionMajor == MoatazRuntimeContract.versionMajor) { "Runtime must use Debian 13" }
        require(codename == MoatazRuntimeContract.codename) { "Runtime codename must be trixie" }
        require(architecture == MoatazRuntimeContract.architecture) { "Runtime architecture must be arm64" }
        require(createdAtEpochSeconds > 0) { "Missing creation timestamp" }
        require(requiredCli.keys.containsAll(MoatazRuntimeContract.requiredCli)) { "Runtime CLI contract is incomplete" }
        require(requiredCli[MoatazRuntimeContract.requiredEmbeddedAgent] != null) { "OpenCode version is missing" }
        bundle.validate()
        this
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

private fun RuntimeBundleDescriptor.validate() {
    require(identifierPattern.matches(artifactName)) { "Invalid artifact name" }
    require(sizeBytes > 0) { "Runtime bundle is empty" }
    require(sha256Pattern.matches(sha256)) { "Invalid runtime bundle SHA-256" }
    require(parts.isNotEmpty()) { "Runtime bundle has no parts" }
    require(parts.map { it.name }.distinct().size == parts.size) { "Duplicate runtime bundle part" }

    var expectedOffset = 0L
    parts.forEach { part ->
        require(identifierPattern.matches(part.name)) { "Invalid runtime bundle part name" }
        require(part.offsetBytes == expectedOffset) { "Runtime bundle parts are not contiguous" }
        require(part.sizeBytes > 0) { "Runtime bundle part is empty" }
        require(sha256Pattern.matches(part.sha256)) { "Invalid runtime bundle part SHA-256" }
        require(part.sizeBytes <= Long.MAX_VALUE - expectedOffset) { "Runtime bundle size overflow" }
        expectedOffset += part.sizeBytes
    }
    require(expectedOffset == sizeBytes) { "Runtime bundle part sizes do not match the artifact" }
}

@Serializable
data class SignedRuntimeManifestEnvelope(
    val format: String,
    val keyId: String,
    val algorithm: String,
    val payloadBase64Url: String,
    val signatureBase64Url: String,
)

fun interface RuntimeManifestSignatureVerifier {
    fun verify(keyId: String, payloadBase64Url: String, signatureBase64Url: String): Boolean
}

/** Structural and cryptographic gate. There is deliberately no unsigned or LAX mode. */
object RuntimeManifestEnvelopeGate {
    const val FORMAT = "moataz-runtime-release-v1"
    const val ALGORITHM = "ed25519"

    fun verify(
        envelope: SignedRuntimeManifestEnvelope,
        trustedKeyIds: Set<String>,
        signatureVerifier: RuntimeManifestSignatureVerifier,
    ): Result<String> = runCatching {
        require(envelope.format == FORMAT) { "Unsupported runtime envelope format" }
        require(envelope.algorithm == ALGORITHM) { "Unsupported runtime signature algorithm" }
        require(identifierPattern.matches(envelope.keyId)) { "Invalid runtime signing key id" }
        require(envelope.keyId in trustedKeyIds) { "Runtime signing key is not trusted" }
        require(base64UrlPattern.matches(envelope.payloadBase64Url)) { "Runtime manifest payload is malformed" }
        require(base64UrlPattern.matches(envelope.signatureBase64Url)) { "Runtime manifest signature is malformed" }
        require(signatureVerifier.verify(envelope.keyId, envelope.payloadBase64Url, envelope.signatureBase64Url)) {
            "Runtime manifest signature verification failed"
        }
        envelope.payloadBase64Url
    }
}
