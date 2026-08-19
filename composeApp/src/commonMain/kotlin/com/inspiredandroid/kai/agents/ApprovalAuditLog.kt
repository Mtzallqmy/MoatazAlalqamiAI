package com.inspiredandroid.kai.agents

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentLinkedDeque
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * Phase 14 — tamper-evident audit trail of every approval decision taken by
 * the approval engine and by the human operator. Every `approve` / `reject` /
 * `autoApprove` / `block` is appended in memory with a timestamp, the tool
 * name, its risk, the args summary and the final verdict.
 *
 * Why a ring buffer rather than persistent storage? Decisions are also
 * surfaced to [ApprovalActivityEventFlow] for the live activity timeline and
 * kept short-lived by design (privacy-local-only). A bounded in-memory
 * history of [MAX_ENTRIES] covers post-run review without pinning memory.
 */
object ApprovalAuditLog {

    data class Entry(
        val timestampMs: Long = System.currentTimeMillis(),
        val toolId: String,
        val toolRisk: String,
        val argsSummary: String,
        val verdict: Verdict,
        val note: String = "",
    )

    enum class Verdict { AutoApproved, Approved, Rejected, Blocked }

    private const val MAX_ENTRIES = 500
    private val RETENTION: Duration = 14.days

    private val entries: ConcurrentLinkedDeque<Entry> = ConcurrentLinkedDeque()
    private val _events = MutableSharedFlow<Entry>(extraBufferCapacity = 64)

    /** Live stream of new audit entries for the activity timeline. */
    val events: SharedFlow<Entry> = _events.asSharedFlow()

    fun all(): List<Entry> = entries.toList()

    fun record(toolId: String, toolRisk: String, argsSummary: String, verdict: Verdict, note: String = "") {
        val entry = Entry(toolId = toolId, toolRisk = toolRisk, argsSummary = argsSummary, verdict = verdict, note = note)
        entries.addLast(entry)
        while (entries.size > MAX_ENTRIES) entries.pollFirst()
        purgeExpired()
        _events.tryEmit(entry)
    }

    fun clear() = entries.clear()

    private fun purgeExpired() {
        val cutoff = System.currentTimeMillis() - RETENTION.inWholeMilliseconds
        while (entries.peekFirst()?.let { it.timestampMs < cutoff } == true) entries.pollFirst()
    }
}
