package com.inspiredandroid.kai.runtime

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
    }
}

object RuntimeDiagnosticRedactor {
    private val assignment = Regex("(?i)(api[_-]?key|token|authorization|password|secret)=([^\\s]+)")
    private val bearer = Regex("(?i)bearer\\s+[A-Za-z0-9._~+/-]+")

    fun redact(value: String): String = value
        .replace(assignment) { "${it.groupValues[1]}=[REDACTED]" }
        .replace(bearer, "Bearer [REDACTED]")
        .takeLast(2_000)
}

class InMemoryRuntimeDiagnostics(private val limit: Int = 200) : RuntimeDiagnosticsSink {
    private val events = ArrayDeque<RuntimeDiagnosticEvent>()

    override fun record(event: RuntimeDiagnosticEvent) {
        events.addLast(event.copy(
            command = event.command?.let(RuntimeDiagnosticRedactor::redact),
            stderrTail = event.stderrTail?.let(RuntimeDiagnosticRedactor::redact),
            cause = event.cause?.let(RuntimeDiagnosticRedactor::redact),
        ))
        while (events.size > limit) events.removeFirst()
    }

    fun snapshot(): List<RuntimeDiagnosticEvent> = events.toList()
}
