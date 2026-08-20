package com.inspiredandroid.kai.linux

/**
 * The Linux distributions Kai can run under proot.
 *
 * Kai Build is always [DEBIAN] — its coding agents are vendor scripts that expect
 * glibc and apt. The chat sandbox defaults to the same Debian install so both
 * surfaces share one lightweight rootfs instead of maintaining duplicate Linux
 * environments on a phone.
 */
enum class LinuxDistro(
    val id: String,
    val displayName: String,
    /**
     * Installed as the last step of setup. The environment is not considered
     * ready until these are in, so an interrupted install can never present
     * itself as usable.
     */
    val basePackages: List<String>,
    /** Installed on demand from the Settings card's "Install Packages" action. */
    val optionalPackages: List<String>,
    val packageManager: PackageManagerSpec,
) {
    /**
     * Debian 13 is the production on-device environment. The base intentionally
     * stays CLI-focused: enough for agents, git/network/file tooling, process
     * control and the PTY bridge, without pulling a desktop or build toolchain.
     * Larger language/toolchain packages remain optional.
     */
    DEBIAN(
        id = "debian",
        displayName = "Debian 13 (Trixie)",
        basePackages = listOf(
            "bash", "bash-completion", "ca-certificates", "curl", "wget", "git",
            "nano", "less", "jq", "ripgrep", "zip", "unzip", "tar", "xz-utils",
            "python3", "coreutils", "findutils", "sed", "grep", "procps", "psmisc",
            "openssh-client", "rsync", "file",
        ),
        optionalPackages = listOf(
            "nodejs",
            "npm",
            "python3-pip",
            "lftp",
            "tmux",
            "tree",
            "fzf",
            "build-essential",
            "cmake",
            "pkg-config",
            "default-jdk",
            "golang-go",
            "rustc",
            "cargo",
        ),
        packageManager = AptPackageManager,
    ),

    /**
     * Ubuntu remains available for compatibility, but fresh local sandboxes use
     * Debian so Kai Build and the terminal can share a single rootfs.
     */
    UBUNTU(
        id = "ubuntu",
        displayName = "Ubuntu 26.04 LTS",
        basePackages = listOf(
            "bash", "ca-certificates", "curl", "wget", "git",
            "openssh-client", "nano", "less", "jq", "ripgrep",
            "zip", "unzip", "tar", "xz-utils", "python3", "python3-pip",
            "python3-venv", "nodejs", "npm", "build-essential", "make",
            "cmake", "pkg-config", "coreutils", "findutils", "sed",
            "grep", "procps", "rsync", "file",
        ),
        optionalPackages = listOf(
            "golang-go",
            "ruby",
            "rustc",
            "cargo",
            "default-jre",
            "default-jdk",
            "sqlite3",
            "postgresql",
            "lftp",
        ),
        packageManager = AptPackageManager,
    ),

    /**
     * `bash` is infrastructure rather than convenience: every persistent shell
     * session execs it directly, so the minirootfs is unusable without it.
     */
    ALPINE(
        id = "alpine",
        displayName = "Alpine Linux",
        basePackages = listOf("bash"),
        optionalPackages = listOf(
            "curl", "wget", "git", "jq", "python3", "py3-pip", "nodejs",
            "openssh-client", "lftp", "rsync",
        ),
        packageManager = ApkPackageManager,
    ),
    ;

    /**
     * Packages the Packages tab must never offer to uninstall — removing any of
     * them breaks the shell sessions the sandbox itself runs on.
     */
    val protectedPackages: Set<String> = basePackages.toSet()

    companion object {
        /**
         * Debian 13 is the production default. It is also Kai Build's distro, so
         * a fresh phone keeps one rootfs, one package database and one CLI home.
         */
        val DEFAULT = DEBIAN

        /**
         * Installs made before the distro was recorded are Alpine — that was the
         * only thing the chat sandbox could be.
         */
        val LEGACY = ALPINE

        fun fromId(id: String?): LinuxDistro = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
