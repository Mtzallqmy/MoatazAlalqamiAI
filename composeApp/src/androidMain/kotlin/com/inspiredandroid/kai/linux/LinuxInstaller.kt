package com.inspiredandroid.kai.linux

import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.io.FileOutputStream

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

/**
 * Name of the pre-built Debian rootfs shipped inside the APK assets.
 * Bundled with base packages + OpenCode so the install works fully offline.
 */
private const val EMBEDDED_ROOTFS_ASSET = "moataz-debian-rootfs-arm64.tar.xz"

/**
 * Downloads, extracts and bootstraps a rootfs. The chat sandbox and Kai Build
 * both drive this; whoever gets there first produces the install the other one
 * then finds already present.
 *
 * When the device is arm64 and the APK bundles a pre-built rootfs, the network
 * download is skipped entirely — the embedded image is extracted in its place.
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
        try {
            // Prefer the embedded rootfs shipped inside the APK. This makes the
            // install work fully offline and immune to mirror flakiness — the
            // bundled image already carries the base packages and OpenCode, so
            // the subsequent apt/proot steps become near-instant no-ops.
            val hasEmbeddedAsset = copyEmbeddedAsset(spec, archive)
            if (!hasEmbeddedAsset) {
                onStep(InstallStep.Download(0f))
                downloader.download(spec.rootfsUrls(), archive) { onStep(InstallStep.Download(it)) }
            }

            currentCoroutineContext().ensureActive()
            onStep(InstallStep.Extract)
            TarExtractor.extract(archive, paths.rootfsDir)
        } finally {
            archive.delete()
        }

        currentCoroutineContext().ensureActive()
        onStep(InstallStep.Configure)
        spec.configure(paths.rootfsDir)
        paths.ensureMountPoints()

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

        val marker = InstallMarker(distro, homeOnRootfs = true)
        paths.writeMarker(marker)
        return marker
    }

    /**
     * Copies the pre-built rootfs bundled inside the APK assets to [target].
     *
     * Returns true when the asset was found and copied, false when it is not
     * available for this architecture (e.g. a device whose ABI is not arm64)
     * and the network download path should be used instead.
     */
    private fun copyEmbeddedAsset(spec: DistroSpec, target: File): Boolean {
        if (!spec.arch().equals("arm64", ignoreCase = true)) return false
        val assetList = appContext.assets.list("") ?: emptyArray()
        if (EMBEDDED_ROOTFS_ASSET !in assetList) return false
        target.parentFile?.mkdirs()
        appContext.assets.open(EMBEDDED_ROOTFS_ASSET).use { input ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(64 * 1024)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                }
            }
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
     * Alpine's mirrors go down independently of the one that served the rootfs,
     * so `apk update` walks the list rewriting `repositories` until one answers.
     * Debian has a single index to refresh.
     */
    private suspend fun refreshPackageIndex(spec: DistroSpec, launcher: ProotLauncher) {
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

    private suspend fun installBasePackages(
        distro: LinuxDistro,
        launcher: ProotLauncher,
        onStep: (InstallStep) -> Unit,
    ) {
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
            manager.installCommand(distro.basePackages.joinToString(" ")),
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
