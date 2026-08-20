package com.inspiredandroid.kai.linux

import com.inspiredandroid.kai.smartTruncate
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Default guest PATH. Callers that need vendor bin dirs override it. */
const val DEFAULT_GUEST_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

private const val DEFAULT_MAX_OUTPUT_CHARS = 15_000
private const val LEGACY_PROJECTS_PATH = "/root/projects"
private const val UNIFIED_WORKSPACE_PATH = "/workspace"

data class ProotResult(
    val success: Boolean,
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int = -1,
    val timedOut: Boolean = false,
    val error: String? = null,
) {
    fun failureDetail(maxChars: Int = 500): String {
        val raw = stderr.ifBlank { stdout }.ifBlank { error.orEmpty() }.trim()
        if (raw.length <= maxChars) return raw
        return "…" + raw.takeLast(maxChars)
    }
}

class ProotHandle internal constructor(
    private val process: Process,
    private val cancelled: AtomicBoolean,
    private val readerFutures: List<CompletableFuture<Void>>,
    /** Host-side pid file written by PTY bridges. */
    private val guestPidFile: File? = null,
) {
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "kai-proot-write").apply { isDaemon = true }
    }

    fun isCancelled(): Boolean = cancelled.get()

    fun cancel() {
        cancelled.set(true)
        killGuest()
        runCatching { writer.shutdownNow() }
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
        runCatching { process.outputStream.close() }
        process.destroyForcibly()
    }

    private fun killGuest() {
        val file = guestPidFile ?: return
        val pid = runCatching { file.readText().trim().toIntOrNull() }.getOrNull()
        if (pid != null && pid > 0) runCatching { android.os.Process.killProcess(pid) }
        runCatching { file.delete() }
    }

    fun writeBytes(data: ByteArray) {
        if (cancelled.get() || data.isEmpty()) return
        runCatching {
            writer.execute {
                if (cancelled.get()) return@execute
                runCatching {
                    process.outputStream.write(data)
                    process.outputStream.flush()
                }
            }
        }
    }

    fun writeText(text: String) = writeBytes(text.toByteArray(Charsets.UTF_8))
    fun writeLine(line: String) = writeText(line + "\n")

    fun awaitExit(): Int {
        while (!cancelled.get() && process.isAlive) {
            runCatching { process.waitFor(200, TimeUnit.MILLISECONDS) }
        }
        if (cancelled.get()) return -1
        readerFutures.forEach { runCatching { it.get(500, TimeUnit.MILLISECONDS) } }
        return runCatching { process.exitValue() }.getOrDefault(-1)
    }
}

/** Shared source of truth for every local PRoot invocation. */
class ProotLauncher(
    private val prootPath: String,
    private val libDir: String,
    private val rootfsPath: String,
    private val tmpPath: String,
    /** Extra host→guest binds on top of `/dev`, `/proc`, `/sys` and `/tmp`. */
    private val binds: List<Pair<String, String>>,
    private val extraArgs: List<String> = emptyList(),
    private val env: Map<String, String> = emptyMap(),
) {

    fun start(
        command: String,
        workingDir: String,
        extraEnv: Map<String, String> = emptyMap(),
    ): Process = Runtime.getRuntime().exec(
        buildArgs(command, workingDir),
        buildEnv(extraEnv),
        File(rootfsPath).parentFile,
    )

    fun execute(
        command: String,
        timeoutSeconds: Long,
        workingDir: String = "/root",
        extraEnv: Map<String, String> = emptyMap(),
        maxOutputChars: Int = DEFAULT_MAX_OUTPUT_CHARS,
    ): ProotResult = try {
        val process = start(command, workingDir, extraEnv)
        val stdout = CompletableFuture.supplyAsync {
            readBounded(process.inputStream.bufferedReader(), maxOutputChars)
        }
        val stderr = CompletableFuture.supplyAsync {
            readBounded(process.errorStream.bufferedReader(), maxOutputChars)
        }
        if (process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            ProotResult(
                success = process.exitValue() == 0,
                stdout = stdout.get().smartTruncate(maxOutputChars),
                stderr = stderr.get().smartTruncate(maxOutputChars),
                exitCode = process.exitValue(),
            )
        } else {
            process.destroyForcibly()
            ProotResult(
                success = false,
                stdout = stdout.get(1, TimeUnit.SECONDS).smartTruncate(maxOutputChars),
                stderr = stderr.get(1, TimeUnit.SECONDS).smartTruncate(maxOutputChars),
                timedOut = true,
                error = "Timed out after ${timeoutSeconds}s",
            )
        }
    } catch (e: Exception) {
        ProotResult(success = false, error = e.message ?: "Failed to execute command in sandbox")
    }

    fun startStreaming(
        command: String,
        workingDir: String,
        extraEnv: Map<String, String> = emptyMap(),
        guestPidFile: File? = null,
        readers: (Process, AtomicBoolean) -> List<CompletableFuture<Void>>,
    ): ProotHandle {
        val process = start(command, workingDir, extraEnv)
        val cancelled = AtomicBoolean(false)
        return ProotHandle(process, cancelled, readers(process, cancelled), guestPidFile)
    }

    private fun buildArgs(command: String, workingDir: String): Array<String> = buildList {
        add(prootPath)
        addAll(extraArgs)
        add("--rootfs=$rootfsPath")
        add("--bind=/dev")
        add("--bind=/proc")
        add("--bind=/sys")
        binds.forEach { (host, guest) ->
            add("--bind=$host:$guest")
            // The app historically exposed projects at /root/projects while the
            // unified SandboxBackend contract uses /workspace. Bind both names
            // to the same host directory so agents and the mobile UI see exactly
            // the same files and old sessions remain compatible.
            if (guest == LEGACY_PROJECTS_PATH) {
                add("--bind=$host:$UNIFIED_WORKSPACE_PATH")
            }
        }
        add("--bind=$tmpPath:/tmp")
        add("-0")
        add("-w")
        add(workingDir)
        add("/bin/sh")
        add("-c")
        add(command)
    }.toTypedArray()

    private fun buildEnv(extraEnv: Map<String, String>): Array<String> {
        val loaderPath = File(prootPath).parent.orEmpty() + "/libproot-loader.so"
        val base = mapOf(
            "HOME" to "/root",
            "PATH" to DEFAULT_GUEST_PATH,
            "TERM" to "xterm-256color",
            "LANG" to "C.UTF-8",
            "LD_LIBRARY_PATH" to libDir,
            "PROOT_TMP_DIR" to tmpPath,
            "PROOT_LOADER" to loaderPath,
        )
        return (base + env + extraEnv).map { (k, v) -> "$k=$v" }.toTypedArray()
    }

    private fun readBounded(reader: BufferedReader, maxChars: Int): String {
        val sb = StringBuilder()
        val buf = CharArray(8192)
        try {
            var read: Int
            while (reader.read(buf).also { read = it } != -1) {
                sb.append(buf, 0, read)
                if (sb.length >= maxChars) break
            }
            if (sb.length >= maxChars) {
                while (reader.read(buf) != -1) { /* discard */ }
            }
        } catch (_: IOException) {
            // Stream closed by cancellation/timeout; return the partial output.
        }
        return sb.toString()
    }
}
