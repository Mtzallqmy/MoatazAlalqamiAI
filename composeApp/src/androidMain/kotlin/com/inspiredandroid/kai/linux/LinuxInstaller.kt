package com.inspiredandroid.kai.linux

import android.content.Context
import android.os.StatFs
import com.inspiredandroid.kai.runtime.RootfsManifest
import com.inspiredandroid.kai.runtime.RuntimeReadinessGate
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Where an install has got to, in terms both feature UIs can render. */
sealed interface InstallStep {
    data class Download(val fraction: Float) : InstallStep
    data object Extract : InstallStep
    data object Configure : InstallStep

    /** One name for apk (which installs serially), the whole set for apt. */
    data class Packages(val packages: List<String>) : InstallStep
}

private const val UPDATE_TIMEOUT_SECONDS = 300L
private const val PACKAGE_TIMEOUT_SECONDS = 900L
private const val MIN_RUNTIME_FREE_BYTES = 1024L * 1024 * 1024
private const val RUNTIME_STORAGE_MULTIPLIER = 3L

/**
 * Name of the pre-built Debian rootfs shipped inside the APK assets.
 * The image supplies Debian itself; required developer packages are probed and
 * installed explicitly before readiness.
 */
private const val EMBEDDED_ROOTFS_ASSET = "moataz-debian-rootfs-arm64.tar.xz"
private const val EMBEDDED_ROOTFS_MANIFEST = "moataz-debian-rootfs-arm64.manifest.json"

/**
 * Downloads, extracts and bootstraps a rootfs. The chat sandbox and Kai Build
 * both drive this; whoever gets there first produces the install the other one
 * then finds already present.
 *
 * When the device is arm64 and the APK bundles the matching verified Debian
 * rootfs, both the rootfs download and base-package network setup are skipped.
 */
class LinuxInstaller(
    private val paths: LinuxPaths,
    private val appContext: Context,
) {

    private val downloader = RootfsDownloader(HttpClient(OkHttp))

    /**
     * Installs [distro] into [paths] and returns the marker it wrote. Cancellable
     * between steps and during the download; a failure or cancellation removes
     * the partial rootfs so the next attempt starts clean.
     */
    suspend fun install(distro: LinuxDistro, onStep: (InstallStep) -> Unit): InstallMarker {
        val spec = DistroSpec.of(distro)
        paths.ensureLayout()
        val proot = File(paths.prootPath)
        check(proot.exists()) {
            "proot binary not found at ${paths.prootPath}. nativeLibraryDir contents: " +
                (File(paths.nativeLibDir).listFiles()?.map { it.name } ?: "empty")
        }
        // Verify the packaged image metadata and available storage before
        // touching an existing installation. A broken APK or full device must
        // never turn an otherwise working runtime into a failed reinstall.
        val embeddedManifest = loadEmbeddedManifest(spec)
        requireSufficientStorage(embeddedManifest)
        paths.copyLibtalloc()

        // Wipe any partial/previous install so a retry after a failed package
        // index update (or a distro change) always re-extracts cleanly — and so
        // nothing reading the marker mid-install sees the outgoing install's.
        paths.deleteInstall()

        // deleteInstall() nukes the host tmp dir proot binds over /tmp. Recreate
        // it (and root/projects) before anything else — otherwise the first proot
        // run fails with "can't canonicalize .../tmp: No such file or directory".
        paths.ensureLayout()

        val archive = paths.archiveFile(spec)
        val stagingRootfs = File(paths.root, "rootfs.staging")
        stagingRootfs.deleteRecursively()
        var activated = false
        try {
            // Prefer the embedded, manifest-verified Debian image. Package and
            // CLI probes below remain authoritative; asset presence is not Ready.
            val hasEmbeddedAsset = copyEmbeddedAsset(embeddedManifest, archive)
            if (!hasEmbeddedAsset) {
                onStep(InstallStep.Download(0f))
                if (distro == LinuxDistro.DEBIAN) {
                    downloader.downloadVerified(spec.rootfsUrls(), archive) { onStep(InstallStep.Download(it)) }
                } else {
                    downloader.download(spec.rootfsUrls(), archive) { onStep(InstallStep.Download(it)) }
                }
            }

            currentCoroutineContext().ensureActive()
            onStep(InstallStep.Extract)
            val installContext = currentCoroutineContext()
            TarExtractor.extractSafe(archive, stagingRootfs) { installContext.ensureActive() }

            currentCoroutineContext().ensureActive()
            onStep(InstallStep.Configure)
            spec.configure(stagingRootfs)
            check(stagingRootfs.renameTo(paths.rootfsDir)) { "Could not atomically activate extracted rootfs" }
            activated = true
            paths.ensureMountPoints()
        } catch (e: Throwable) {
            stagingRootfs.deleteRecursively()
            if (activated) paths.rootfsDir.deleteRecursively()
            throw e
        } finally {
            archive.delete()
        }

        val launcher = launcherFor(spec)
        try {
            refreshPackageIndex(spec, launcher)
            currentCoroutineContext().ensureActive()
            installBasePackages(distro, launcher, onStep)
        } catch (e: Throwable) {
            // A rootfs without its base packages would skip the download on the
            // next attempt and keep failing the same way.
            paths.rootfsDir.deleteRecursively()
            throw e
        }

        val health = EnvironmentDoctor(paths).diagnose()
        val marker = InstallMarker(distro, homeOnRootfs = true)
        return RuntimeReadinessGate.commit(health, marker, paths::writeMarker)
    }

    /** Missing images may download; present-but-invalid images always fail closed. */
    private fun loadEmbeddedManifest(spec: DistroSpec): RootfsManifest? {
        if (spec.distro != LinuxDistro.DEBIAN) return null
        val assetList = appContext.assets.list("") ?: emptyArray()
        if (EMBEDDED_ROOTFS_MANIFEST !in assetList) return null
        val manifest = try {
            appContext.assets.open(EMBEDDED_ROOTFS_MANIFEST).bufferedReader().use {
                Json { ignoreUnknownKeys = false }.decodeFromString<RootfsManifest>(it.readText())
            }
        } catch (e: Exception) {
            throw IllegalStateException("Moataz Runtime integrity error: embedded rootfs manifest is unreadable or corrupt", e)
        }
        check(manifest.isProductionRuntime()) {
            "Moataz Runtime integrity error: embedded rootfs is not Debian 13 Trixie arm64 " +
                "(${manifest.distro} ${manifest.version} ${manifest.architecture})"
        }
        check(manifest.architecture.equals(spec.arch(), ignoreCase = true)) {
            "Moataz Runtime integrity error: embedded architecture ${manifest.architecture} " +
                "does not match device architecture ${spec.arch()}"
        }
        val imageDigest = MessageDigest.getInstance("SHA-256")
        manifest.assetParts.forEach { part ->
            check(part.name in assetList) {
                "Moataz Runtime integrity error: embedded rootfs part is missing: ${part.name}"
            }
            val partDigest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            appContext.assets.open(part.name).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val bytesRead = input.read(buffer)
                    if (bytesRead < 0) break
                    partDigest.update(buffer, 0, bytesRead)
                    imageDigest.update(buffer, 0, bytesRead)
                    size += bytesRead
                }
            }
            check(size == part.sizeBytes) {
                "Moataz Runtime integrity error: embedded rootfs part size mismatch: ${part.name}"
            }
            val actualPartSha = partDigest.digest().joinToString("") { "%02x".format(it) }
            check(actualPartSha == part.sha256) {
                "Moataz Runtime integrity error: embedded rootfs part SHA-256 mismatch: ${part.name}"
            }
        }
        val actualImageSha = imageDigest.digest().joinToString("") { "%02x".format(it) }
        check(actualImageSha == manifest.sha256) {
            "Moataz Runtime integrity error: embedded rootfs SHA-256 mismatch"
        }
        return manifest
    }

    private fun requireSufficientStorage(manifest: RootfsManifest?) {
        val compressedBytes = manifest?.assetParts?.fold(0L) { total, part ->
            check(total <= Long.MAX_VALUE - part.sizeBytes) {
                "Moataz Runtime integrity error: embedded rootfs part sizes overflow"
            }
            total + part.sizeBytes
        } ?: 0L
        val expandedAllowance = if (compressedBytes > Long.MAX_VALUE / RUNTIME_STORAGE_MULTIPLIER) {
            Long.MAX_VALUE
        } else {
            compressedBytes * RUNTIME_STORAGE_MULTIPLIER
        }
        val requiredBytes = maxOf(MIN_RUNTIME_FREE_BYTES, expandedAllowance)
        val availableBytes = StatFs(paths.root.absolutePath).availableBytes
        check(availableBytes >= requiredBytes) {
            val bytesPerMiB = 1024L * 1024
            val availableMiB = availableBytes / bytesPerMiB
            val requiredMiB = requiredBytes / bytesPerMiB + if (requiredBytes % bytesPerMiB == 0L) 0 else 1
            "Insufficient storage for Moataz Runtime: $availableMiB MiB available, " +
                "$requiredMiB MiB required. Free up storage and try again; " +
                "existing runtime and projects were not removed."
        }
    }

    /** Copies a prevalidated embedded image, or returns false when no image exists. */
    private fun copyEmbeddedAsset(manifest: RootfsManifest?, target: File): Boolean {
        if (manifest == null) return false
        val assetList = appContext.assets.list("") ?: emptyArray()
        target.parentFile?.mkdirs()
        FileOutputStream(target).use { output ->
            if (manifest.assetParts.isEmpty()) {
                check(EMBEDDED_ROOTFS_ASSET in assetList) {
                    "Moataz Runtime integrity error: embedded rootfs archive is missing"
                }
                appContext.assets.open(EMBEDDED_ROOTFS_ASSET).use { input ->
                    input.copyTo(output, 64 * 1024)
                }
            } else {
                manifest.assetParts.forEach { part ->
                    check(part.name in assetList) {
                        "Moataz Runtime integrity error: embedded rootfs part is missing: ${part.name}"
                    }
                    val digest = MessageDigest.getInstance("SHA-256")
                    var size = 0L
                    appContext.assets.open(part.name).use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            digest.update(buffer, 0, bytesRead)
                            size += bytesRead
                        }
                    }
                    val partSha = digest.digest().joinToString("") { "%02x".format(it) }
                    check(size == part.sizeBytes) {
                        "Moataz Runtime integrity error: embedded rootfs part size mismatch: ${part.name}"
                    }
                    check(partSha == part.sha256) {
                        "Moataz Runtime integrity error: embedded rootfs part SHA-256 mismatch: ${part.name}"
                    }
                }
            }
        }
        check(target.sha256() == manifest.sha256) {
            "Moataz Runtime integrity error: embedded rootfs SHA-256 mismatch"
        }
        return true
    }

    /**
     * A proot for install-time work only. A fresh install always keeps `/root` on
     * the rootfs, so there is nothing to bind over it, and no projects yet.
     */
    private fun launcherFor(spec: DistroSpec) = ProotLauncher(
        prootPath = paths.prootPath,
        libDir = paths.libDir,
        rootfsPath = paths.rootfsDir.absolutePath,
        tmpPath = paths.tmpDir.absolutePath,
        binds = emptyList(),
        extraArgs = spec.prootArgs,
        env = spec.env,
    )

    /**
     * Refresh the package index. For Debian, if the base packages are already
     * installed in the rootfs (e.g. from a pre-built image), skip apt update
     * entirely — it needs internet and would fail when the network is unavailable.
     * Alpine still walks its mirrors since it can't pre-check.
     */
    private suspend fun refreshPackageIndex(spec: DistroSpec, launcher: ProotLauncher) {
        // If this is Debian and all base packages are already installed (dpkg
        // says "install ok installed"), skip apt update — no internet needed.
        if (spec.distro == LinuxDistro.DEBIAN && basePackagesAlreadyInstalled(launcher, spec.distro)) {
            return
        }
        val updateCommand = spec.distro.packageManager.updateCommand
        if (spec !is AlpineSpec) {
            val result = launcher.execute(updateCommand, timeoutSeconds = UPDATE_TIMEOUT_SECONDS)
            check(result.success) { "`$updateCommand` failed: ${result.failureDetail()}" }
            return
        }
        var lastDetail = ""
        for (mirror in spec.mirrors) {
            currentCoroutineContext().ensureActive()
            spec.writeRepositories(paths.rootfsDir, mirror)
            val result = launcher.execute(updateCommand, timeoutSeconds = 60)
            if (result.success) return
            lastDetail = result.failureDetail()
        }
        val suffix = if (lastDetail.isNotEmpty()) ": $lastDetail" else ""
        error("`$updateCommand` failed on all Alpine mirrors$suffix")
    }

    /**
     * Checks dpkg status inside the rootfs: if every base package is already
     * "install ok installed", the rootfs was pre-bootstrapped and apt update
     * + apt install can be skipped.
     */
    private fun basePackagesAlreadyInstalled(launcher: ProotLauncher, distro: LinuxDistro): Boolean {
        val dpkgStatus = File(paths.rootfsDir, "var/lib/dpkg/status")
        if (!dpkgStatus.exists()) return false
        return try {
            val status = dpkgStatus.readText()
            distro.basePackages.all { pkg ->
                status.contains("Package: $pkg") &&
                    status.substringAfter("Package: $pkg")
                        .substringBefore("\n\n")
                        .contains("install ok installed")
            }
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun installBasePackages(
        distro: LinuxDistro,
        launcher: ProotLauncher,
        onStep: (InstallStep) -> Unit,
    ) {
        // If all base packages are already installed (pre-bootstrapped rootfs),
        // skip apt install entirely — nothing more to do.
        if (distro == LinuxDistro.DEBIAN && basePackagesAlreadyInstalled(launcher, distro)) {
            onStep(InstallStep.Packages(distro.basePackages))
            return
        }
        val manager = distro.packageManager
        if (distro == LinuxDistro.ALPINE) {
            // apk resolves one package per call, which also gives per-package progress.
            for (pkg in distro.basePackages) {
                currentCoroutineContext().ensureActive()
                onStep(InstallStep.Packages(listOf(pkg)))
                val result = launcher.execute(
                    manager.installCommand(pkg),
                    timeoutSeconds = PACKAGE_TIMEOUT_SECONDS,
                )
                check(result.success) { "Failed to install $pkg: ${result.failureDetail(200)}" }
            }
            return
        }
        // apt resolves the whole set at once, which is both faster and the only
        // way its dependency solver sees the full picture.
        onStep(InstallStep.Packages(distro.basePackages))
        val result = launcher.execute(
            manager.installCommand(distro.basePackages),
            timeoutSeconds = PACKAGE_TIMEOUT_SECONDS,
        )
        check(result.success) { "Failed to install base packages: ${result.failureDetail()}" }
    }

    companion object {
        /**
         * Serializes package work across features. A shared rootfs means the chat
         * sandbox's "Install Packages" and a Kai Build agent install can otherwise
         * hit the dpkg lock at the same time and both fail.
         */
        val packageLock = Mutex()
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
