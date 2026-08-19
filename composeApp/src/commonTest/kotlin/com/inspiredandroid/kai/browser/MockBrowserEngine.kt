package com.inspiredandroid.kai.browser

/**
 * In-memory [BrowserEngine] for unit tests — a fully scriptable fake page
 * model. Never use in production; it exists solely to make browser policy,
 * session isolation and cancellation tests deterministic.
 *
 * Behavior contract (tests rely on these):
 * - sessions are keyed by run id and are closed on [close]
 * - element ids are issued in visit order and stay stable across actions
 * - open always navigates (subject to SSRF policy, enforced by the tool
 *   layer, not here — this engine trusts validated actions)
 */
class MockBrowserEngine : BrowserEngine {
    override val id: BrowserEngineId = BrowserEngineId("mock")

    /** The pages loaded per run, in order — tests assert navigation history. */
    val navigatedUrls: MutableList<String> = mutableListOf()

    private val active = mutableMapOf<String, MockPageSession>()

    /** Configure the page shown on any new session. */
    var pageProvider: (url: String) -> MockPage = { _ -> DEFAULT_PAGE }

    override suspend fun openSession(runId: String): BrowserSession {
        val session = MockPageSession(runId = runId, page = pageProvider("about:blank"), history = mutableListOf())
        active[runId] = session
        return BrowserSession(sessionId = "mock-$runId", engineId = id, runId = runId)
    }

    override suspend fun execute(session: BrowserSession, action: BrowserAction): BrowserResult {
        val page = active[session.runId]?.page ?: return BrowserResult.Failed("mock session expired", retryable = false)
        if (!active[session.runId]!!.isActive) return BrowserResult.Failed("session closed", retryable = false)
        return when (action) {
            is BrowserAction.Open -> {
                active[session.runId]!!.history += action.url
                active[session.runId]!!.page = pageProvider(action.url)
                navigatedUrls += action.url
                BrowserResult.Navigated(action.url, active[session.runId]!!.page.title)
            }
            is BrowserAction.Read -> BrowserResult.Read(
                when (action.format) {
                    ReadFormat.MARKDOWN -> CdpPageModel.Markdown(active[session.runId]!!.page.markdown, session.runId)
                    ReadFormat.SEMANTIC -> CdpPageModel.SemanticTree(active[session.runId]!!.page.tree)
                    ReadFormat.ELEMENTS -> CdpPageModel.Elements(active[session.runId]!!.page.elements)
                },
            )
            is BrowserAction.Click -> {
                val target = active[session.runId]!!.page.elements.firstOrNull { it.targetId == action.targetId }
                if (target == null) return BrowserResult.Failed("unknown target ${action.targetId}", retryable = false)
                BrowserResult.Clicked(action.targetId, session.runId)
            }
            is BrowserAction.TypeText -> {
                val target = active[session.runId]!!.page.elements.firstOrNull { it.targetId == action.targetId }
                if (target == null) return BrowserResult.Failed("unknown target ${action.targetId}", retryable = false)
                BrowserResult.Typed(action.targetId, submitted = action.submit)
            }
            is BrowserAction.Back -> {
                val history = active[session.runId]!!.history
                if (history.size <= 1) return BrowserResult.Failed("no history to go back", retryable = false)
                history.removeAt(history.lastIndex)
                BrowserResult.Back(history.last(), active[session.runId]!!.page.title)
            }
            is BrowserAction.Extract -> {
                val text = if (action.query != null) "${active[session.runId]!!.page.title}: ${action.query} found"
                else active[session.runId]!!.page.markdown
                BrowserResult.Extracted(text)
            }
            is BrowserAction.Close -> BrowserResult.Closed
        }
    }

    override suspend fun close(session: BrowserSession) {
        active[session.runId]?.isActive = false
        active.remove(session.runId)
    }

    fun runIdCount(): Int = active.keys.size
}

/** Scriptable fake page — tests supply the content the agent "sees". */
data class MockPage(
    val url: String = "https://example.com",
    val title: String = "Example",
    val markdown: String = "# Example\n\nA sample page for tests.",
    val tree: CdpNode = CdpNode(role = "document", name = "Example", targetId = null),
    val elements: List<CdpElement> = listOf(
        CdpElement(targetId = "el-1", tag = "h1", role = "heading", name = "Example", value = null, text = "Example"),
        CdpElement(targetId = "el-2", tag = "input", role = "textbox", name = "search", value = null, text = null),
        CdpElement(targetId = "el-3", tag = "button", role = "button", name = "Search", value = null, text = "Search"),
    ),
)

/** One fake session — tracks history and alive state per run id. */
private class MockPageSession(
    val runId: String,
    var page: MockPage,
    val history: MutableList<String>,
    var isActive: Boolean = true,
)

/** Default page for the mock — plain and safe. */
internal val DEFAULT_PAGE = MockPage()
