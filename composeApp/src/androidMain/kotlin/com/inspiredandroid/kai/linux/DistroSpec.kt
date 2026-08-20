package com.inspiredandroid.kai.linux

import android.os.Build
import java.io.File
import java.io.IOException
import java.net.URL
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths

// Cap at 3.22: Alpine 3.23+ ships apk-tools 3, which uses execveat() in a way
// proot does not support, so `apk update` fails under the sandbox runtime.
private const val ALPINE_VERSION = "3.22.5"
private const val ALPINE_BRANCH = "v3.22"

private val ALPINE_MIRRORS = listOf(
    "https://dl-cdn.alpinelinux.org/alpine",
    "https://mirrors.edge.kernel.org/alpine",
    "https://ftp.halifax.rwth-aachen.de/alpine",
    "https://alpine.ethz.ch/alpine",
    "https://mirror.csclub.uwaterloo.ca/alpine",
    "https://mirrors.tuna.tsinghua.edu.cn/alpine",
)

private const val LXC_INDEX = "https://images.linuxcontainers.org/meta/1.0/index-user"
private const val LXC_BASE = "https://images.linuxcontainers.org"
private const val DEBIAN_RELEASE = "trixie"

sealed interface DistroSpec {
    val distro: LinuxDistro
    fun arch(): String
    val archiveName: String
    fun rootfsUrls(): List<String>
    fun configure(rootfsDir: File)
    val prootArgs: List<String> get() = emptyList()
    val env: Map<String, String> get() = emptyMap()

    companion object {
        fun of(distro: LinuxDistro): DistroSpec = when (distro) {
            LinuxDistro.ALPINE -> AlpineSpec
            LinuxDistro.DEBIAN -> DebianSpec
            LinuxDistro.UBUNTU -> UbuntuSpec
        }
    }
}

object AlpineSpec : DistroSpec {
    override val distro = LinuxDistro.ALPINE
    override val archiveName = "rootfs.tar.gz"

    override fun arch(): String {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        return when {
            abi.startsWith("arm64") -> "aarch64"
            abi.startsWith("armeabi") -> "armhf"
            abi.startsWith("x86_64") -> "x86_64"
            abi.startsWith("x86") -> "x86"
            else -> "aarch64"
        }
    }

    override fun rootfsUrls(): List<String> {
        val arch = arch()
        return ALPINE_MIRRORS.map { base ->
            "$base/$ALPINE_BRANCH/releases/$arch/alpine-minirootfs-$ALPINE_VERSION-$arch.tar.gz"
        }
    }

    override fun configure(rootfsDir: File) {
        TarExtractor.makeWritable(rootfsDir)
        TarExtractor.writeResolvConf(rootfsDir)
        writeRepositories(rootfsDir, ALPINE_MIRRORS.first())
    }

    val mirrors: List<String> = ALPINE_MIRRORS

    fun writeRepositories(rootfsDir: File, mirrorBase: String) {
        val apkDir = File(rootfsDir, "etc/apk")
        apkDir.mkdirs()
        File(apkDir, "repositories").writeText(
            "$mirrorBase/$ALPINE_BRANCH/main\n$mirrorBase/$ALPINE_BRANCH/community\n",
        )
    }
}

object DebianSpec : DistroSpec {
    override val distro = LinuxDistro.DEBIAN
    override val archiveName = "rootfs.tar.xz"

    override fun arch(): String {
        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        return when {
            abi.startsWith("arm64") -> "arm64"
            abi.startsWith("armeabi") -> "armhf"
            abi.startsWith("x86_64") -> "amd64"
            abi.startsWith("x86") -> "i386"
            else -> "arm64"
        }
    }

    /**
     * Debian 13 (trixie) resolution order:
     * 1. v4.2.0 production arm64 asset, pre-installed with CLI tools + OpenCode.
     * 2. newest trixie image from the Linux Containers index.
     * 3. recent pinned LXC path if the index is temporarily unreachable.
     */
    override fun rootfsUrls(): List<String> {
        val arch = arch()
        val githubAssetUrl = when {
            arch == "arm64" -> "https://github.com/Mtzallqmy/MoatazAlalqamiAI/releases/download/v4.2.0/moataz-debian-rootfs-arm64-v13.tar.xz"
            else -> null
        }
        val fallbackPath = "/images/debian/$DEBIAN_RELEASE/$arch/default/20260818_05:24/rootfs.tar.xz"
        val lxcCandidates = try {
            val index = URL(LXC_INDEX).openStream().bufferedReader().use { it.readText() }
            val line = index.lineSequence()
                .filter { it.startsWith("debian;$DEBIAN_RELEASE;$arch;default;") }
                .maxOrNull()
                ?: throw IOException("No Debian $DEBIAN_RELEASE image for $arch in LXC index")
            val path = line.split(';').getOrNull(5)?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw IOException("Malformed LXC index line: $line")
            listOf(LXC_BASE + path.removeSuffix("/") + "/rootfs.tar.xz")
        } catch (_: Exception) {
            listOf(LXC_BASE + fallbackPath)
        }
        return if (githubAssetUrl != null) listOf(githubAssetUrl) + lxcCandidates else lxcCandidates
    }

    override fun configure(rootfsDir: File) {
        TarExtractor.makeWritable(rootfsDir)
        TarExtractor.writeResolvConf(rootfsDir)
        ensureAptStateDirectories(rootfsDir)
        repairUsrMergeSymlinks(rootfsDir)
        ensureWorkingShell(rootfsDir)
        File(rootfsDir, "etc/dpkg/dpkg.cfg.d").mkdirs()
        File(rootfsDir, "etc/dpkg/dpkg.cfg.d/force-unsafe-io").writeText("force-unsafe-io\n")
    }

    override val prootArgs = listOf("--link2symlink", "-L")
    override val env = mapOf("DEBIAN_FRONTEND" to "noninteractive")
}

/** Ubuntu remains available only as a compatibility environment. */
object UbuntuSpec : DistroSpec {
    override val distro = LinuxDistro.UBUNTU
    override val archiveName = "ubuntu-cloud-rootfs.tar.xz"
    private const val UBUNTU_RELEASE = "26.04"

    override fun arch(): String {
        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        return when {
            abi.startsWith("arm64") -> "arm64"
            abi.startsWith("armeabi") -> "armhf"
            abi.startsWith("x86_64") -> "amd64"
            abi.startsWith("x86") -> "i386"
            else -> "arm64"
        }
    }

    override fun rootfsUrls(): List<String> {
        val arch = arch()
        val githubAssetUrl = when {
            arch == "arm64" -> "https://github.com/Mtzallqmy/MoatazAlalqamiAI/releases/download/v4.1.0/moataz-ubuntu-rootfs-arm64.tar.xz"
            arch == "amd64" -> "https://github.com/Mtzallqmy/MoatazAlalqamiAI/releases/download/v4.1.0/moataz-ubuntu-rootfs-x86_64.tar.xz"
            else -> null
        }
        val cdnUrl = "https://cloud-images.ubuntu.com/releases/$UBUNTU_RELEASE/release/ubuntu-$UBUNTU_RELEASE-server-cloudimg-$arch-root.tar.gz"
        val lxcUrl = "$LXC_BASE/images/ubuntu/$UBUNTU_RELEASE/$arch/default/latest/rootfs.tar.xz"
        return buildList {
            if (githubAssetUrl != null) add(githubAssetUrl)
            add(cdnUrl)
            add(lxcUrl)
        }
    }

    override fun configure(rootfsDir: File) {
        TarExtractor.makeWritable(rootfsDir)
        TarExtractor.writeResolvConf(rootfsDir)
        ensureAptStateDirectories(rootfsDir)
        repairUsrMergeSymlinks(rootfsDir)
        ensureWorkingShell(rootfsDir)
        File(rootfsDir, "etc/dpkg/dpkg.cfg.d").mkdirs()
        File(rootfsDir, "etc/dpkg/dpkg.cfg.d/force-unsafe-io").writeText("force-unsafe-io\n")
    }

    override val prootArgs = listOf("--link2symlink", "-L")
    override val env = mapOf("DEBIAN_FRONTEND" to "noninteractive")
}

private fun ensureAptStateDirectories(rootfsDir: File) {
    listOf(
        "var/lib/apt/lists/partial",
        "var/cache/apt/archives/partial",
        "var/lib/dpkg/updates",
        "var/lib/dpkg/info",
        "var/lib/dpkg/alternatives",
        "var/log",
        "run/lock",
        "tmp",
    ).forEach { File(rootfsDir, it).mkdirs() }
}

/**
 * Repair usr-merge links using NOFOLLOW_LINKS. File.exists() follows symlinks,
 * so a broken link reports false while still occupying the directory entry;
 * deleting that entry explicitly prevents FileAlreadyExists during repair.
 */
private fun repairUsrMergeSymlinks(rootfsDir: File) {
    for ((linkPath, target) in listOf(
        "bin" to "usr/bin",
        "sbin" to "usr/sbin",
        "lib" to "usr/lib",
        "lib64" to "usr/lib64",
    )) {
        val link = File(rootfsDir, linkPath)
        val targetFile = File(rootfsDir, target)
        if (!targetFile.exists()) continue

        val path = link.toPath()
        val entryExists = Files.exists(path, LinkOption.NOFOLLOW_LINKS)
        val usable = runCatching { link.exists() && link.canonicalFile.exists() }.getOrDefault(false)
        if (usable) continue

        runCatching {
            if (entryExists) Files.deleteIfExists(path)
            Files.createSymbolicLink(path, Paths.get(target))
        }
    }
}

/**
 * Guarantee a real executable /bin/sh even when usr-merge links were lost.
 * Production rootfs carries sh.real from busybox-static; downloaded images can
 * still fall back to dash. Broken /bin/sh symlinks are removed without following
 * them before the real file is copied.
 */
private fun ensureWorkingShell(rootfsDir: File) {
    val sh = File(rootfsDir, "bin/sh")
    val works = runCatching { sh.exists() && sh.canonicalFile.exists() }.getOrDefault(false)
    if (works) return

    val fallbacks = listOf("bin/sh.real", "usr/bin/sh.real", "usr/bin/dash")
    for (fallback in fallbacks) {
        val src = File(rootfsDir, fallback)
        if (!src.isFile) continue
        sh.parentFile?.mkdirs()
        runCatching { Files.deleteIfExists(sh.toPath()) }
        val copied = runCatching { src.copyTo(sh, overwrite = true) }.isSuccess
        if (copied && sh.isFile) {
            sh.setExecutable(true, false)
            return
        }
    }
}
