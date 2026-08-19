package com.inspiredandroid.kai.browser

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.seconds

/**
 * Settings for a Lightpanda browser gateway (or any MCP-HTTP compatible
 * browser service).
 *
 * Security notes:
 * - The base URL is validated by [SsrfGuard] before use.
 * - Sessions use an opaque [Mcp-Session-Id] per agent run (never shared).
 * - Credentials never flow through page content to the LLM — only the
 *   converted Markdown / semantic tree does.
 */
data class LightpandaGatewayConfig(
    val baseUrl: String,
    val apiKey: String? = null,
    val timeoutSeconds: Long = 60L,
)

/**
 * A replaceable Lightpanda backend — talks to a MCP-HTTP (JSON-RPC 2.0)
 * browser service such as the official Lightpanda browser. This is a
 * REFERENCE implementation: no binary or source is bundled with the app,
 * and the runtime only ever sees the [BrowserEngine] contract.
 *
 * Protocol (docs/SANDBOX_GATEWAY_PROTOCOL.md sibling — browser):
 * POST {baseUrl}/mcp with JSON-RPC 2.0 envelopes, method names mapping to
 * browser actions. Session isolation is enforced by a fresh Mcp-Session-Id
 * header per run.
 */
class LightpandaGatewayBackend(
    private val config: LightpandaGatewayConfig,
    private val httpClient: HttpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    },
    private val systemClock: () -> Long = System::currentTimeMillis,
) : BrowserEngine {

    override val id: BrowserEngineId = BrowserEngineId("lightpanda-gateway")

    override suspend fun openSession(runId: String): BrowserSession {
        SsrfGuard.isBlocked(config.baseUrl)?.let { throw IllegalStateException("Gateway URL blocked: $it") }
        val sessionId = "run-$runId-${systemClock()}"
        return BrowserSession(sessionId = sessionId, engineId = id, runId = runId)
    }

    override suspend fun execute(session: BrowserSession, action: BrowserAction): BrowserResult = try {
        withTimeout((config.timeoutSeconds).seconds) {
            val (method, params) = actionToRpc(action)
            val envelope = buildJsonObject {
                put("jsonrpc", kotlinx.serialization.json.JsonPrimitive("2.0"))
                put("id", kotlinx.serialization.json.JsonPrimitive("${session.sessionId}-${systemClock()}"))
                put("method", kotlinx.serialization.json.JsonPrimitive(method))
                if (params != null) put("params", params)
            }
            val response = httpClient.post("${config.baseUrl.trimEnd('/')}/mcp") {
                header("Mcp-Session-Id", session.sessionId)
                config.apiKey?.let { header("Authorization", "Bearer $it") }
                contentType(ContentType.Application.Json)
                setBody(envelope.toString())
            }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                return@withTimeout BrowserResult.Failed("gateway HTTP ${response.status.value}: ${body.take(200)}", retryable = true)
            }
            parseRpcResult(body)
        }
    } catch (ce: CancellationException) {
        throw ce
    } catch (e: Exception) {
        BrowserResult.Failed(e.message ?: e::class.simpleName ?: "gateway error", retryable = true)
    }

    override suspend fun close(session: BrowserSession) {
        try {
            httpClient.post("${config.baseUrl.trimEnd('/')}/mcp") {
                header("Mcp-Session-Id", session.sessionId)
                config.apiKey?.let { header("Authorization", "Bearer $it") }
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("jsonrpc", kotlinx.serialization.json.JsonPrimitive("2.0"))
                    put("id", kotlinx.serialization.json.JsonPrimitive("${session.sessionId}-close"))
                    put("method", kotlinx.serialization.json.JsonPrimitive("sessions.close"))
                }.toString())
            }
        } catch (_: Exception) {
            // Cleanup is best-effort; the run lifecycle already discards the session.
        }
    }

    // ---------- RPC mapping ----------

    private fun actionToRpc(action: BrowserAction): Pair<String, kotlinx.serialization.json.JsonObject?> = when (action) {
        is BrowserAction.Open -> "browser.open" to buildJsonObject {
            put("url", kotlinx.serialization.json.JsonPrimitive(action.url))
            put("timeout", kotlinx.serialization.json.JsonPrimitive(action.timeoutMs))
        }
        is BrowserAction.Read -> "browser.read" to buildJsonObject {
            put("format", kotlinx.serialization.json.JsonPrimitive(action.format.name.lowercase()))
        }
        is BrowserAction.Click -> "browser.click" to buildJsonObject {
            put("target_id", kotlinx.serialization.json.JsonPrimitive(action.targetId))
        }
        is BrowserAction.TypeText -> "browser.type" to buildJsonObject {
            put("target_id", kotlinx.serialization.json.JsonPrimitive(action.targetId))
            put("text", kotlinx.serialization.json.JsonPrimitive(action.text))
            put("submit", kotlinx.serialization.json.JsonPrimitive(action.submit))
        }
        is BrowserAction.Back -> "browser.back" to null
        is BrowserAction.Extract -> "browser.extract" to buildJsonObject {
            if (action.query != null) put("query", kotlinx.serialization.json.JsonPrimitive(action.query))
        }
        is BrowserAction.Close -> "browser.close" to null
    }

    private fun parseRpcResult(body: String): BrowserResult = try {
        val tree = JSON.parseToJsonElement(body).let {
            it as? kotlinx.serialization.json.JsonObject
        } ?: return BrowserResult.Failed("unexpected response shape", retryable = true)
        val result = tree["result"] as? kotlinx.serialization.json.JsonObject
        val error = tree["error"] as? kotlinx.serialization.json.JsonObject
        if (error != null) {
            val message = (error["message"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "gateway error"
            return BrowserResult.Failed(message, retryable = true)
        }
        if (result == null) return BrowserResult.Failed("missing result in response", retryable = true)
        val kind = (result["kind"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        return when (kind) {
            "navigated" -> BrowserResult.Navigated(
                (result["url"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty(),
                (result["title"] as? kotlinx.serialization.json.JsonPrimitive)?.content,
            )
            "read" -> BrowserResult.Read(CdpPageModel.Markdown(
                (result["content"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty(),
                (result["url"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty(),
            ))
            "clicked" -> BrowserResult.Clicked(
                (result["target_id"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty(),
                (result["url"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty(),
            )
            "typed" -> BrowserResult.Typed(
                (result["target_id"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty(),
                (result["submitted"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false,
            )
            "back" -> BrowserResult.Back(
                (result["url"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty(),
                (result["title"] as? kotlinx.serialization.json.JsonPrimitive)?.content,
            )
            "extracted" -> BrowserResult.Extracted((result["content"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty())
            "closed" -> BrowserResult.Closed
            else -> BrowserResult.Failed("unknown result kind: $kind", retryable = false)
        }
    } catch (_: Exception) {
        BrowserResult.Failed("failed to parse gateway response", retryable = true)
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}
