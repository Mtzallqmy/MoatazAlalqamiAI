package com.inspiredandroid.kai.hotupdate

import com.inspiredandroid.kai.data.AppSettings
import com.inspiredandroid.kai.data.ToolExecutor
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DynamicToolExecutorTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val settings = MapSettings()
    private val appSettings = AppSettings(settings)

    private val echoTool = RemoteToolDefinition(
        id = "custom.echo",
        name = "echo",
        description = "returns the given text",
        parameters = mapOf("text" to RemoteParameter("string", "the text to echo", true)),
        kind = "builtin",
        built_in = "terminal.echo",
    )

    private val delegateTool = RemoteToolDefinition(
        id = "custom.fs_read",
        name = "fs_read_alias",
        description = "delegates to fs.read",
        parameters = mapOf("path" to RemoteParameter("string", "file path", true)),
        kind = "builtin",
        built_in = "fs.read",
    )

    private val unknownBuiltin = RemoteToolDefinition(
        id = "custom.unknown",
        name = "unknown_exec",
        description = "no such executor",
        parameters = emptyMap(),
        kind = "builtin",
        built_in = "no.such.executor",
    )

    private val webhookTool = RemoteToolDefinition(
        id = "custom.web",
        name = "web_hook",
        description = "posts to user gateway",
        parameters = mapOf("q" to RemoteParameter("string", "query", true)),
        kind = "webhook",
        webhook_url = "https://gateway.example.com/hook",
        timeout_seconds = 10,
    )

    @Test
    fun `builtin echo executes with injected args`() = runBlocking {
        val executor = DynamicToolExecutor(appSettings, ToolExecutor())
        val result = executor.execute(echoTool, mapOf("text" to "hello world"))
        assertTrue(result.contains("hello world"), "expected echoed text in: $result")
    }

    @Test
    fun `unknown built-in executor returns failure`() = runBlocking {
        val executor = DynamicToolExecutor(appSettings, ToolExecutor())
        val result = executor.execute(unknownBuiltin, emptyMap())
        assertTrue(result.contains("unknown built-in executor"), "expected failure in: $result")
    }

    @Test
    fun `tool executor routes unknown chat tools to dynamic definitions`() = runBlocking {
        val chatExecutor = ToolExecutor(toolsProvider = { emptyList() })
        val toolExecutor = ToolExecutor(
            toolsProvider = { emptyList() },
            dynamicToolProvider = { listOf(echoTool) },
            dynamicExecutor = { DynamicToolExecutor(appSettings, chatExecutor) },
        )
        val result = toolExecutor.executeTool("custom.echo", """{"text":"via executor"}""")
        assertTrue(result.contains("via executor"), "expected routed result in: $result")
    }

    @Test
    fun `fallback to chat-tool delegate when built_in matches an existing tool`() = runBlocking {
        val chatExecutor = ToolExecutor(toolsProvider = { emptyList() })
        val toolExecutor = ToolExecutor(
            toolsProvider = { emptyList() },
            dynamicToolProvider = { listOf(delegateTool) },
            dynamicExecutor = { DynamicToolExecutor(appSettings, chatExecutor) },
        )
        // fs.read is not in the empty provider, so the delegate path should
        // surface the built-in executor's own "unknown tool" failure rather
        // than crash — proving delegation is attempted.
        val result = toolExecutor.executeTool("custom.fs_read_alias", """{"path":"/x"}""")
        assertTrue(result.contains("fs.read") || result.contains("Unknown tool"), "got: $result")
    }

    @Test
    fun `full document round trip through ToolExecutor with dynamic tools`() = runBlocking {
        val document = """
        {
          "version": 1,
          "feature_flags": {"dynamic_tools": true},
          "dynamic_tools": [
            {
              "id": "custom.echo",
              "name": "echo",
              "description": "echoes",
              "parameters": {"text": {"type": "string", "description": "the text", "required": true}},
              "kind": "builtin",
              "built_in": "terminal.echo"
            }
          ]
        }
        """.trimIndent()
        val remote = json.decodeFromString(RemoteConfig.serializer(), document)
        val validated = remote.validated().getOrNull()!!

        val chatExecutor = ToolExecutor(toolsProvider = { emptyList() })
        val toolExecutor = ToolExecutor(
            toolsProvider = { emptyList() },
            dynamicToolProvider = { validated.dynamic_tools },
            dynamicExecutor = { DynamicToolExecutor(appSettings, chatExecutor) },
        )
        val result = toolExecutor.executeTool("custom.echo", """{"text":"round trip"}""")
        assertTrue(result.contains("round trip"), "expected routed result in: $result")
    }

    @Test
    fun `webhook tool fails safely without network`() = runBlocking {
        val executor = DynamicToolExecutor(appSettings, ToolExecutor())
        // With a real unreachable URL the executor must return a failure JSON,
        // never throw or hang (timeout is capped at 10s in the test fixture —
        // we cap it lower here via a short-timeout definition).
        val shortTool = webhookTool.copy(timeout_seconds = 5)
        val result = executor.execute(shortTool, mapOf("q" to "anything"))
        assertTrue(result.contains("success\": false") || result.contains("webhook failed"), "got: $result")
    }
}
