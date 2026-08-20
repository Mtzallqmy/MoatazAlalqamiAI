package com.inspiredandroid.kai.linux

import com.inspiredandroid.kai.runtime.EnvironmentHealth
import com.inspiredandroid.kai.runtime.EnvironmentIssue
import com.inspiredandroid.kai.runtime.MoatazRuntimeContract
import com.inspiredandroid.kai.runtime.OsRelease
import com.inspiredandroid.kai.runtime.validateRuntimeIdentity
import com.inspiredandroid.kai.runtime.RuntimeDiagnosticEvent
import com.inspiredandroid.kai.runtime.RuntimeDiagnosticsSink
import java.io.File

/** Authoritative runtime probe. A marker is never accepted as a substitute for this. */
class EnvironmentDoctor(
    private val paths: LinuxPaths,
    private val diagnostics: RuntimeDiagnosticsSink = RuntimeDiagnosticsSink.None,
) {

    fun diagnose(): EnvironmentHealth {
        val issues = mutableListOf<EnvironmentIssue>()
        paths.copyLibtalloc()
        paths.ensureLayout()
        paths.ensureMountPoints()

        listOf("libproot.so", "libproot-loader.so", "libtalloc.so")
            .map { File(paths.nativeLibDir, it) }
            .filterNot { it.isFile }
            .forEach { issues += EnvironmentIssue.MissingNative("Missing ${it.name}") }
        if (issues.any { it is EnvironmentIssue.MissingNative }) return EnvironmentHealth(issues)

        val sh = File(paths.rootfsDir, "bin/sh")
        val bash = File(paths.rootfsDir, "bin/bash")
        if (!sh.exists() || !sh.canExecute()) issues += EnvironmentIssue.BrokenShell("/bin/sh is missing or not executable")
        if (!bash.exists() || !bash.canExecute()) issues += EnvironmentIssue.BrokenShell("/bin/bash is missing or not executable")

        val osRelease = readOsRelease()
        if (osRelease == null) issues += EnvironmentIssue.WrongDistro("/etc/os-release is missing or unreadable")
        if (issues.any { it is EnvironmentIssue.BrokenShell } || osRelease == null) return EnvironmentHealth(issues)

        val launcher = launcher()
        val architecture = launcher.probe("architecture", "dpkg --print-architecture", timeoutSeconds = 20)
        if (!architecture.success) {
            issues += EnvironmentIssue.BootProbeFailed(architecture.failureDetail())
            return EnvironmentHealth(issues)
        }
        issues += validateRuntimeIdentity(osRelease, architecture.stdout)
        if (issues.any { !it.repairable }) return EnvironmentHealth(issues)

        val missingCli = launcher.probe(
            stage = "required_cli",
            command =
            "for tool in ${MoatazRuntimeContract.requiredCli.joinToString(" ")}; do command -v \"\$tool\" >/dev/null 2>&1 || printf '%s\\n' \"\$tool\"; done",
            timeoutSeconds = 30,
        )
        if (!missingCli.success) {
            issues += EnvironmentIssue.BootProbeFailed(missingCli.failureDetail())
        } else {
            missingCli.stdout.lineSequence().map(String::trim).filter(String::isNotEmpty).forEach { tool ->
                issues += EnvironmentIssue.MissingCli(tool, "$tool is not available on PATH")
            }
        }

        val filesystem = launcher.probe(
            stage = "filesystem",
            command = "test -w /tmp && test -w /root && test -d /workspace && test -d /root/projects",
            timeoutSeconds = 20,
            workingDir = MoatazRuntimeContract.workspaceRoot,
        )
        if (!filesystem.success) {
            issues += EnvironmentIssue.WorkspaceMountMissing(filesystem.failureDetail().ifBlank { "Runtime workspace bind probe failed" })
        }

        val pty = launcher.probe(
            stage = "pty",
            command = "python3 -c 'import fcntl,os,pty,struct,termios; " +
                "m,s=pty.openpty(); fcntl.ioctl(m,termios.TIOCSWINSZ,struct.pack(\"HHHH\",31,97,0,0)); " +
                "r,c,_,_=struct.unpack(\"HHHH\",fcntl.ioctl(m,termios.TIOCGWINSZ,struct.pack(\"HHHH\",0,0,0,0))); " +
                "assert r==31 and c==97; os.write(m,b\"moataz-pty\\n\"); assert b\"moataz-pty\" in os.read(s,64); " +
                "assert os.environ.get(\"TERM\"); os.close(m); os.close(s)'",
            timeoutSeconds = 20,
        )
        if (!pty.success) issues += EnvironmentIssue.PtyUnavailable(pty.failureDetail())

        val opencode = launcher.probe(
            stage = "embedded_opencode",
            command = "command -v opencode >/dev/null 2>&1 && opencode --version >/dev/null 2>&1",
            timeoutSeconds = 30,
        )
        if (!opencode.success) {
            issues += EnvironmentIssue.AgentBinaryBroken(
                opencode.failureDetail().ifBlank { "Embedded OpenCode is missing or cannot execute" },
            )
        }
        return EnvironmentHealth(issues)
    }

    private fun ProotLauncher.probe(
        stage: String,
        command: String,
        timeoutSeconds: Long,
        workingDir: String = "/root",
    ): ProotResult {
        val started = System.nanoTime()
        val result = execute(command, timeoutSeconds, workingDir)
        diagnostics.record(
            RuntimeDiagnosticEvent(
                stage = stage,
                command = command,
                exitCode = result.exitCode,
                durationMillis = (System.nanoTime() - started) / 1_000_000,
                stderrTail = result.stderr,
                cause = result.error,
            ),
        )
        return result
    }

    private fun readOsRelease(): OsRelease? = runCatching {
        val file = listOf(File(paths.rootfsDir, "etc/os-release"), File(paths.rootfsDir, "usr/lib/os-release"))
            .firstOrNull { it.exists() } ?: return null
        OsRelease.parse(file.readText())
    }.getOrNull()

    private fun launcher() = ProotLauncher(
        prootPath = paths.prootPath,
        libDir = paths.libDir,
        rootfsPath = paths.rootfsDir.absolutePath,
        tmpPath = paths.tmpDir.absolutePath,
        binds = listOf(
            paths.projectsDir.absolutePath to MoatazRuntimeContract.workspaceRoot,
            paths.projectsDir.absolutePath to MoatazRuntimeContract.legacyProjectsRoot,
        ),
        extraArgs = DebianSpec.prootArgs,
        env = DebianSpec.env,
    )
}
