package com.inspiredandroid.kai.runtime.distribution

/** The installer persists [nextOffset] and can resume without trusting partial data. */
data class RuntimeBundleRequest(
    val artifactName: String,
    val offsetBytes: Long,
    val maxBytes: Int,
)

data class RuntimeBundleChunk(
    val offsetBytes: Long,
    val bytes: ByteArray,
    val nextOffset: Long,
    val complete: Boolean,
)

sealed interface RuntimeBundleLocation {
    data class Embedded(val assetName: String) : RuntimeBundleLocation
    data class Remote(val httpsUrl: String) : RuntimeBundleLocation
}

interface RuntimeBundleSource {
    val location: RuntimeBundleLocation

    suspend fun read(request: RuntimeBundleRequest): RuntimeBundleChunk
}

object RuntimeBundleSourceGate {
    fun validate(
        distribution: AppDistribution,
        descriptor: RuntimeBundleDescriptor,
        source: RuntimeBundleSource,
        request: RuntimeBundleRequest,
        chunk: RuntimeBundleChunk,
        trustedRemoteHosts: Set<String> = emptySet(),
    ) {
        require(request.artifactName == descriptor.artifactName) { "Unexpected runtime artifact" }
        require(request.offsetBytes in 0 until descriptor.sizeBytes) { "Invalid runtime range offset" }
        require(request.maxBytes in 1..MAX_CHUNK_BYTES) { "Invalid runtime range size" }
        require(chunk.offsetBytes == request.offsetBytes) { "Runtime source returned the wrong offset" }
        require(chunk.bytes.isNotEmpty()) { "Runtime source returned an empty chunk" }
        require(chunk.bytes.size <= request.maxBytes) { "Runtime source exceeded the requested range" }
        require(chunk.nextOffset == chunk.offsetBytes + chunk.bytes.size) { "Runtime source returned an invalid cursor" }
        require(chunk.nextOffset <= descriptor.sizeBytes) { "Runtime source exceeded the artifact size" }
        require(chunk.complete == (chunk.nextOffset == descriptor.sizeBytes)) { "Runtime completion flag is inconsistent" }

        when (distribution.runtimeBundlePolicy()) {
            RuntimeBundlePolicy.EmbeddedRequired -> require(source.location is RuntimeBundleLocation.Embedded) {
                "Full/Offline builds require an embedded runtime"
            }
            RuntimeBundlePolicy.SignedRemoteRequired -> {
                val remote = requireNotNull(source.location as? RuntimeBundleLocation.Remote) {
                    "Lite builds require a signed remote runtime"
                }
                val host = validatedHttpsHost(remote.httpsUrl)
                require(host in trustedRemoteHosts.map { it.lowercase() }) { "Remote runtime host is not trusted" }
            }
        }
    }

    private fun validatedHttpsHost(value: String): String {
        require(value.startsWith("https://", ignoreCase = true)) { "Remote runtime URL must use HTTPS" }
        val authority = value.substringAfter("https://").substringBefore('/')
        require(authority.isNotBlank() && '@' !in authority && '#' !in value && '[' !in authority) {
            "Remote runtime URL must not contain credentials or fragments"
        }
        return authority.substringBefore(':').lowercase()
    }

    const val MAX_CHUNK_BYTES = 4 * 1024 * 1024
}
