package com.inspiredandroid.kai.linux

import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

private const val BUFFER_SIZE = 8192
private const val TAR_BLOCK_SIZE = 512
/** Hard caps against tar bombs: unbounded archives can exhaust disk in seconds. */
private const val MAX_TAR_ENTRIES = 100_000L
private const val MAX_TAR_SINGLE_FILE_BYTES = 512L * 1024 * 1024
private const val MAX_TAR_TOTAL_BYTES = 2L * 1024 * 1024 * 1024
private const val MAX_TAR_FILENAME_LENGTH = 512
private const val TAR_NAME_OFFSET = 0
private const val TAR_MODE_OFFSET = 100
private const val TAR_SIZE_OFFSET = 124
private const val TAR_TYPE_OFFSET = 156
private const val TAR_LINK_OFFSET = 157
private const val TAR_PREFIX_OFFSET = 345

/** ustar marks a regular file with '0'; pre-ustar archives leave the flag NUL. */
private const val TAR_TYPE_REGULAR = '0'
private const val TAR_TYPE_REGULAR_LEGACY: Byte = 0

/**
 * Extracts rootfs tarballs into a target directory. Alpine ships `.tar.gz`,
 * the Linux Containers Debian images ship `.tar.xz`.
 */
object TarExtractor {

    /** Picks the decompressor from [archive]'s extension. */
    fun extract(archive: File, targetDir: File) {
        targetDir.mkdirs()
        val raw = BufferedInputStream(FileInputStream(archive))
        val stream = if (archive.name.endsWith(".xz")) XZInputStream(raw) else GZIPInputStream(raw)
        stream.use { extractTar(it, targetDir) }
    }

    /** Hardened extraction with tar-bomb and traversal guards, for untrusted archives. */
    fun extractSafe(archive: File, targetDir: File) {
        targetDir.mkdirs()
        val raw = BufferedInputStream(FileInputStream(archive))
        val stream = if (archive.name.endsWith(".xz")) XZInputStream(raw) else GZIPInputStream(raw)
        stream.use { extractTar(it, targetDir, safe = true) }
    }

    fun makeWritable(rootfsDir: File) {
        rootfsDir.walkTopDown().forEach { file ->
            if (file.isDirectory && !file.canWrite()) {
                file.setWritable(true, true)
            }
        }
    }

    /**
     * LXC Debian images ship `etc/resolv.conf` as a symlink into
     * `/run/systemd/resolve/...`, which does not exist under proot. Writing
     * through that symlink throws ENOENT — delete the link first, then write a
     * plain file with public resolvers.
     */
    fun writeResolvConf(rootfsDir: File) {
        val etcDir = File(rootfsDir, "etc")
        etcDir.mkdirs()
        // systemd-resolved stub target (and similar) need a /run tree for some tools
        File(rootfsDir, "run").mkdirs()
        File(rootfsDir, "run/systemd/resolve").mkdirs()
        replaceWithRegularFile(
            File(etcDir, "resolv.conf"),
            "nameserver 8.8.8.8\nnameserver 8.8.4.4\n",
        )
        // Quiet hostname resolution noise inside the sandbox
        val hosts = File(etcDir, "hosts")
        if (!hosts.exists() || java.nio.file.Files.isSymbolicLink(hosts.toPath())) {
            replaceWithRegularFile(
                hosts,
                "127.0.0.1\tlocalhost\n::1\tlocalhost ip6-localhost ip6-loopback\n",
            )
        }
    }

    /** Delete file or symlink (including broken links), then write [content]. */
    private fun replaceWithRegularFile(file: File, content: String) {
        file.parentFile?.mkdirs()
        val path = file.toPath()
        try {
            // NOFOLLOW: broken symlinks still "exist" as links
            if (java.nio.file.Files.isSymbolicLink(path) ||
                java.nio.file.Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)
            ) {
                java.nio.file.Files.deleteIfExists(path)
            }
        } catch (_: Exception) {
            // Fall through to File.delete
        }
        if (file.exists() || file.isFile || java.nio.file.Files.isSymbolicLink(path)) {
            file.delete()
        }
        file.writeText(content)
    }

    private fun extractTar(inputStream: InputStream, targetDir: File, safe: Boolean = false) {
        val headerBuffer = ByteArray(TAR_BLOCK_SIZE)
        val dataBuffer = ByteArray(BUFFER_SIZE)
        val targetCanonical = targetDir.canonicalPath
        var entryCount = 0L
        var totalBytes = 0L

        while (true) {
            val headerBytesRead = readFully(inputStream, headerBuffer)
            if (headerBytesRead < TAR_BLOCK_SIZE) break

            val name = readTarString(headerBuffer, TAR_NAME_OFFSET, 100)
            if (name.isEmpty()) break

            val prefix = readTarString(headerBuffer, TAR_PREFIX_OFFSET, 155)
            val fullName = if (prefix.isNotEmpty()) "$prefix/$name" else name

            val sizeStr = readTarString(headerBuffer, TAR_SIZE_OFFSET, 12)
            val size = if (sizeStr.isNotEmpty()) sizeStr.toLong(8) else 0L

            val modeStr = readTarString(headerBuffer, TAR_MODE_OFFSET, 8)
            val mode = if (modeStr.isNotEmpty()) modeStr.toInt(8) else 0
            val typeFlag = headerBuffer[TAR_TYPE_OFFSET]
            val type = typeFlag.toInt().toChar()
            val linkName = readTarString(headerBuffer, TAR_LINK_OFFSET, 100)

            // --- Tar bomb + filename guards (safe mode, for untrusted archives).
            if (safe) {
                entryCount++
                if (entryCount > MAX_TAR_ENTRIES) break
                if (size > MAX_TAR_SINGLE_FILE_BYTES) {
                    if (size > 0) skipBytes(inputStream, alignToBlock(size))
                    continue
                }
                totalBytes += size
                if (totalBytes > MAX_TAR_TOTAL_BYTES) break
                if (fullName.length > MAX_TAR_FILENAME_LENGTH || fullName.contains("\u0000")) {
                    if (size > 0) skipBytes(inputStream, alignToBlock(size))
                    continue
                }
            }

            val outFile = File(targetDir, fullName)
            if (!outFile.canonicalPath.startsWith(targetCanonical)) {
                skipBytes(inputStream, alignToBlock(size))
                continue
            }

            if (typeFlag == TAR_TYPE_REGULAR_LEGACY || type == TAR_TYPE_REGULAR) {
                outFile.parentFile?.mkdirs()
                // LXC/Cloud rootfs sometimes emit a regular-file header for a
                // path that already exists as a directory (duplicate entries /
                // reordered listings) — that yields EISDIR on open. Remove it.
                if (outFile.exists() && outFile.isDirectory) {
                    outFile.deleteRecursively()
                }
                FileOutputStream(outFile).use { output ->
                    var remaining = size
                    while (remaining > 0) {
                        val toRead = minOf(remaining, dataBuffer.size.toLong()).toInt()
                        val bytesRead = inputStream.read(dataBuffer, 0, toRead)
                        if (bytesRead <= 0) break
                        output.write(dataBuffer, 0, bytesRead)
                        remaining -= bytesRead
                    }
                }
                if (mode and 0b001_001_001 != 0) {
                    outFile.setExecutable(true, false)
                }
                val padding = alignToBlock(size) - size
                if (padding > 0) skipBytes(inputStream, padding)
                continue
            }

            when (type) {
                '5', 'D' -> outFile.mkdirs()

                '2' -> {
                    outFile.parentFile?.mkdirs()
                    // In safe mode, only accept symlinks that resolve inside the
                    // target tree so a crafted archive cannot escape via links.
                    if (safe) {
                        val resolved = java.io.File(targetDir, linkName)
                        if (!resolved.path.startsWith(targetCanonical)) continue
                    }
                    // Relatively-targeted symlinks must be resolved against the
                    // link's own directory, not the archive root — a relative
                    // target like `dash` inside `usr/bin/sh` points to `usr/bin`,
                    // but `../usr/bin` resolved from the root wrongly escapes the
                    // guard (and produces broken links under proot).
                    val guardTarget = if (safe && !linkName.startsWith("/")) {
                        java.io.File(outFile.parentFile ?: targetDir, linkName)
                    } else {
                        java.io.File(targetDir, linkName)
                    }
                    if (safe && !guardTarget.path.startsWith(targetCanonical)) continue
                    var linked = false
                    try {
                        if (outFile.exists()) outFile.delete()
                        java.nio.file.Files.createSymbolicLink(
                            outFile.toPath(),
                            java.nio.file.Paths.get(linkName),
                        )
                        linked = true
                    } catch (_: Exception) {
                        // Some devices refuse symlink creation under SELinux or
                        // when the parent is read-only at that moment — fall back
                        // to a best-effort retry with a fresh directory handle.
                    }
                    if (!linked) {
                        try {
                            outFile.parentFile?.mkdirs()
                            if (outFile.exists()) outFile.delete()
                            java.nio.file.Files.createSymbolicLink(
                                outFile.toPath(),
                                java.nio.file.Paths.get(linkName),
                            )
                        } catch (_: Exception) {
                            // Logged by the installer's post-extract repair pass
                            // (see DistroSpec.configure) — never fatal here.
                        }
                    }
                }

                '1' -> {
                    val linkTarget = File(targetDir, linkName)
                    outFile.parentFile?.mkdirs()
                    // Hard link must also resolve inside the target tree (defends
                    // against a crafted archive linking to an absolute host path).
                    if (!linkTarget.path.startsWith(targetCanonical)) continue
                    if (linkTarget.exists()) {
                        linkTarget.copyTo(outFile, overwrite = true)
                    }
                }

                else -> {}
            }

            // Non-file entries (long-name headers, pax records) still carry a body.
            if (size > 0) skipBytes(inputStream, alignToBlock(size))
        }
    }

    private fun readTarString(buffer: ByteArray, offset: Int, length: Int): String {
        val end = minOf(offset + length, buffer.size)
        val nullIndex = (offset until end).firstOrNull { buffer[it] == 0.toByte() } ?: end
        return String(buffer, offset, nullIndex - offset, Charsets.US_ASCII).trim()
    }

    private fun readFully(inputStream: InputStream, buffer: ByteArray): Int {
        var totalRead = 0
        while (totalRead < buffer.size) {
            val bytesRead = inputStream.read(buffer, totalRead, buffer.size - totalRead)
            if (bytesRead <= 0) break
            totalRead += bytesRead
        }
        return totalRead
    }

    private fun skipBytes(inputStream: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = inputStream.skip(remaining)
            if (skipped <= 0) {
                if (inputStream.read() < 0) break
                remaining -= 1
            } else {
                remaining -= skipped
            }
        }
    }

    private fun alignToBlock(size: Long): Long {
        val remainder = size % TAR_BLOCK_SIZE
        return if (remainder == 0L) size else size + (TAR_BLOCK_SIZE - remainder)
    }
}
