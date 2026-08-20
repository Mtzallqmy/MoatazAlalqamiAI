package com.inspiredandroid.kai.linux

/**
 * The Linux distributions Kai can run under proot.
 *
 * Kai Build is always [DEBIAN] — its coding agents are vendor scripts that expect
 * glibc and apt. The chat sandbox lets the user pick, and when it is also Debian
 * the two share a single rootfs instead of installing one each.
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
     * Base set is Kai Build's proven list: `tar` because OpenCode's installer
     * extracts a `.tar.gz`, `coreutils` because Claude's checks a `sha256sum`.
     */
    DEBIAN(
        id = "debian",
        displayName = "Debian 13",
        basePackages = listOf(
            "bash", "ca-certificates", "curl", "wget", "git",
            "nano", "less", "unzip", "python3", "tar", "xz-utils", "coreutils",
            "procps", "jq", "ripgrep", "openssh-client", "rsync", "file",
        ),
        optionalPackages = listOf(
            "nodejs",
            "npm",
            "python3-pip",
            "lftp",
        ),
        packageManager = AptPackageManager,
    ),

    /**
     * Ubuntu 26.04 LTS — the default distro for the Agentic Development
     * Platform (v3.4.0+). Uses apt (same family as Debian) but ships with a
     * larger base: `build-essential` and `python3-venv` are included because
     * coding agents expect them, `jq`/`ripgrep` because the agent's tools
     * rely on structured output, and `nodejs`/`npm` so JavaScript projects
     * scaffold without a second package install.
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
            "docker.io",
            "podman",
            "sqlite3",
            "postgresql",
            "nginx",
            "openssh-server",
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
         * What a fresh install becomes unless the user picks otherwise.
         * Ubuntu 26.04 LTS is the default for the Agentic Development Platform
         * (v3.4.0+); Kai Build still uses [DEBIAN] for compatibility.
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
