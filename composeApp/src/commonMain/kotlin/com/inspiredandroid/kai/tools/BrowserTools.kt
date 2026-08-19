package com.inspiredandroid.kai.tools

import com.inspiredandroid.kai.browser.BrowserAction
import com.inspiredandroid.kai.browser.BrowserPolicy
import com.inspiredandroid.kai.browser.BrowserResult
import com.inspiredandroid.kai.browser.BrowserSession
import com.inspiredandroid.kai.browser.BrowserSessionManager
import com.inspiredandroid.kai.browser.ReadFormat
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Args for the 7 browser tools — typed, minimal, prompt-injection aware. */
data class BrowserOpenArgs(
    val sandboxId: String,
    val url: String,
    val timeout: Duration? = null,
)
data class BrowserReadArgs(val sandboxId: String, val format: String = "markdown")
data class BrowserClickArgs(val sandboxId: String, val targetId: String)
data class BrowserTypeArgs(val sandboxId: String, val targetId: String, val text: String, val submit: Boolean = false)
data class BrowserBackArgs(val sandboxId: String)
data class BrowserExtractArgs(val sandboxId: String, val query: String? = null)
data class BrowserCloseArgs(val sandboxId: String)

/**
 * Dispatches browser.* tools. Kept separate from the 23 sandbox tools because
 * browser tools bind to an agent run (session lifecycle) instead of a sandbox.
 * The agent runtime uses the same run id as session key so that cancelling
 * the run cancels in-flight browser actions and cleans up the session.
 */
class BrowserDispatcher(
    private val sessions: BrowserSessionManager,
    private val runtime: ToolRuntime,
) {
    suspend fun call(name: String, raw: Map<String, Any?>): ToolResult = try {
        when (name) {
            "browser.open" -> browserOpen(parseBrowserOpen(raw))
            "browser.read" -> browserRead(parseBrowserRead(raw))
            "browser.click" -> browserClick(parseBrowserClick(raw))
            "browser.type" -> browserType(parseBrowserType(raw))
            "browser.back" -> browserBack(parseBrowserBack(raw))
            "browser.extract" -> browserExtract(parseBrowserExtract(raw))
            "browser.close" -> browserClose(parseBrowserClose(raw))
            else -> ToolResult.Failure("Unknown browser tool: $name")
        }
    } catch (ce: kotlinx.coroutines.CancellationException) {
        throw ce
    } catch (e: Exception) {
        runtime.emitBrowserActivity(name, success = false, detail = e.message ?: e::class.simpleName ?: "error")
        ToolResult.Failure(e.message ?: e::class.simpleName ?: "browser error", retryable = name != "browser.close")
    }

    fun riskLevelFor(name: String): ToolRiskLevel = when (name) {
        // General browsing and reading are read-only / safe for the workspace.
        "browser.open", "browser.read", "browser.back", "browser.extract", "browser.close" -> ToolRiskLevel.READ_ONLY
        // Clicking and typing can change remote state (form submit, button press) —
        // ApprovalEngine sees them as NETWORK-level writes requiring the policy gate.
        "browser.click", "browser.type" -> ToolRiskLevel.NETWORK
        else -> ToolRiskLevel.READ_ONLY
    }

    // ---------- Tool implementations ----------

    private suspend fun browserOpen(args: BrowserOpenArgs): ToolResult {
        val blocked = BrowserPolicy.validateOpen(BrowserAction.Open(args.url))
        if (blocked != null) return ToolResult.Failure("Blocked by browser policy: $blocked", retryable = false)
        sessions.sessionFor(args.sandboxId)
        val action = BrowserAction.Open(args.url, args.timeout?.inWholeMilliseconds ?: 30_000L)
        val result = sessions.run(args.sandboxId, action)
        return toToolResult("browser.open", result) { r ->
            when (r) {
                is BrowserResult.Navigated -> "Navigated to ${r.url}${if (r.title != null) " (${r.title})" else ""}"
                else -> null
            }
        }
    }

    private suspend fun browserRead(args: BrowserReadArgs): ToolResult {
        sessions.sessionFor(args.sandboxId)
        val format = when (args.format.lowercase()) {
            "semantic", "tree" -> ReadFormat.SEMANTIC
            "elements" -> ReadFormat.ELEMENTS
            else -> ReadFormat.MARKDOWN
        }
        val result = sessions.run(args.sandboxId, BrowserAction.Read(format))
        return toToolResult("browser.read", result) { r ->
            when (r) {
                is BrowserResult.Read -> BrowserPolicy.capForLlm(describePageModel(r.model))
                else -> null
            }
        }
    }

    private suspend fun browserClick(args: BrowserClickArgs): ToolResult {
        val blocked = BrowserPolicy.validateTarget(args.targetId)
        if (blocked != null) return ToolResult.Failure("Blocked by browser policy: $blocked", retryable = false)
        sessions.sessionFor(args.sandboxId)
        val result = sessions.run(args.sandboxId, BrowserAction.Click(args.targetId))
        return toToolResult("browser.click", result) { r ->
            when (r) {
                is BrowserResult.Clicked -> "Clicked ${r.targetId} → ${r.url}"
                else -> null
            }
        }
    }

    private suspend fun browserType(args: BrowserTypeArgs): ToolResult {
        val blocked = BrowserPolicy.validateTarget(args.targetId) ?: BrowserPolicy.validateType(args.text)
        if (blocked != null) return ToolResult.Failure("Blocked by browser policy: $blocked", retryable = false)
        sessions.sessionFor(args.sandboxId)
        val result = sessions.run(args.sandboxId, BrowserAction.TypeText(args.targetId, args.text, args.submit))
        return toToolResult("browser.type", result) { r ->
            when (r) {
                is BrowserResult.Typed -> "Typed into ${r.targetId}${if (r.submitted) " and submitted" else ""}"
                else -> null
            }
        }
    }

    private suspend fun browserBack(args: BrowserBackArgs): ToolResult {
        sessions.sessionFor(args.sandboxId)
        val result = sessions.run(args.sandboxId, BrowserAction.Back)
        return toToolResult("browser.back", result) { r ->
            when (r) {
                is BrowserResult.Back -> "Back → ${r.url}${if (r.title != null) " (${r.title})" else ""}"
                else -> null
            }
        }
    }

    private suspend fun browserExtract(args: BrowserExtractArgs): ToolResult {
        val blocked = BrowserPolicy.validateExtract(args.query)
        if (blocked != null) return ToolResult.Failure("Blocked by browser policy: $blocked", retryable = false)
        sessions.sessionFor(args.sandboxId)
        val result = sessions.run(args.sandboxId, BrowserAction.Extract(args.query))
        return toToolResult("browser.extract", result) { r ->
            when (r) {
                is BrowserResult.Extracted -> BrowserPolicy.capForLlm(r.content)
                else -> null
            }
        }
    }

    /** Called by the orchestrator on every terminal run state to close the session. */
    suspend fun cleanupRun(runId: String) {
        sessions.finishRun(runId)
    }

    private suspend fun browserClose(args: BrowserCloseArgs): ToolResult {
        sessions.finishRun(args.sandboxId)
        runtime.emitBrowserActivity("browser.close", success = true, detail = "session closed and cleaned")
        return ToolResult.Success(message = "browser session closed")
    }

    // ---------- Helpers ----------

    private inline fun toToolResult(
        toolName: String,
        result: BrowserResult,
        map: (BrowserResult) -> String?,
    ): ToolResult = when (result) {
        is BrowserResult.Failed -> ToolResult.Failure(result.error, retryable = result.retryable)
        is BrowserResult.Blocked -> ToolResult.Failure("Blocked by browser policy: ${result.reason}", retryable = false)
        else -> {
            val summary = map(result)
            runtime.emitBrowserActivity(toolName, success = true, detail = summary ?: result.toString())
            ToolResult.Success(data = result, message = summary ?: "ok")
        }
    }

    companion object {
        /** Render a page model into LLM-friendly text with stable element indices. */
        fun describePageModel(model: com.inspiredandroid.kai.browser.CdpPageModel): String = when (model) {
            is com.inspiredandroid.kai.browser.CdpPageModel.Markdown -> model.content
            is com.inspiredandroid.kai.browser.CdpPageModel.SemanticTree -> renderNode(model.root, depth = 0)
            is com.inspiredandroid.kai.browser.CdpPageModel.Elements -> buildString {
                model.elements.forEachIndexed { index, el ->
                    appendLine("[${el.targetId}] ${el.role}${if (el.name != null) " \"${el.name}\"" else ""}${if (el.text != null) ": ${el.text}" else ""}")
                }
            }.trimEnd()
            is com.inspiredandroid.kai.browser.CdpPageModel.TitleOnly -> "Page has no visible content"
        }

        private fun renderNode(node: com.inspiredandroid.kai.browser.CdpNode, depth: Int): String = buildString {
            val indent = "  ".repeat(depth.coerceIn(0, 6))
            val label = buildString {
                append(node.role)
                if (node.name != null) append(" \"${node.name}\"")
                if (node.targetId != null) append(" #${node.targetId}")
            }
            appendLine("$indent$label")
            node.children.forEach { append(renderNode(it, depth + 1)) }
        }.trimEnd()

        // ---------- Typed arg parsers ----------

        fun parseBrowserOpen(raw: Map<String, Any?>): BrowserOpenArgs = BrowserOpenArgs(
            sandboxId = raw["sandbox_id"]?.toString() ?: raw["run_id"]?.toString() ?: error("sandbox_id required"),
            url = raw["url"]?.toString() ?: error("url required"),
            timeout = (raw["timeout"] as? Number)?.toLong()?.seconds,
        )
        fun parseBrowserRead(raw: Map<String, Any?>): BrowserReadArgs = BrowserReadArgs(
            sandboxId = raw["sandbox_id"]?.toString() ?: raw["run_id"]?.toString() ?: error("sandbox_id required"),
            format = raw["format"]?.toString() ?: "markdown",
        )
        fun parseBrowserClick(raw: Map<String, Any?>): BrowserClickArgs = BrowserClickArgs(
            sandboxId = raw["sandbox_id"]?.toString() ?: raw["run_id"]?.toString() ?: error("sandbox_id required"),
            targetId = raw["target_id"]?.toString() ?: error("target_id required"),
        )
        fun parseBrowserType(raw: Map<String, Any?>): BrowserTypeArgs = BrowserTypeArgs(
            sandboxId = raw["sandbox_id"]?.toString() ?: raw["run_id"]?.toString() ?: error("sandbox_id required"),
            targetId = raw["target_id"]?.toString() ?: error("target_id required"),
            text = raw["text"]?.toString() ?: error("text required"),
            submit = raw["submit"] as? Boolean ?: false,
        )
        fun parseBrowserBack(raw: Map<String, Any?>): BrowserBackArgs = BrowserBackArgs(
            sandboxId = raw["sandbox_id"]?.toString() ?: raw["run_id"]?.toString() ?: error("sandbox_id required"),
        )
        fun parseBrowserExtract(raw: Map<String, Any?>): BrowserExtractArgs = BrowserExtractArgs(
            sandboxId = raw["sandbox_id"]?.toString() ?: raw["run_id"]?.toString() ?: error("sandbox_id required"),
            query = raw["query"]?.toString(),
        )
        fun parseBrowserClose(raw: Map<String, Any?>): BrowserCloseArgs = BrowserCloseArgs(
            sandboxId = raw["sandbox_id"]?.toString() ?: raw["run_id"]?.toString() ?: error("sandbox_id required"),
        )
    }
}

