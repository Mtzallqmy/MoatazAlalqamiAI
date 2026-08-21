package com.inspiredandroid.kai.agents

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for the supervised [ApprovalEngine].
 *
 * Core security invariants verified here:
 * - Unknown tools can NEVER be auto-executed (anti-prompt-injection).
 * - Destructive git operations always require explicit approval.
 * - The approval policy comes only from user configuration — never from
 *   tool outputs or agent-provided data.
 */
class ApprovalEngineTest {

    private val knownTools = setOf("read_file", "write_file", "run_command", "git_diff", "search_web", "push_changes")

    private fun engine() = ApprovalEngine(knownToolIds = { knownTools })

    // ---------------------------------------------------------------
    // Unknown tool gating (anti-injection core rule)
    // ---------------------------------------------------------------

    @Test
    fun `unknown tool always requires approval`() {
        val decision = engine().decide("delete_all_files", ToolRisk.Dangerous, ApprovalMode.Autonomous, "{}")
        assertTrue(decision is ApprovalDecision.NeedsApproval)
    }

    @Test
    fun `unknown tool is gated even in autonomous mode with safe risk`() {
        val decision = engine().decide("mystery_tool", ToolRisk.SafeRead, ApprovalMode.Autonomous, "{}")
        assertTrue(decision is ApprovalDecision.NeedsApproval)
    }

    // ---------------------------------------------------------------
    // Destructive git guardrails
    // ---------------------------------------------------------------

    @Test
    fun `git reset hard always needs approval`() {
        val args = """{"command":"git reset --hard HEAD"}"""
        val decision = engine().decide("run_command", ToolRisk.Dangerous, ApprovalMode.Autonomous, args)
        assertTrue(decision is ApprovalDecision.NeedsApproval)
    }

    @Test
    fun `git clean -fd always needs approval`() {
        val args = """{"command":"git clean -fd"}"""
        val decision = engine().decide("run_command", ToolRisk.Dangerous, ApprovalMode.Autonomous, args)
        assertTrue(decision is ApprovalDecision.NeedsApproval)
    }

    @Test
    fun `force push always needs approval`() {
        val args = """{"command":"git push --force origin main"}"""
        val decision = engine().decide("run_command", ToolRisk.Dangerous, ApprovalMode.Autonomous, args)
        assertTrue(decision is ApprovalDecision.NeedsApproval)
    }

    // ---------------------------------------------------------------
    // Approval modes
    // ---------------------------------------------------------------

    @Test
    fun `safe mode asks for everything including reads`() {
        val decision = engine().decide("read_file", ToolRisk.SafeRead, ApprovalMode.Safe, "{}")
        assertTrue(decision is ApprovalDecision.NeedsApproval)
    }

    @Test
    fun `balanced mode auto-approves reads and local writes`() {
        assertEquals(ApprovalDecision.AutoApproved, engine().decide("read_file", ToolRisk.SafeRead, ApprovalMode.Balanced, "{}"))
        assertEquals(ApprovalDecision.AutoApproved, engine().decide("write_file", ToolRisk.LocalWrite, ApprovalMode.Balanced, "{}"))
    }

    @Test
    fun `balanced mode asks for network writes`() {
        val decision = engine().decide("search_web", ToolRisk.NetworkWrite, ApprovalMode.Balanced, "{}")
        assertTrue(decision is ApprovalDecision.NeedsApproval)
    }

    @Test
    fun `autonomous mode asks only for dangerous actions`() {
        assertEquals(ApprovalDecision.AutoApproved, engine().decide("search_web", ToolRisk.NetworkWrite, ApprovalMode.Autonomous, "{}"))
        val decision = engine().decide("push_changes", ToolRisk.Dangerous, ApprovalMode.Autonomous, """{"force":false}""")
        assertTrue(decision is ApprovalDecision.NeedsApproval)
    }

    @Test
    fun `new network and package tiers always require explicit approval`() {
        assertTrue(engine().decide("search_web", ToolRisk.Network, ApprovalMode.Autonomous, "{}") is ApprovalDecision.NeedsApproval)
        assertTrue(engine().decide("run_command", ToolRisk.PackageInstall, ApprovalMode.Autonomous, "{}") is ApprovalDecision.NeedsApproval)
    }

    @Test
    fun `push and deploy commands are classified as external effects`() {
        assertEquals(
            ToolRisk.ExternalEffect,
            ApprovalEngine.classifyCommandRisk("terminal.exec", """{"command":"git push origin main"}""", ToolRisk.WorkspaceWrite),
        )
        assertEquals(
            ToolRisk.ExternalEffect,
            ApprovalEngine.classifyCommandRisk("terminal.exec", """{"command":"deploy production"}""", ToolRisk.WorkspaceWrite),
        )
    }

    @Test
    fun `delete and writes outside workspace are elevated to destructive`() {
        assertEquals(ToolRisk.Destructive, ApprovalEngine.classifyCommandRisk("fs.delete", "{}", ToolRisk.WorkspaceWrite))
        assertEquals(
            ToolRisk.Destructive,
            ApprovalEngine.classifyCommandRisk("fs.write", """{"path":"/etc/profile"}""", ToolRisk.WorkspaceWrite),
        )
    }
}
