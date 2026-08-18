package com.inspiredandroid.kai.agents

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApprovalAuditLogTest {

    @AfterTest
    fun cleanup() = ApprovalAuditLog.clear()

    @Test
    fun `records approve verdicts`() {
        ApprovalAuditLog.record("shell_exec", "Dangerous", "rm -rf /", ApprovalAuditLog.Verdict.Rejected, "user declined")
        ApprovalAuditLog.record("read_file", "SafeRead", "/etc/hosts", ApprovalAuditLog.Verdict.AutoApproved)

        val entries = ApprovalAuditLog.all()
        assertEquals(2, entries.size)
        assertEquals(ApprovalAuditLog.Verdict.Rejected, entries[0].verdict)
        assertEquals(ApprovalAuditLog.Verdict.AutoApproved, entries[1].verdict)
    }

    @Test
    fun `ring buffer caps entries`() {
        repeat(600) { ApprovalAuditLog.record("t$it", "SafeRead", "x", ApprovalAuditLog.Verdict.AutoApproved) }
        assertTrue(ApprovalAuditLog.all().size <= 500)
    }

    @Test
    fun `clear empties the log`() {
        ApprovalAuditLog.record("t", "SafeRead", "x", ApprovalAuditLog.Verdict.Approved)
        ApprovalAuditLog.clear()
        assertEquals(0, ApprovalAuditLog.all().size)
    }
}
