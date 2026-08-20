package com.inspiredandroid.kai.linux

import android.content.Context
import java.io.File

/** Directory under `filesDir` holding the chat sandbox's Linux. */
const val SANDBOX_DIR_NAME = "linux-sandbox"

/** Directory under `filesDir` holding Kai Build's own Linux, when it needs one. */
const val BUILD_DIR_NAME = "kai-build"

/** Pre-marker Kai Build installs recorded completion in this file. */
const val LEGACY_READY_FILE = "ready"

/**
 * Every Alpine rootfs ships this and no Debian one does, which makes it proof
 * that a marker-less chat sandbox is a finished pre-unification install rather
 * than a Debian that is still being extracted.
 */
const val ALPINE_RELEASE_FILE = "rootfs/etc/alpine-release"

private const val MARKER_FILE = "install"
private const val KEY_DISTRO = "distro"
private const val KEY_HOME = "home"
private const val HOME_ROOTFS = "rootfs"
private const val HOME_EXTERNAL = "external"
private const val PRODUCTION_DEBIAN_MAJOR = "13"

/**
 * What an install recorded about itself. Written only once the install fully
 * succeeds, so a partial rootfs can never present itself as ready.
 */
data class InstallMarker(
    val distro: LinuxDistro,
    val homeOnRootfs: Boolean,
)

/** Storage layout for one Linux install. */
class LinuxPaths(
    context: Context,
    dirName: String,
    private val legacyMarker: InstallMarker,
    private val legacyEvidence: String,
) {
    private val storedAppContext: Context = context.applicationContext
    val appContext: Context get() = storedAppContext

    val root: File = File(appContext.filesDir, dirName)
    val rootfsDir: File get() = File(root, "rootfs")
    val tmpDir: File get() = File(root, "tmp")

    private val markerFile: File get() = File(root, MARKER_FILE)

    fun archiveFile(spec: DistroSpec): File = File(root, spec.archiveName)

    val nativeLibDir: String get() = appContext.applicationInfo.nativeLibraryDir

    /** proot runs straight out of nativeLibraryDir — the one place Android grants exec. */
    val prootPath: String get() = File(nativeLibDir, "libproot.so").absolutePath

    private val tallocTarget: File get() = File(root, "libtalloc.so.2")

    /** proot resolves `libtalloc.so.2` relative to this. */
    val libDir: String get() = root.absolutePath

    private val legacyExternalHome: File by lazy {
        val external = appContext.getExternalFilesDir(null)
        val target = if (external != null) File(external, "sandbox-home") else File(root, "home")
        target.mkdirs()
        target
    }

    val projectsDir: File by lazy {
        val external = appContext.getExternalFilesDir(null) ?: root
        File(external, "kai-build-home/projects")
    }

    fun homeDir(marker: InstallMarker): File = if (marker.homeOnRootfs) File(rootfsDir, "root") else legacyExternalHome

    fun ensureLayout() {
        listOf(root, tmpDir, projectsDir).forEach { it.mkdirs() }
    }

    /** Mount points must exist inside the rootfs before proot binds over them. */
    fun ensureMountPoints() {
        File(rootfsDir, "root/projects").mkdirs()
        File(rootfsDir, "root/.local/bin").mkdirs()
    }

    /**
     * Fail early with a useful diagnostic when the APK is missing one of the
     * native pieces PRoot needs. Previously only libproot.so was checked, so a
     * missing loader or talloc looked like a Linux/rootfs hang later.
     */
    fun validateNativeRuntime() {
        ensureLayout()
        val required = listOf(
            "libproot.so" to true,
            "libproot-loader.so" to false,
            "libtalloc.so" to false,
        )
        val failures = required.mapNotNull { (name, mustExecute) ->
            val file = File(nativeLibDir, name)
            when {
                !file.isFile || file.length() == 0L -> "$name missing"
                mustExecute && !file.canExecute() -> "$name is not executable"
                else -> null
            }
        }
        check(failures.isEmpty()) {
            "Invalid arm64 PRoot runtime in $nativeLibDir: ${failures.joinToString()}"
        }
        copyLibtalloc()
        check(tallocTarget.isFile && tallocTarget.length() > 0L) {
            "Failed to prepare libtalloc.so.2 in ${root.absolutePath}"
        }
    }

    /**
     * Android strips the `.so.2` suffix from jniLibs, so proot cannot find the
     * soname it was linked against until we put a correctly named copy somewhere
     * on its library path.
     */
    fun copyLibtalloc() {
        if (tallocTarget.exists()) return
        val source = File(nativeLibDir, "libtalloc.so")
        if (source.exists()) source.copyTo(tallocTarget, overwrite = true)
    }

    /**
     * Returns a usable installed marker. Existing Debian 12 installs are
     * deliberately treated as not installed by v4.2.0 so setup replaces them
     * with Debian 13 instead of exposing an old rootfs as production-ready.
     * Project directories live outside [root] and are therefore preserved.
     */
    fun readMarker(): InstallMarker? {
        if (!rootfsDir.isDirectory) return null
        parseMarker()?.let { marker ->
            return marker.takeIf { isCompatibleRootfs(it) }
        }
        if (!File(root, legacyEvidence).exists()) return null
        if (!isCompatibleRootfs(legacyMarker)) return null
        writeMarker(legacyMarker)
        return legacyMarker
    }

    private fun isCompatibleRootfs(marker: InstallMarker): Boolean {
        if (marker.distro != LinuxDistro.DEBIAN) return true
        val osRelease = File(rootfsDir, "etc/os-release")
        if (!osRelease.isFile) return false
        val values = runCatching {
            osRelease.readLines()
                .mapNotNull { line ->
                    val idx = line.indexOf('=')
                    if (idx <= 0) null else line.substring(0, idx) to line.substring(idx + 1).trim().trim('"')
                }
                .toMap()
        }.getOrNull() ?: return false
        val id = values["ID"]?.lowercase()
        val major = values["VERSION_ID"]?.substringBefore('.')
        return id == LinuxDistro.DEBIAN.id && major == PRODUCTION_DEBIAN_MAJOR
    }

    fun writeMarker(marker: InstallMarker) {
        root.mkdirs()
        val home = if (marker.homeOnRootfs) HOME_ROOTFS else HOME_EXTERNAL
        markerFile.writeText("$KEY_DISTRO=${marker.distro.id}\n$KEY_HOME=$home\n")
    }

    private fun parseMarker(): InstallMarker? {
        if (!markerFile.isFile) return null
        val values = runCatching {
            markerFile.readLines()
                .mapNotNull { line ->
                    val idx = line.indexOf('=')
                    if (idx <= 0) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
                }
                .toMap()
        }.getOrNull() ?: return null
        val distroId = values[KEY_DISTRO] ?: return null
        return InstallMarker(
            distro = LinuxDistro.fromId(distroId),
            homeOnRootfs = values[KEY_HOME] != HOME_EXTERNAL,
        )
    }

    /** Wipes the install, leaving project folders (they live outside [root]) alone. */
    fun deleteInstall() {
        markerFile.delete()
        File(root, LEGACY_READY_FILE).delete()
        rootfsDir.deleteRecursively()
        tmpDir.deleteRecursively()
        LinuxDistro.entries.forEach { File(root, DistroSpec.of(it).archiveName).delete() }
    }

    companion object {
        fun forSandbox(context: Context) = LinuxPaths(
            context = context,
            dirName = SANDBOX_DIR_NAME,
            legacyMarker = InstallMarker(LinuxDistro.LEGACY, homeOnRootfs = false),
            legacyEvidence = ALPINE_RELEASE_FILE,
        )

        fun forBuild(context: Context) = LinuxPaths(
            context = context,
            dirName = BUILD_DIR_NAME,
            legacyMarker = InstallMarker(LinuxDistro.DEBIAN, homeOnRootfs = true),
            legacyEvidence = LEGACY_READY_FILE,
        )
    }
}
