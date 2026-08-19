/*
 * Moataz Alalqami AI — Policy Engine
 *
 * Content-aware risk assessment for shell commands handed to the sandbox.
 * Works on the parsed argv representation rather than raw text so equivalent
 * obfuscations (e.g. "bash <(curl ...)" vs "bash < ( curl ... )") are caught
 * by the same structural rules. The agent orchestrator is expected to route
 * DENY decisions to a human approval gate before execution.
 */
package com.inspiredandroid.kai.policy

/** Content-aware verdict for a single command line. */
enum class CommandVerdict {
    /** Command is safe to execute directly. */
    ALLOW,
    /** Command is destructive or network-fetching — require explicit approval. */
    ASK,
    /** Command is prohibited in all contexts. */
    DENY,
}

/** Why a command got its verdict, for UI display and audit trails. */
data class PolicyDecision(
    val verdict: CommandVerdict,
    val reasons: List<String>,
) {
    companion object {
        val ALLOW = PolicyDecision(CommandVerdict.ALLOW, emptyList())
    }
}

/**
 * Content-aware analysis of a shell command given as structured argv.
 * `argv`[0] is the program, the rest are arguments.
 */
object PolicyEngine {

    /** Commands that are prohibited in all contexts regardless of arguments. */
    private val DENY_PROGRAMS = setOf(
        "mkfs", "fdisk", "dd", "mount", "umount", "parted",
        "chroot", "pivot_root", "swapon", "swapoff",
        "iptables", "nft", "ip6tables",
    )

    /** Programs whose content-fetch behavior always raises the bar to ASK. */
    private val FETCH_PROGRAMS = setOf("curl", "wget", "fetch", "lynx", "aria2c")

    /** Pipe targets that re-introduce remote code execution. */
    private val EXEC_SINKS = setOf("sh", "bash", "zsh", "dash", "ksh", "python3", "python", "perl", "ruby", "node", "php", "lua")

    /** Words that make a destructive argument pattern match. */
    private val DESTRUCTIVE_FLAGS = setOf("-rf", "-fr", "-r", "-f", "--force", "--no-preserve-root", "--recursive")

    /** Destructive programs that are only ASK (not DENY) so a human can still approve. */
    private val DESTRUCTIVE_PROGRAMS = setOf(
        "rm", "shred", "wipe", "srm", "del", "format",
        "kill", "killall", "pkill", "reboot", "shutdown", "halt", "poweroff", "init", "telinit",
        "chmod", "chown", "chgrp", "passwd", "usermod", "userdel", "useradd", "groupdel",
        "systemctl", "service", "iptables-restore",
    )

    /** Dangerous path targets even when the program itself is benign. */
    private val DANGEROUS_PATHS = setOf("/", "/bin", "/boot", "/dev", "/etc", "/lib", "/lib64", "/proc", "/root", "/sbin", "/sys", "/usr")

    /**
     * Analyzes a command given as structured argv. This is the preferred entry point
     * because it is immune to text-level obfuscation (extra whitespace, quoting tricks).
     */
    fun analyzeCommand(argv: List<String>): PolicyDecision {
        if (argv.isEmpty()) return PolicyDecision(CommandVerdict.DENY, listOf("empty command"))
        val program = argv[0].substringAfterLast('/').lowercase()

        // --- Hard deny: programs that alter disk/table/network stack at the OS level.
        if (program in DENY_PROGRAMS) {
            return PolicyDecision(CommandVerdict.DENY, listOf("prohibited program: $program"))
        }

        val reasons = mutableListOf<String>()
        val verdicts = mutableListOf(CommandVerdict.ALLOW)

        // --- Root filesystem targeting.
        val pathsTouched = argv.drop(1)
        if (program in DESTRUCTIVE_PROGRAMS && pathsTouched.any { it in DANGEROUS_PATHS }) {
            return PolicyDecision(CommandVerdict.DENY, listOf("destructive program targeting system path"))
        }

        // --- Destructive flag + wildcard combos (rm -rf /, rm -r *).
        if (program == "rm") {
            val hasDestructiveFlag = argv.drop(1).any { it.lowercase() in DESTRUCTIVE_FLAGS }
            val hasWildcard = argv.drop(1).any { "*" in it || it in DANGEROUS_PATHS }
            if (hasDestructiveFlag && (hasWildcard || argv.drop(1).any { it in DANGEROUS_PATHS })) {
                return PolicyDecision(CommandVerdict.DENY, listOf("rm with destructive flags on wildcard/system path"))
            }
            if (hasDestructiveFlag) verdicts += CommandVerdict.ASK.also { reasons += "rm with destructive flags" }
        }

        // --- Force push / force operations on VCS and package managers.
        if (program in setOf("git") && argv.contains("--force") || argv.any { it == "push" && argv.contains("--force") }) {
            verdicts += CommandVerdict.ASK.also { reasons += "git --force operation" }
        }
        if (program in setOf("npm", "pnpm", "yarn", "pip", "pip3", "gem") && argv.contains("--force")) {
            verdicts += CommandVerdict.ASK.also { reasons += "package manager --force install" }
        }

        // --- Remote code execution sinks: curl|sh, wget|bash, process substitution.
        val text = argv.joinToString(" ")
        if (program in FETCH_PROGRAMS) {
            if (text.contains('|') || text.contains(';') || text.contains("&&") || text.contains("`") || text.contains("\$(")) {
                return PolicyDecision(CommandVerdict.DENY, listOf("network fetch piped to shell sink"))
            }
            verdicts += CommandVerdict.ASK.also { reasons += "network fetch command" }
        } else if ((program in EXEC_SINKS && text.contains('|')) || (program in EXEC_SINKS && text.contains('$'))) {
            return PolicyDecision(CommandVerdict.DENY, listOf("shell sink fed by pipe or substitution"))
        }

        // --- Sudo / privilege escalation.
        if (program == "sudo" || program == "su" || argv.contains("sudo")) {
            verdicts += CommandVerdict.ASK.also { reasons += "privilege escalation command" }
        }

        // --- Exporting secrets (any program writing to env-like files or printing secrets).
        if (text.contains("AWS_SECRET") || text.contains("PRIVATE_KEY") || text.contains("token") && text.contains("=") && text.contains("export")) {
            verdicts += CommandVerdict.ASK.also { reasons += "potential secret handling" }
        }

        // --- Process termination of everything.
        if ((program == "killall" || program == "pkill") && argv.any { it == "-9" || it == "-SIGKILL" }) {
            verdicts += CommandVerdict.ASK.also { reasons += "forceful process termination" }
        }

        val verdict = verdicts.maxByOrNull { it.ordinal } ?: CommandVerdict.ALLOW
        return PolicyDecision(verdict, reasons)
    }

    /** Convenience overload for a single shell command line (legacy callers). */
    fun analyzeCommandString(command: String): PolicyDecision {
        val argv = tokenize(command)
        if (argv.isEmpty()) return PolicyDecision(CommandVerdict.DENY, listOf("empty command"))
        return analyzeCommand(argv)
    }

    /**
     * Naive whitespace tokenizer that respects double quotes — sufficient for
     * structural policy matching; the orchestrator should prefer pre-parsed argv.
     */
    internal fun tokenize(command: String): List<String> {
        val tokens = mutableListOf<String>()
        var current = StringBuilder()
        var inQuote = false
        for (ch in command) {
            when {
                ch == '"' -> inQuote = !inQuote
                ch.isWhitespace() && !inQuote -> {
                    if (current.isNotEmpty()) { tokens += current.toString(); current = StringBuilder() }
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) tokens += current.toString()
        return tokens
    }
}
