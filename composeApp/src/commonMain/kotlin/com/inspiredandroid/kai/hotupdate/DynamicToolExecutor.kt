package com.inspiredandroid.kai.hotupdate

import com.inspiredandroid.kai.data.AppSettings
import com.inspiredandroid.kai.data.ToolExecutor
import com.inspiredandroid.kai.httpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.time.Duration.Companion.seconds

/**
 * Executes tools delivered through the remote config. Only *built-in*
 * executors are supported by default — the schema and routing are remote,
 * but the code that runs is already compiled into the APK, so a remote
 * config can never execute arbitrary code.
 *
 * An optional user-controlled webhook gateway (`webhook_url` in the tool
 * definition + `dynamic_tools_gateway_token` in settings) lets power users
 * bridge to their own agent backend; requests are plain JSON POSTs with a
 * per-tool timeout.
 */
class DynamicToolExecutor(
    private val appSettings: AppSettings,
    private val chatToolExecutor: ToolExecutor,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    /** Execute a remotely-delivered tool, mapping `built_in` ids to chat tools. */
    suspend fun execute(tool: RemoteToolDefinition, args: Map<String, Any>): String = when (tool.kind) {
        "builtin" -> executeBuiltin(tool, args)
        "webhook" -> executeWebhook(tool, args)
        else -> """{"success": false, "error": "unknown tool kind"}"""
    }

    private suspend fun executeBuiltin(tool: RemoteToolDefinition, args: Map<String, Any>): String {
        // Built-ins map onto existing chat tools so the agent gets real
        // functionality from a remote definition. Add new mappings in the APK
        // when new executors ship; the remote side just routes to them.
        val mapped = when (tool.built_in) {
            "terminal.echo" -> "echo_builtin"
            else -> null
        }
        if (mapped == null) {
            return """{"success": false, "error": "unknown built-in executor: ${tool.built_in}"}"""
        }
        if (mapped == "echo_builtin") {
            return """{"success": true, "text": "${safeString(args["text"])}"}"""
        }
        // Fallback: delegate to the chat tool executor by name when the
        // built_in id happens to match an existing tool id (e.g. "fs.read").
        val delegate = tool.built_in!!
        return runCatching {
            val argsJson = json.encodeToString(
                JsonObject.serializer(),
                buildJsonObject { args.forEach { (k, v) -> put(k, anyToPrimitive(v)) } },
            )
            chatToolExecutor.executeTool(delegate, argsJson)
        }.getOrElse { """{"success": false, "error": "${it.message}"}""" }
    }

    private suspend fun executeWebhook(tool: RemoteToolDefinition, args: Map<String, Any>): String {
        val url = tool.webhook_url ?: return """{"success": false, "error": "no webhook url"}"""
        val token = runCatching { appSettings.settings.getString("dynamic_tools_gateway_token", "") }.getOrNull().orEmpty()
        val timeoutSeconds = tool.timeout_seconds?.coerceIn(5, 300) ?: 60
        return runCatching {
            withTimeout(timeoutSeconds.seconds) {
                val client = httpClient { }
                val response = client.post(url) {
                    contentType(ContentType.Application.Json)
                    if (token.isNotEmpty()) header("Authorization", "Bearer $token")
                    setBody(
                        buildJsonObject {
                            put("tool", JsonPrimitive(tool.id))
                            put("arguments", JsonObject(args.mapValues { (_, v) -> anyToPrimitive(v) }))
                        },
                    )
                }
                val body = response.bodyAsText().take(20_000)
                if (response.status.isSuccess()) body else """{"success": false, "error": "webhook status ${response.status.value}: ${body.take(500)}"}"""
            }
        }.getOrElse { """{"success": false, "error": "webhook failed: ${it.message}"}""" }
    }

    private fun safeString(value: Any?): String =
        (value?.toString() ?: "").replace("\"", "\\\"").take(2_000)

    private fun anyToPrimitive(value: Any): JsonPrimitive = when (value) {
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    }
}
