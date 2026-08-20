package com.inspiredandroid.kai.linux

import android.os.Build
import java.io.File
import java.io.IOException
import java.net.URL

// Cap at 3.22: Alpine 3.23+ ships apk-tools 3, which uses execveat() in a way
// proot does not support, so `apk update` fails under the sandbox runtime.
// See termux/proot-distro#532 / #595.
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
private const val DEBIAN_RELEASE = "bookworm"

/**
 * The per-distribution facts the shared installer and proot launcher need:
 * where the rootfs comes from, what has to be fixed up after extraction, and
 * which proot flags and environment the distro's own tooling depends on.
 */
sealed interface DistroSpec {

    val distro: LinuxDistro

    /** This device's ABI in the distro's own architecture vocabulary. */
    fun arch(): String

    /** File name for the downloaded archive — the extension picks the decompressor. */
    val archiveName: String

    /**
     * Candidate download URLs, best first. May hit the network to resolve an
     * index, so call it off the main thread.
     */
    fun rootfsUrls(): List<String>

    /** Post-extraction fixes that must land before the first proot run. */
    fun configure(rootfsDir: File)

    /** proot flags this distro cannot work without. */
    val prootArgs: List<String> get() = emptyList()

    /** Environment every command in this distro should see. */
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

    /**
     * `apk update` is the first thing that has to work, and a mirror can be
     * unreachable even when the one that served the rootfs was fine. The
     * installer walks these, rewriting `repositories` each time.
     */
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
     * Newest default image for this device's architecture, e.g.
     * `debian;bookworm;arm64;default;20260731_05:24;/images/debian/bookworm/arm64/default/20260731_05:24/`.
     *
     * Resolution order for arm64 devices:
     * 1. Pre-built rootfs hosted on this repo's GitHub Releases (guaranteed
     *    availability, pre-installed base packages + OpenCode) — tried first.
     * 2. LXC index for the newest default image.
     * 3. Hardcoded recent LXC build as a last resort.
     *
     * Non-arm64 devices skip the GitHub asset and go straight to LXC.
     */
    override fun rootfsUrls(): List<String> {
        val arch = arch()
        val githubAssetUrl = when {
            arch == "arm64" -> "https://github.com/Mtzallqmy/MoatazAlalqamiAI/releases/download/v4.1.0/moataz-debian-rootfs-arm64.tar.xz"
            arch == "amd64" -> "https://github.com/Mtzallqmy/MoatazAlalqamiAI/releases/download/v4.1.0/moataz-debian-rootfs-x86_64.tar.xz"
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
            // Index unreachable — use the hardcoded fallback which is always pinned
            // to a recent, verified build for this architecture.
            listOf(LXC_BASE + fallbackPath)
        }
        return if (githubAssetUrl != null) listOf(githubAssetUrl) + lxcCandidates else lxcCandidates
    }

    override fun configure(rootfsDir: File) {
        TarExtractor.makeWritable(rootfsDir)
        TarExtractor.writeResolvConf(rootfsDir)
        // apt and dpkg assume these exist; an LXC image ships some of them empty
        // and the tar extractor skips empty directories it never saw an entry for.
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
        // usr-merge repair: on some devices the tar extractor cannot recreate the
        // root-level symlinks (bin -> usr/bin, sbin -> usr/sbin, lib -> usr/lib),
        // so proot fails every command with "/bin/sh not found". Re-create any
        // missing or broken ones and copy dash into place as a real /bin/sh file
        // as a last resort — proot only needs a working shell.
        repairUsrMergeSymlinks(rootfsDir)
        ensureWorkingShell(rootfsDir)
        // dpkg fsyncs every unpacked file by default, which on a phone turns a
        // base install into a multi-minute affair.
        File(rootfsDir, "etc/dpkg/dpkg.cfg.d").mkdirs()
        File(rootfsDir, "etc/dpkg/dpkg.cfg.d/force-unsafe-io").writeText("force-unsafe-io\n")
    }

    /**
     * Fixes usr-merge root symlinks that the extractor could not recreate.
     * Debian bookworm is fully usr-merged, so /bin, /sbin and /lib are symlinks
     * into /usr — if any of them is missing or a broken link, proot cannot even
     * exec `/bin/sh`. Recreate each one pointing at its usr/ counterpart.
     */
    private fun repairUsrMergeSymlinks(rootfsDir: File) {
        for ((linkPath, target) in listOf("bin" to "usr/bin", "sbin" to "usr/sbin", "lib" to "usr/lib", "lib64" to "usr/lib64")) {
            val link = File(rootfsDir, linkPath)
            val targetFile = File(rootfsDir, target)
            val broken = link.exists() && (link.canonicalFile != link.absoluteFile || !link.canonicalFile.exists())
            if (!link.exists() || broken) {
                if (targetFile.exists()) {
                    if (link.exists()) link.delete()
                    try {
                        java.nio.file.Files.createSymbolicLink(
                            link.toPath(),
                            java.nio.file.Paths.get(target),
                        )
                    } catch (_: Exception) {
                        // Remaining failure is handled by ensureWorkingShell.
                    }
                }
            }
        }
    }

    /**
     * Guarantees an executable `/bin/sh` exists on the rootfs: if /bin is still
     * broken after symlink repair, copy the embedded `bin/sh.real` (static
     * busybox) or dash directly into /bin/sh so proot can exec a shell at the
     * canonical path. Fully compatible with what the installer runs
     * (`/bin/sh -c ...`).
     */
    private fun ensureWorkingShell(rootfsDir: File) {
        val sh = File(rootfsDir, "bin/sh")
        val works = sh.exists() && sh.canonicalFile.exists()
        // Embedded rootfs ships `bin/sh.real` — a real busybox-static copy
        // (static-pie, needs no libc/linker symlinks) so proot always has a
        // shell even if every symlink was lost during extraction. Fall back
        // to dash (real ELF) afterwards.
        val fallbacks = listOf("bin/sh.real", "usr/bin/sh.real", "usr/bin/dash")
        if (!works) {
            for (fb in fallbacks) {
                val src = File(rootfsDir, fb)
                if (src.exists()) {
                    sh.parentFile?.mkdirs()
                    if (sh.exists()) sh.delete()
                    runCatching { src.copyTo(sh, overwrite = true) }
                    if (sh.exists()) {
                        sh.setExecutable(true, false)
                        break
                    }
                }
            }
        }
    }

    /**
     * dpkg unpacks packages via hardlinks, which Android's `protected_hardlinks`
     * policy refuses inside the app sandbox. Without the emulation the base
     * install fails with a dpkg subprocess error even though `apt-get update`
     * succeeded. `-L` is the companion lstat fix.
     */
    override val prootArgs = listOf("--link2symlink", "-L")

    override val env = mapOf("DEBIAN_FRONTEND" to "noninteractive")
}

/**
 * Ubuntu 26.04 LTS (Noble) — fetched from Ubuntu Cloud Images (the same
 * tarballs Canonical ships for cloud instances). Unlike the Debian LXC path
 * these archives are architecture-specific and already contain a working apt
 * with the `universe` repository enabled.
 *
 * Resolution order:
 * 1. Pre-built rootfs hosted on this repo's GitHub Releases (guaranteed
 *    availability, pre-installed base packages + OpenCode) — tried first.
 * 2. Ubuntu Cloud Images release tarball (Canonical CDN, always available).
 * 3. LXC index as a last resort.
 */
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
        // Ubuntu Cloud Images release tarballs — Canonical CDN, always available.
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
        // Ubuntu cloud images ship empty dirs for some apt state paths that
        // the tar extractor skips; recreate them so apt can bootstrap.
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
        // usr-merge repair: same safeguard as Debian — recreate root-level
        // usr-merge symlinks the extractor could not restore and fall back to a
        // real /bin/sh file so proot can always exec a shell.
        repairUsrMergeSymlinks(rootfsDir)
        ensureWorkingShell(rootfsDir)
        // dpkg fsyncs every unpacked file by default — deadly on flash storage.
        File(rootfsDir, "etc/dpkg/dpkg.cfg.d").mkdirs()
        File(rootfsDir, "etc/dpkg/dpkg.cfg.d/force-unsafe-io").writeText("force-unsafe-io\n")
    }

    /**
     * Fixes usr-merge root symlinks that the extractor could not recreate —
     * see the Debian implementation for details.
     */
    private fun repairUsrMergeSymlinks(rootfsDir: File) {
        for ((linkPath, target) in listOf("bin" to "usr/bin", "sbin" to "usr/sbin", "lib" to "usr/lib", "lib64" to "usr/lib64")) {
            val link = File(rootfsDir, linkPath)
            val targetFile = File(rootfsDir, target)
            val broken = link.exists() && (link.canonicalFile != link.absoluteFile || !link.canonicalFile.exists())
            if (!link.exists() || broken) {
                if (targetFile.exists()) {
                    if (link.exists()) link.delete()
                    try {
                        java.nio.file.Files.createSymbolicLink(
                            link.toPath(),
                            java.nio.file.Paths.get(target),
                        )
                    } catch (_: Exception) {
                        // Remaining failure is handled by ensureWorkingShell.
                    }
                }
            }
        }
    }

    /**
     * Guarantees an executable `/bin/sh` exists on the rootfs — see the Debian
     * implementation for details.
     */
    private fun ensureWorkingShell(rootfsDir: File) {
        val sh = File(rootfsDir, "bin/sh")
        val works = sh.exists() && sh.canonicalFile.exists()
        // Embedded rootfs ships `bin/sh.real` — a real busybox-static copy
        // (static-pie, needs no libc/linker symlinks) so proot always has a
        // shell even if every symlink was lost during extraction. Fall back
        // to dash (real ELF) afterwards.
        val fallbacks = listOf("bin/sh.real", "usr/bin/sh.real", "usr/bin/dash")
        if (!works) {
            for (fb in fallbacks) {
                val src = File(rootfsDir, fb)
                if (src.exists()) {
                    sh.parentFile?.mkdirs()
                    if (sh.exists()) sh.delete()
                    runCatching { src.copyTo(sh, overwrite = true) }
                    if (sh.exists()) {
                        sh.setExecutable(true, false)
                        break
                    }
                }
            }
        }
    }

    override val prootArgs = listOf("--link2symlink", "-L")

    override val env = mapOf("DEBIAN_FRONTEND" to "noninteractive")
}
