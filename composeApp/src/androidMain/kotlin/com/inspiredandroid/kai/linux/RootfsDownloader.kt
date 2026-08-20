package com.inspiredandroid.kai.linux

import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.security.MessageDigest

private const val BUFFER_SIZE = 64 * 1024

/**
 * Streams a rootfs tarball to disk, trying each candidate URL in turn.
 *
 * Alpine publishes the same minirootfs on several mirrors, so a mirror that is
 * down or rate-limiting costs a retry rather than the install. Debian resolves
 * to a single Linux Containers URL, which simply means a one-element list.
 */
class RootfsDownloader(private val httpClient: HttpClient) {

    private val productionHosts = setOf("images.linuxcontainers.org")

    /** Debian production downloads are accepted only with the publisher's SHA256SUMS. */
    suspend fun downloadVerified(
        urls: List<String>,
        targetFile: File,
        onProgress: (Float) -> Unit,
    ) {
        require(urls.isNotEmpty()) { "No download URL for this rootfs" }
        var lastError: Exception? = null
        for ((index, url) in urls.withIndex()) {
            try {
                val uri = URI(url)
                require(uri.scheme == "https" && uri.host in productionHosts) { "Untrusted rootfs host: ${uri.host}" }
                downloadFrom(url, targetFile, onProgress)
                val expected = fetchPublishedSha256(url)
                val actual = targetFile.sha256()
                check(actual == expected) { "Rootfs SHA-256 mismatch: expected $expected, got $actual" }
                return
            } catch (e: CancellationException) {
                targetFile.delete()
                throw e
            } catch (e: Exception) {
                lastError = e
                targetFile.delete()
                if (index < urls.lastIndex) onProgress(0f)
            }
        }
        throw IOException("All verified rootfs download mirrors failed", lastError)
    }

    /**
     * Cancellable through the calling coroutine. A failed URL deletes its partial
     * file and resets progress so the UI does not show a bar walking backwards.
     */
    suspend fun download(
        urls: List<String>,
        targetFile: File,
        onProgress: (Float) -> Unit,
    ) {
        require(urls.isNotEmpty()) { "No download URL for this rootfs" }
        targetFile.parentFile?.mkdirs()
        var lastError: Exception? = null
        for ((index, url) in urls.withIndex()) {
            try {
                downloadFrom(url, targetFile, onProgress)
                return
            } catch (e: CancellationException) {
                targetFile.delete()
                throw e
            } catch (e: Exception) {
                lastError = e
                targetFile.delete()
                if (index < urls.lastIndex) onProgress(0f)
            }
        }
        throw IOException("All rootfs download mirrors failed", lastError)
    }

    private suspend fun downloadFrom(
        url: String,
        targetFile: File,
        onProgress: (Float) -> Unit,
    ) {
        httpClient.prepareGet(url).execute { response ->
            if (!response.status.isSuccess()) {
                throw IOException("HTTP ${response.status.value} from $url")
            }
            val totalBytes = response.contentLength() ?: -1L
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(BUFFER_SIZE)
            var downloadedBytes = 0L

            FileOutputStream(targetFile).use { output ->
                while (!channel.isClosedForRead) {
                    currentCoroutineContext().ensureActive()
                    val bytesRead = channel.readAvailable(buffer)
                    if (bytesRead <= 0) break
                    output.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead
                    if (totalBytes > 0) {
                        onProgress((downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f))
                    }
                }
            }
            onProgress(1f)
        }
    }

    private suspend fun fetchPublishedSha256(url: String): String {
        val fileName = url.substringAfterLast('/')
        val sumsUrl = url.substringBeforeLast('/') + "/SHA256SUMS"
        val response = httpClient.get(sumsUrl)
        check(response.status.isSuccess()) { "HTTP ${response.status.value} from $sumsUrl" }
        val match = response.bodyAsText().lineSequence().map(String::trim).firstOrNull { line ->
            val name = line.substringAfterLast(' ').removePrefix("*")
            name == fileName
        } ?: error("No SHA-256 published for $fileName")
        return match.substringBefore(' ').lowercase().also {
            require(it.matches(Regex("[0-9a-f]{64}"))) { "Invalid SHA-256 in $sumsUrl" }
        }
    }
}

private fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().buffered().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
