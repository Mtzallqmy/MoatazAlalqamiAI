package com.inspiredandroid.kai.runtime

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class RuntimeDiagnosticEvent(
    val stage: String,
    val command: String?,
    val exitCode: Int?,
    val durationMillis: Long,
    val stderrTail: String?,
    val cause: String?,
)

fun interface RuntimeDiagnosticsSink {
    fun record(event: RuntimeDiagnosticEvent)

    companion object {
        val None = RuntimeDiagnosticsSink {}
        val Shared: RuntimeDiagnosticsSink get() = RuntimeDiagnosticsStore
    }
}

object RuntimeDiagnosticRedactor {
    private val assignment = Regex(
        "(?i)([\\\"']?(?:api[_-]?key|access[_-]?token|refresh[_-]?token|token|authorization|password|secret)" +
            "[\\\"']?\\s*[=:]\\s*)" +
            "(\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;]+)",
    )
    private val commandArgument = Regex(
        "(?i)(--?(?:api[_-]?key|access[_-]?token|refresh[_-]?token|token|authorization|password|secret)\\s+)" +
            "(\\\"[^\\\"]*\\\"|'[^']*'|[^\\s]+)",
    )
    private val bearer = Regex("(?i)bearer\\s+[A-Za-z0-9._~+/-]+")
    private val credentialUrl = Regex("(?i)(https?://[^:/\\s]+:)[^@/\\s]+(@)")
    private val knownToken = Regex(
        "(?i)\\b(?:sk-[A-Za-z0-9_-]{16,}|gh[pousr]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,}|" +
            "AIza[A-Za-z0-9_-]{20,}|xox[baprs]-[A-Za-z0-9-]{16,})\\b",
    )
    private val privateKey = Regex(
        "-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z0-9 ]*PRIVATE KEY-----",
    )

    fun redact(value: String): String = value
        .replace(privateKey, "[REDACTED PRIVATE KEY]")
        .replace(credentialUrl) { "${it.groupValues[1]}[REDACTED]${it.groupValues[2]}" }
        .replace(bearer, "Bearer [REDACTED]")
        .replace(assignment) { "${it.groupValues[1]}[REDACTED]" }
        .replace(commandArgument) { "${it.groupValues[1]}[REDACTED]" }
        .replace(knownToken, "[REDACTED TOKEN]")
        .takeLast(2_000)
}

class InMemoryRuntimeDiagnostics(private val limit: Int = 200) : RuntimeDiagnosticsSink {
    private val lock = Mutex()
    private val events = ArrayDeque<RuntimeDiagnosticEvent>()

    override fun record(event: RuntimeDiagnosticEvent) {
        val safeEvent = event.copy(
            command = event.command?.let(RuntimeDiagnosticRedactor::redact),
            stderrTail = event.stderrTail?.let(RuntimeDiagnosticRedactor::redact),
            cause = event.cause?.let(RuntimeDiagnosticRedactor::redact),
        )
        runBlocking {
            lock.withLock {
                events.addLast(safeEvent)
                while (events.size > limit) events.removeFirst()
            }
        }
    }

    fun snapshot(): List<RuntimeDiagnosticEvent> = runBlocking { lock.withLock { events.toList() } }
}

/** Process-wide bounded diagnostics used by install, health and repair probes. */
object RuntimeDiagnosticsStore : RuntimeDiagnosticsSink {
    private val delegate = InMemoryRuntimeDiagnostics(limit = 200)

    override fun record(event: RuntimeDiagnosticEvent) = delegate.record(event)

    fun snapshot(): List<RuntimeDiagnosticEvent> = delegate.snapshot()

    fun exportText(): String = snapshot().joinToString("\n\n") { event ->
        buildString {
            append("stage=").append(event.stage)
            append(" durationMs=").append(event.durationMillis)
            append(" exitCode=").append(event.exitCode ?: "n/a")
            event.command?.takeIf(String::isNotBlank)?.let { append("\ncommand=").append(it) }
            event.stderrTail?.takeIf(String::isNotBlank)?.let { append("\nstderr=").append(it) }
            event.cause?.takeIf(String::isNotBlank)?.let { append("\ncause=").append(it) }
        }
    }
}
