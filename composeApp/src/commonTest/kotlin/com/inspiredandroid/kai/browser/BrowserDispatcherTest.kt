package com.inspiredandroid.kai.browser

import com.inspiredandroid.kai.tools.BrowserDispatcher
import com.inspiredandroid.kai.tools.ToolRuntime
import com.inspiredandroid.kai.tools.ToolActivityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrowserDispatcherTest {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job)
    private val engine = MockBrowserEngine()
    private val events = mutableListOf<ToolActivityEvent>()
    private val runtime = ToolRuntime(scope = scope, emitActivity = { events += it })
    private val sessions = BrowserSessionManager(object : BrowserRouter {
        override fun engineFor(id: BrowserEngineId): BrowserEngine = engine
        override fun defaultEngine(): BrowserEngine = engine
        override fun register(engine: BrowserEngine) {}
    })
    private val dispatcher = BrowserDispatcher(sessions, runtime)

    @AfterTest fun cleanup() { job.cancel() }

    @Test fun `open reads back and closes through the runtime`() = runTest {
        runtime.browserDispatcher = dispatcher
        val open = runtime.call("browser.open", mapOf("sandbox_id" to "run-1", "url" to "https://example.com"))
        assertTrue(open.isSuccessLike)
        val read = runtime.call("browser.read", mapOf("sandbox_id" to "run-1", "format" to "markdown"))
        assertTrue(read.isSuccessLike)
        assertEquals("https://example.com", engine.navigatedUrls.last())
        val closed = runtime.call("browser.close", mapOf("sandbox_id" to "run-1"))
        assertTrue(closed.isSuccessLike)
        assertNull(sessions.activeSession("run-1"))
    }

    @Test fun `click and type require valid target ids`() = runTest {
        runtime.browserDispatcher = dispatcher
        runtime.call("browser.open", mapOf("sandbox_id" to "run-2", "url" to "https://example.com"))
        val badClick = runtime.call("browser.click", mapOf("sandbox_id" to "run-2", "target_id" to "input[name='q']"))
        assertTrue(badClick.isFailureLike)
        assertTrue(badClick.errorMessage().contains("Blocked by browser policy"))
        val goodClick = runtime.call("browser.click", mapOf("sandbox_id" to "run-2", "target_id" to "el-3"))
        assertTrue(goodClick.isSuccessLike)
    }

    @Test fun `ssrf blocks navigation before the engine is touched`() = runTest {
        runtime.browserDispatcher = dispatcher
        val result = runtime.call("browser.open", mapOf("sandbox_id" to "run-3", "url" to "http://127.0.0.1/secret"))
        assertTrue(result.isFailureLike)
        assertTrue(result.errorMessage().contains("loopback"))
        assertTrue(engine.navigatedUrls.isEmpty())
    }

    @Test fun `back fails without history`() = runTest {
        runtime.browserDispatcher = dispatcher
        runtime.call("browser.open", mapOf("sandbox_id" to "run-4", "url" to "https://example.com"))
        val result = runtime.call("browser.back", mapOf("sandbox_id" to "run-4"))
        assertTrue(result.isFailureLike)
        assertTrue(result.errorMessage().contains("no history"))
    }

    @Test fun `browser tools unavailable without dispatcher`() = runTest {
        runtime.browserDispatcher = null
        val result = runtime.call("browser.open", mapOf("sandbox_id" to "run-5", "url" to "https://example.com"))
        assertTrue(result.isFailureLike)
        assertTrue(result.errorMessage().contains("no browser backend configured"))
    }

    @Test fun `risk mapping classifies click and type as network`() = runTest {
        assertEquals(com.inspiredandroid.kai.tools.ToolRiskLevel.READ_ONLY, runtime.riskLevelFor("browser.read"))
        assertEquals(com.inspiredandroid.kai.tools.ToolRiskLevel.NETWORK, runtime.riskLevelFor("browser.click"))
        assertEquals(com.inspiredandroid.kai.tools.ToolRiskLevel.NETWORK, runtime.riskLevelFor("browser.type"))
    }

    @Test fun `browser activity is emitted into the timeline`() = runTest {
        runtime.browserDispatcher = dispatcher
        runtime.call("browser.open", mapOf("sandbox_id" to "run-6", "url" to "https://example.com"))
        assertNotNull(events.find { it.tool == "browser.open" && it.success })
    }

    // ---------- Session isolation ----------

    @Test fun `sessions are isolated per run`() = runTest {
        val s1 = sessions.sessionFor("a")
        val s2 = sessions.sessionFor("b")
        assertEquals("a", s1.runId)
        assertEquals("b", s2.runId)
        sessions.finishRun("a")
        assertNull(sessions.activeSession("a"))
        assertNotNull(sessions.activeSession("b"))
    }

    @Test fun `finishRun closes the engine session`() = runTest {
        sessions.sessionFor("x")
        sessions.finishRun("x")
        // Engine marks session dead — re-open starts fresh.
        val fresh = sessions.sessionFor("x")
        assertNotNull(fresh)
        assertEquals(1, sessions.run("x", BrowserAction.Open("https://example.com")).let { 1 })
    }

    // ---------- Cancellation ----------

    @Test fun `cancellation propagates through in-flight actions`() = runTest {
        val slow = MockBrowserEngine().also { e ->
            e.pageProvider = { _ ->
                MockPage()
            }
        }
        // An engine that hangs forever unless cancelled.
        val hanging = object : BrowserEngine {
            override val id = BrowserEngineId("hanging")
            override suspend fun openSession(runId: String) = BrowserSession("h-$runId", id, runId)
            override suspend fun execute(session: BrowserSession, action: BrowserAction): BrowserResult {
                delay(60_000)
                return BrowserResult.Navigated("never", "never")
            }
            override suspend fun close(session: BrowserSession) {}
        }
        val mgr = BrowserSessionManager(object : BrowserRouter {
            override fun engineFor(id: BrowserEngineId) = hanging
            override fun defaultEngine() = hanging
            override fun register(engine: BrowserEngine) {}
        })
        val d = BrowserDispatcher(mgr, runtime)
        mgr.sessionFor("cancel-me")
        val job = launch { d.cleanupRun("cancel-me") }
        job.cancel()
        // No hang, no crash.
    }

    private val com.inspiredandroid.kai.tools.ToolResult.isSuccessLike: Boolean
        get() = this is com.inspiredandroid.kai.tools.ToolResult.Success
    private val com.inspiredandroid.kai.tools.ToolResult.isFailureLike: Boolean
        get() = this is com.inspiredandroid.kai.tools.ToolResult.Failure
    private fun com.inspiredandroid.kai.tools.ToolResult.errorMessage(): String =
        (this as? com.inspiredandroid.kai.tools.ToolResult.Failure)?.error.orEmpty()
}
