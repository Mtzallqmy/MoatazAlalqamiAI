package com.inspiredandroid.kai.sandbox

import com.inspiredandroid.kai.linux.ProotHandle
import com.inspiredandroid.kai.linux.ProotLauncher
import com.inspiredandroid.kai.linux.PtyProotExecutor
import java.io.BufferedReader
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay

private const val MAX_OUTPUT_LENGTH = 15_000
private const val DEFAULT_TIMEOUT_SECONDS = 30L
private const val MAX_TIMEOUT_SECONDS = 180L

/**
 * The chat sandbox's view of a rootfs: line-oriented output over pipes, and
 * results shaped as the map the shell tool and background jobs already consume.
 *
 * Everything about starting the process — argv, binds, environment — lives in
 * the shared [ProotLauncher]. Callers that explicitly need a TTY obtain a
 * [PtyProotExecutor] from the same launcher via [createPtyExecutor].
 */
class ProotExecutor(private val launcher: ProotLauncher) {

    /** Create a real-PTY executor without duplicating the PRoot argv/bind setup. */
    fun createPtyExecutor(
        tmpPath: String,
        columns: Int = 80,
        rows: Int = 24,
    ): PtyProotExecutor = PtyProotExecutor(
        launcher = launcher,
        tmpPath = tmpPath,
        initialColumns = columns,
        initialRows = rows,
    )

    suspend fun executeWithRetry(
        commands: List<String>,
        timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
        maxAttempts: Int = 2,
        workingDir: String = "/root",
        extraEnv: Map<String, String> = emptyMap(),
    ): Map<String, Any> {
        var lastResult: Map<String, Any> = mapOf("success" to false, "error" to "No commands provided")
        for (command in commands) {
            for (attempt in 0 until maxAttempts) {
                if (attempt > 0) delay(2_000L * attempt)
                val result = execute(command, timeoutSeconds, workingDir, extraEnv)
                if ((result["success"] as? Boolean) == true) return result
                lastResult = result
            }
        }
        return lastResult
    }

    fun execute(
        command: String,
        timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
        workingDir: String = "/root",
        extraEnv: Map<String, String> = emptyMap(),
    ): Map<String, Any> {
        require(workingDir.isWithinSandbox()) {
            "Refusing working directory outside the sandbox: $workingDir"
        }
        val result = launcher.execute(
            command = command,
            timeoutSeconds = timeoutSeconds.coerceIn(1, MAX_TIMEOUT_SECONDS),
            workingDir = workingDir,
            extraEnv = extraEnv,
            maxOutputChars = MAX_OUTPUT_LENGTH,
        )
        if (!result.success && result.stdout.isEmpty() && result.stderr.isEmpty() && !result.timedOut) {
            result.error?.let { return mapOf("success" to false, "error" to it) }
        }
        return mapOf(
            "success" to result.success,
            "stdout" to result.stdout,
            "stderr" to result.stderr,
            "exit_code" to result.exitCode,
            "timed_out" to result.timedOut,
        )
    }

    fun executeStreaming(
        command: String,
        workingDir: String = "/root",
        extraEnv: Map<String, String> = emptyMap(),
        onStdout: (String) -> Unit,
        onStderr: (String) -> Unit,
    ): ProotHandle {
        require(workingDir.isWithinSandbox()) {
            "Refusing working directory outside the sandbox: $workingDir"
        }
        return launcher.startStreaming(
            command = command,
            workingDir = workingDir,
            extraEnv = extraEnv,
        ) { process, cancelled ->
            listOf(
                CompletableFuture.runAsync {
                    streamLines(process.inputStream.bufferedReader(), cancelled, onStdout)
                },
                CompletableFuture.runAsync {
                    streamLines(process.errorStream.bufferedReader(), cancelled, onStderr)
                },
            )
        }
    }

    private fun String.isWithinSandbox(): Boolean {
        val normalized = normalize(this)
        return normalized == "/" ||
            normalized == "/root" ||
            normalized.startsWith("/root/") ||
            normalized.startsWith("/tmp/") ||
            normalized == "/tmp"
    }

    private fun normalize(path: String): String {
        val parts = path.trimStart('/').split("/").filter { it.isNotEmpty() }
        val stack = ArrayDeque<String>()
        for (part in parts) {
            when {
                part == ".." -> if (stack.isNotEmpty()) stack.removeLast()
                part == "." -> {}
                else -> stack.addLast(part)
            }
        }
        return "/" + stack.joinToString("/")
    }

    private fun streamLines(
        reader: BufferedReader,
        cancelled: AtomicBoolean,
        onLine: (String) -> Unit,
    ) {
        try {
            while (!cancelled.get()) {
                val line = try {
                    reader.readLine()
                } catch (e: IOException) {
                    if (cancelled.get()) break
                    throw e
                } ?: break
                onLine(line)
            }
        } finally {
            runCatching { reader.close() }
        }
    }
}
