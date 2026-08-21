package com.inspiredandroid.kai.linux

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class TarExtractorTest {
    @Test
    fun `safe extraction accepts complete archive`() = withTempDirectory { directory ->
        val archive = File(directory, "valid.tar.gz")
        archive.writeBytes(gzip(tarFile("workspace.txt", "healthy".encodeToByteArray()) + endMarker()))

        val output = File(directory, "output")
        TarExtractor.extractSafe(archive, output)

        assertEquals("healthy", File(output, "workspace.txt").readText())
    }

    @Test
    fun `safe extraction rejects traversal instead of skipping it`() = withTempDirectory { directory ->
        val archive = File(directory, "traversal.tar.gz")
        archive.writeBytes(gzip(tarFile("../escaped", byteArrayOf()) + endMarker()))

        assertFails { TarExtractor.extractSafe(archive, File(directory, "output")) }
    }

    @Test
    fun `safe extraction rejects truncated file data`() = withTempDirectory { directory ->
        val header = tarHeader("partial.txt", size = 32)
        val archive = File(directory, "truncated-data.tar.gz")
        archive.writeBytes(gzip(header + "short".encodeToByteArray()))

        assertFails { TarExtractor.extractSafe(archive, File(directory, "output")) }
    }

    @Test
    fun `safe extraction rejects truncated entry padding`() = withTempDirectory { directory ->
        val archive = File(directory, "truncated-padding.tar.gz")
        archive.writeBytes(gzip(tarHeader("partial.txt", size = 1) + byteArrayOf(1)))

        assertFails { TarExtractor.extractSafe(archive, File(directory, "output")) }
    }

    @Test
    fun `safe extraction rejects truncated header`() = withTempDirectory { directory ->
        val archive = File(directory, "truncated-header.tar.gz")
        archive.writeBytes(gzip(ByteArray(100) { 1 }))

        assertFails { TarExtractor.extractSafe(archive, File(directory, "output")) }
    }

    private fun tarFile(name: String, data: ByteArray): ByteArray {
        val padding = (512 - data.size % 512) % 512
        return tarHeader(name, data.size.toLong()) + data + ByteArray(padding)
    }

    private fun tarHeader(name: String, size: Long): ByteArray = ByteArray(512).also { header ->
        name.encodeToByteArray().copyInto(header, endIndex = name.length)
        writeOctal(header, 100, 8, 0b110_100_100)
        writeOctal(header, 124, 12, size)
        header[156] = '0'.code.toByte()
    }

    private fun writeOctal(target: ByteArray, offset: Int, length: Int, value: Long) {
        val encoded = value.toString(8).padStart(length - 1, '0').encodeToByteArray()
        encoded.copyInto(target, destinationOffset = offset)
        target[offset + length - 1] = 0
    }

    private fun endMarker(): ByteArray = ByteArray(1024)

    private fun gzip(raw: ByteArray): ByteArray = ByteArrayOutputStream().use { output ->
        GZIPOutputStream(output).use { it.write(raw) }
        output.toByteArray()
    }

    private fun withTempDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("moataz-tar-test").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
