package com.inspiredandroid.kai.cli

sealed interface InstallStrategy {
    data class Apt(val packages: List<String>) : InstallStrategy
    data class Script(val url: String, val sha256: String? = null) : InstallStrategy
    data class Npm(val packageName: String) : InstallStrategy
    data class Pipx(val packageName: String) : InstallStrategy
    data class Cargo(val crate: String) : InstallStrategy
    data class BinaryArchive(val url: String, val sha256: String) : InstallStrategy
    data object Manual : InstallStrategy
}

enum class CliCategory { Shell, Agent, Language, VersionControl, Utility, Custom }

data class CliDefinition(
    val id: String,
    val displayName: String,
    val executable: String,
    val aliases: List<String> = emptyList(),
    val install: InstallStrategy,
    val detectCommand: String = "command -v $executable",
    val versionCommand: String = "$executable --version",
    val launchCommand: String? = executable,
    val requiresPty: Boolean = true,
    val env: Map<String, String> = emptyMap(),
    val pathEntries: List<String> = emptyList(),
    val homepage: String? = null,
    val category: CliCategory,
) {
    init {
        require(id.matches(Regex("[a-z0-9][a-z0-9._-]*")))
        require(executable.matches(Regex("[A-Za-z0-9._+-]+")))
    }
}

sealed interface CliStatus {
    data object NotInstalled : CliStatus
    data object Installing : CliStatus
    data class Ready(val version: String) : CliStatus
    data class Broken(val reason: String) : CliStatus
    data class UpdateAvailable(val current: String, val available: String) : CliStatus
    data class Unsupported(val reason: String) : CliStatus
}

data class CliCommandResult(val exitCode: Int, val stdout: String = "", val stderr: String = "")

fun interface CliCommandRunner {
    fun execute(command: String): CliCommandResult
}

class CliRegistry(definitions: Iterable<CliDefinition> = defaultCliDefinitions()) {
    private val definitions = LinkedHashMap<String, CliDefinition>()

    init { definitions.forEach(::register) }

    fun register(definition: CliDefinition) {
        require(this.definitions.putIfAbsent(definition.id, definition) == null) {
            "Duplicate CLI id: ${definition.id}"
        }
    }

    fun get(id: String): CliDefinition? = definitions[id]
    fun all(): List<CliDefinition> = definitions.values.toList()
}

class CliDetector(private val runner: CliCommandRunner) {
    fun detect(definition: CliDefinition): CliStatus {
        val detected = runner.execute(definition.detectCommand)
        if (detected.exitCode != 0 || detected.stdout.isBlank()) return CliStatus.NotInstalled
        val version = runner.execute(definition.versionCommand)
        if (version.exitCode != 0) {
            return CliStatus.Broken(version.stderr.ifBlank { "Version probe failed with ${version.exitCode}" }.trim())
        }
        val text = version.stdout.ifBlank { version.stderr }.lineSequence().firstOrNull()?.trim().orEmpty()
        return if (text.isEmpty()) CliStatus.Broken("Version probe returned no version") else CliStatus.Ready(text)
    }
}

class CliInstaller(
    private val runner: CliCommandRunner,
    private val detector: CliDetector = CliDetector(runner),
    private val allowedScriptHosts: Set<String> = emptySet(),
) {
    fun install(definition: CliDefinition): CliStatus {
        val command = when (val strategy = definition.install) {
            is InstallStrategy.Apt -> "apt-get install -y -- ${strategy.packages.joinToString(" ")}"
            is InstallStrategy.Npm -> "npm install -g -- ${strategy.packageName}"
            is InstallStrategy.Pipx -> "pipx install -- ${strategy.packageName}"
            is InstallStrategy.Cargo -> "cargo install --locked -- ${strategy.crate}"
            is InstallStrategy.BinaryArchive -> verifiedDownloadCommand(strategy.url, strategy.sha256)
            is InstallStrategy.Script -> {
                val host = httpsHost(strategy.url) ?: return CliStatus.Unsupported("Installer must use HTTPS")
                if (host !in allowedScriptHosts) return CliStatus.Unsupported("Installer host is not allowed: $host")
                val download = verifiedDownloadCommand(strategy.url, strategy.sha256)
                "$download && bash /tmp/moataz-cli-installer"
            }
            InstallStrategy.Manual -> return CliStatus.Unsupported("Manual installation required")
        }
        val installed = runner.execute(command)
        if (installed.exitCode != 0) {
            return CliStatus.Broken(installed.stderr.ifBlank { "Installer failed with ${installed.exitCode}" }.trim())
        }
        return when (val detected = detector.detect(definition)) {
            CliStatus.NotInstalled -> CliStatus.Broken("Installer exited successfully but ${definition.executable} is missing")
            else -> detected
        }
    }

    private fun verifiedDownloadCommand(url: String, sha256: String?): String {
        val base = "curl --fail --silent --show-error --location --proto '=https' --max-time 120 '$url' -o /tmp/moataz-cli-installer"
        return if (sha256 == null) base else "$base && printf '%s  %s\\n' '$sha256' /tmp/moataz-cli-installer | sha256sum -c -"
    }

    private fun httpsHost(url: String): String? {
        if (!url.startsWith("https://")) return null
        return url.removePrefix("https://").substringBefore('/').substringBefore(':').takeIf { it.isNotBlank() }
    }
}

fun defaultCliDefinitions(): List<CliDefinition> = listOf(
    CliDefinition("bash", "Shell", "bash", install = InstallStrategy.Apt(listOf("bash")), category = CliCategory.Shell),
    CliDefinition("opencode", "OpenCode", "opencode", install = InstallStrategy.Script("https://opencode.ai/install"), category = CliCategory.Agent, homepage = "https://opencode.ai"),
    CliDefinition("claude-code", "Claude Code", "claude", install = InstallStrategy.Script("https://claude.ai/install.sh"), category = CliCategory.Agent, homepage = "https://claude.ai/code"),
    CliDefinition("grok", "Grok", "grok", install = InstallStrategy.Script("https://x.ai/cli/install.sh"), category = CliCategory.Agent, homepage = "https://x.ai"),
    CliDefinition("python", "Python", "python3", install = InstallStrategy.Apt(listOf("python3")), category = CliCategory.Language),
    CliDefinition("node", "Node", "node", install = InstallStrategy.Apt(listOf("nodejs")), category = CliCategory.Language),
    CliDefinition("git", "Git", "git", install = InstallStrategy.Apt(listOf("git")), category = CliCategory.VersionControl),
)
