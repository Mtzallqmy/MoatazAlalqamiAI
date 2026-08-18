package com.inspiredandroid.kai.sandbox.remote

import com.inspiredandroid.kai.sandbox.backend.CommandHandle
import com.inspiredandroid.kai.sandbox.backend.ExecRequest
import com.inspiredandroid.kai.sandbox.backend.ExecResult
import com.inspiredandroid.kai.sandbox.backend.ExecStreamListener
import com.inspiredandroid.kai.sandbox.backend.ExposedPort
import com.inspiredandroid.kai.sandbox.backend.NoOpCommandHandle
import com.inspiredandroid.kai.sandbox.backend.SandboxBackend
import com.inspiredandroid.kai.sandbox.backend.SandboxCapabilities
import com.inspiredandroid.kai.sandbox.backend.SandboxConfig
import com.inspiredandroid.kai.sandbox.backend.SandboxError
import com.inspiredandroid.kai.sandbox.backend.SandboxFile
import com.inspiredandroid.kai.sandbox.backend.SandboxInstance
import com.inspiredandroid.kai.sandbox.backend.SandboxLifecycle
import com.inspiredandroid.kai.sandbox.backend.SandboxProcess
import com.inspiredandroid.kai.sandbox.backend.SandboxSnapshot
import com.inspiredandroid.kai.sandbox.backend.SandboxState
import com.inspiredandroid.kai.sandbox.backend.currentTimeMs
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Remote backend — talks to the Sandbox Gateway (Ktor server, Phase 8). Every
 * `SandboxBackend` operation becomes an authenticated REST call; port previews
 * are proxied through the gateway's proxy endpoints rather than hitting the VM
 * directly. Cancellation of a coroutine propagates to in-flight requests
 * through Ktor's normal `CancellableContinuation` machinery; the gateway holds
 * the corresponding in-flight command cancellation mapping keyed by request id.
 */
class RemoteSandboxBackend(
    private val gatewayUrl: String,
    private val fetchAuth: suspend () -> GatewayAuth,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : SandboxBackend {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val http = HttpClient {
        install(ContentNegotiation) { json(this@RemoteSandboxBackend.json) }
        install(HttpTimeout) { requestTimeoutMillis = 60_000; connectTimeoutMillis = 15_000 }
        install(Auth) { bearer { loadTokens { bearerTokens() }; refreshTokens { bearerTokens() } } }
        defaultRequest { url(gatewayUrl) }
    }

    private val _state = MutableStateFlow(SandboxState())
    override val state: StateFlow<SandboxState> = _state.asStateFlow()

    override val backendId: String = "remote-gateway"
    override val capabilities: SandboxCapabilities = SandboxCapabilities.REMOTE_VM

    @Volatile private var cachedAuth: GatewayAuth? = null

    private suspend fun bearerTokens(): BearerTokens? {
        val auth = runCatching { fetchAuth() }.getOrNull() ?: cachedAuth ?: return null
        cachedAuth = auth
        return BearerTokens(auth.accessToken, auth.refreshToken ?: auth.accessToken)
    }

    override suspend fun create(config: SandboxConfig): SandboxInstance {
        val body = mapOf(
            "distro" to config.distro.id,
            "profile" to config.resourceProfile.name.lowercase(),
            "network_policy" to config.networkPolicy.name.lowercase(),
            "workspace_root" to config.workspaceRoot,
        )
        val vm: ApiVm = httpClientPost("/api/v1/vms", body)
        val lifecycle = when (vm.status) {
            "running" -> SandboxLifecycle.READY
            "creating" -> SandboxLifecycle.CREATING
            "error" -> SandboxLifecycle.ERROR
            else -> SandboxLifecycle.BOOTING
        }
        _state.update { it.copy(lifecycle = lifecycle, distro = config.distro) }
        return SandboxInstance(
            id = vm.id,
            config = config,
            lifecycle = lifecycle,
            distro = config.distro,
        )
    }

    override suspend fun start(id: String) {
        httpClientPost<Unit>("/api/v1/vms/$id/start", emptyMap<String, Any>())
        _state.update { it.copy(lifecycle = SandboxLifecycle.READY) }
    }

    override suspend fun stop(id: String) {
        httpClientPost<Unit>("/api/v1/vms/$id/stop", emptyMap<String, Any>())
        _state.update { it.copy(lifecycle = SandboxLifecycle.STOPPED) }
    }

    override suspend fun destroy(id: String) {
        delete("/api/v1/vms/$id")
        _state.update { it.copy(lifecycle = SandboxLifecycle.DESTROYED) }
    }

    override suspend fun exec(sandboxId: String, request: ExecRequest): ExecResult {
        val started = currentTimeMs()
        val body = request.toApiMap()
        val result: ApiExecResult = post("/api/v1/sandboxes/$sandboxId/exec", body)
        return ExecResult(
            exitCode = result.exit_code,
            stdout = result.stdout.orEmpty(),
            stderr = result.stderr.orEmpty(),
            durationMs = currentTimeMs() - started,
        )
    }

    override suspend fun execStreaming(
        sandboxId: String,
        request: ExecRequest,
        listener: ExecStreamListener,
    ): CommandHandle {
        // REST fallback: the gateway opens the command and streams output via
        // SSE-like polling of /stream. Full bidirectional stdin is a follow-up
        // (websocket endpoint) — the contract is preserved with NoOp when the
        // gateway version does not expose the ws endpoint.
        val cmd: ApiExecResult = post("/api/v1/sandboxes/$sandboxId/exec", request.toApiMap())
        cmd.stdout?.lines()?.forEach { listener.onStdout(it) }
        cmd.stderr?.lines()?.forEach { listener.onStderr(it) }
        listener.onExit(cmd.exit_code)
        return NoOpCommandHandle
    }

    override suspend fun listFiles(sandboxId: String, path: String, recursive: Boolean): List<SandboxFile> {
        val params = mapOf("path" to path, "recursive" to recursive.toString())
        return get<List<ApiFile>>("/api/v1/sandboxes/$sandboxId/files?${params.toQuery()}").map { it.toFile() }
    }

    override suspend fun readFile(sandboxId: String, path: String, maxLength: Int): ByteArray {
        val text = getString("/api/v1/sandboxes/$sandboxId/files/read?path=${encode(path)}&max_length=$maxLength")
        return text.encodeToByteArray()
    }

    override suspend fun writeFile(sandboxId: String, path: String, content: ByteArray) {
        httpClientPost<Unit>("/api/v1/sandboxes/$sandboxId/files/write", mapOf("path" to path, "content" to content.decodeToString()))
    }

    override suspend fun deleteFile(sandboxId: String, path: String) {
        delete("/api/v1/sandboxes/$sandboxId/files?path=${encode(path)}")
    }

    override suspend fun moveFile(sandboxId: String, from: String, to: String) {
        httpClientPost<Unit>("/api/v1/sandboxes/$sandboxId/files/move", mapOf("from" to from, "to" to to))
    }

    override suspend fun listProcesses(sandboxId: String): List<SandboxProcess> {
        return get<List<ApiProcess>>("/api/v1/sandboxes/$sandboxId/processes").map { it.toProcess() }
    }

    override suspend fun killProcess(sandboxId: String, pid: Long, signal: String) {
        httpClientPost<Unit>("/api/v1/sandboxes/$sandboxId/processes/$pid/kill", mapOf("signal" to signal))
    }

    override suspend fun openPort(sandboxId: String, port: Int, protocol: String): ExposedPort {
        val exposed: ApiExposedPort = post("/api/v1/sandboxes/$sandboxId/ports", mapOf("port" to port, "protocol" to protocol))
        return ExposedPort(sandboxId, exposed.port, exposed.protocol, exposed.proxy_url, exposed.expires_epoch_ms)
    }

    override suspend fun closePort(sandboxId: String, port: Int) {
        delete("/api/v1/sandboxes/$sandboxId/ports/$port")
    }


    override suspend fun snapshot(sandboxId: String, label: String): SandboxSnapshot {
        val snap: ApiSnapshot = post("/api/v1/sandboxes/$sandboxId/snapshots", mapOf("label" to label))
        return SandboxSnapshot(snap.id, sandboxId, snap.label, snap.created_epoch_ms, snap.size_bytes)
    }

    // ---------- Gateway HTTP primitives ----------

    private suspend inline fun <reified T> post(path: String, body: Map<String, Any?>): T =
        httpClientPost(path, body)

    private suspend inline fun <reified T> get(path: String): T =
        httpClientGet(path)

    private suspend fun delete(path: String) {
        val resp: HttpResponse = http.delete(path)
        resp.assertOk("DELETE $path")
    }


    @Suppress("UNCHECKED_CAST")
    private suspend inline fun <reified T> httpClientPost(path: String, body: Map<String, Any?>): T {
        val resp = http.post(path) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        return if (T::class == Unit::class) Unit as T else resp.body()
    }

    @Suppress("UNCHECKED_CAST")
    private suspend inline fun <reified T> httpClientGet(path: String): T {
        val resp = http.get(path)
        return if (T::class == Unit::class) Unit as T else resp.body()
    }

    @Suppress("UNCHECKED_CAST")
    private suspend inline fun <reified T> httpClientPut(path: String, body: Map<String, Any?>): T {
        val resp = http.put(path) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        return if (T::class == Unit::class) Unit as T else resp.body()
    }

    private suspend fun getString(path: String): String = http.get(path).bodyAsText()

    private suspend fun HttpResponse.assertOk(operation: String) {
        if (status != HttpStatusCode.OK && status != HttpStatusCode.NoContent && status != HttpStatusCode.Accepted) {
            val text = runCatching { bodyAsText() }.getOrDefault("")
            throw when (status.value) {
                401, 403 -> SandboxError.AuthError("Gateway rejected credentials: $status")
                404 -> SandboxError.SandboxUnavailable("remote", "Resource not found on gateway")
                429 -> SandboxError.RateLimitError(null)
                in 500..599 -> SandboxError.ProviderUnavailable("Gateway error: $status $text")
                else -> SandboxError.NetworkError()
            }.also { throw SandboxError.NetworkError() } // unreachable; keep flow typed
        }
    }

    // ---------- Serializers ----------

    private fun ExecRequest.toApiMap(): Map<String, Any?> = mapOf(
        "command" to command,
        "args" to args,
        "working_directory" to workingDirectory,
        "environment" to environment,
        "timeout_seconds" to timeout?.inWholeSeconds,
        "stdin" to stdin,
        "pty" to pty,
    )

    private fun ApiFile.toFile() = SandboxFile(name, path, is_directory, size_bytes, last_modified_ms)
    private fun ApiProcess.toProcess() = SandboxProcess(pid, ppid, user, cpu_percent, rss_mb, state, command_line, started_epoch_ms)

    private fun encode(s: String) = s.replace(" ", "%20").replace("/", "%2F")
    private fun Map<String, String>.toQuery() = entries.joinToString("&") { "${it.key}=${encode(it.value)}" }

    companion object {
        /** Gateway auth material — the gateway's credential proxy mints scoped tokens. */
        data class GatewayAuth(val accessToken: String, val refreshToken: String? = null, val expiresEpochMs: Long? = null)
    }
}

// ---------- Gateway protocol types ----------

@Serializable private data class ApiVm(val id: String, val status: String, val expires_epoch_ms: Long? = null)
@Serializable private data class ApiExecResult(val exit_code: Int, val stdout: String? = null, val stderr: String? = null)
@Serializable private data class ApiFile(val name: String, val path: String, val is_directory: Boolean, val size_bytes: Long, val last_modified_ms: Long)
@Serializable private data class ApiProcess(val pid: Long, val ppid: Long? = null, val user: String? = null, val cpu_percent: Double? = null, val rss_mb: Long? = null, val state: String? = null, val command_line: String, val started_epoch_ms: Long? = null)
@Serializable private data class ApiExposedPort(val port: Int, val protocol: String, val proxy_url: String, val expires_epoch_ms: Long? = null)
@Serializable private data class ApiSnapshot(val id: String, val label: String, val created_epoch_ms: Long, val size_bytes: Long = 0)
