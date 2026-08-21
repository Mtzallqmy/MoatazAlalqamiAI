package com.inspiredandroid.kai.sandbox.remote

import com.inspiredandroid.kai.sandbox.backend.CommandHandle
import com.inspiredandroid.kai.sandbox.backend.ExecRequest
import com.inspiredandroid.kai.sandbox.backend.ExecResult
import com.inspiredandroid.kai.sandbox.backend.ExecStreamListener
import com.inspiredandroid.kai.sandbox.backend.ExposedPort
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
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
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
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64

/**
 * Remote backend — talks to the Sandbox Gateway (Ktor server, Phase 8). Every
 * `SandboxBackend` operation becomes an authenticated REST call; port previews
 * are proxied through the gateway's proxy endpoints rather than hitting the VM
 * directly. Interactive WebSocket execution is deliberately not advertised
 * until a gateway implements and proves that protocol.
 */
class RemoteSandboxBackend(
    private val gatewayUrl: String,
    private val fetchAuth: suspend () -> GatewayAuth,
    @Suppress("UNUSED_PARAMETER")
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    engine: HttpClientEngine? = null,
    private val nowEpochMs: () -> Long = { currentTimeMs() },
) : SandboxBackend {

    init {
        val parsed = runCatching { Url(gatewayUrl) }.getOrElse {
            throw SandboxError.ConfigurationError("gatewayUrl", "must be an absolute HTTPS URL")
        }
        if (parsed.protocol.name != "https") {
            throw SandboxError.ConfigurationError("gatewayUrl", "HTTPS is required")
        }
        val authority = gatewayUrl.removePrefix("https://").substringBefore('/')
        if (authority.isBlank() || '@' in authority || '?' in gatewayUrl || '#' in gatewayUrl) {
            throw SandboxError.ConfigurationError("gatewayUrl", "credentials, query and fragment are not allowed")
        }
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val http = if (engine == null) HttpClient {
        install(ContentNegotiation) { json(this@RemoteSandboxBackend.json) }
        install(HttpTimeout) { requestTimeoutMillis = 60_000; connectTimeoutMillis = 15_000 }
    } else HttpClient(engine) {
        install(ContentNegotiation) { json(this@RemoteSandboxBackend.json) }
        install(HttpTimeout) { requestTimeoutMillis = 60_000; connectTimeoutMillis = 15_000 }
    }

    private val _state = MutableStateFlow(SandboxState())
    override val state: StateFlow<SandboxState> = _state.asStateFlow()

    override val backendId: String = "remote-gateway"
    override val capabilities: SandboxCapabilities = SandboxCapabilities(
        exec = true,
        streamingExec = false,
        filesystem = true,
        fileSearch = true,
        processControl = true,
        portExposure = true,
        snapshots = true,
        idleTimeout = false,
        maxLifetime = false,
        networkPolicy = false,
    )

    @Volatile private var cachedAuth: GatewayAuth? = null

    private suspend fun validAccessToken(): String {
        val refreshSkewMs = 30_000L
        cachedAuth?.takeIf { it.expiresEpochMs != null && it.expiresEpochMs > nowEpochMs() + refreshSkewMs }
            ?.let { return it.accessToken }
        val auth = runCatching { fetchAuth() }
            .getOrElse { throw SandboxError.AuthError("Unable to obtain a gateway token") }
        val expires = auth.expiresEpochMs
        if (
            auth.accessToken.isBlank() ||
            expires == null ||
            expires <= nowEpochMs() ||
            expires > nowEpochMs() + MAX_TOKEN_LIFETIME_MS
        ) {
            cachedAuth = null
            throw SandboxError.AuthError("Gateway token must be non-empty, short-lived and unexpired")
        }
        cachedAuth = auth
        return auth.accessToken
    }

    override suspend fun create(config: SandboxConfig): SandboxInstance {
        val body = RemoteCreateVmRequest(
            distro = config.distro.id,
            profile = config.resourceProfile.name.lowercase(),
            network_policy = config.networkPolicy.name.lowercase(),
            workspace_root = config.workspaceRoot,
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
        httpClientPost<Unit, RemoteActionRequest>("/api/v1/vms/${id.pathSegment()}/start", RemoteActionRequest())
        _state.update { it.copy(lifecycle = SandboxLifecycle.READY) }
    }

    override suspend fun stop(id: String) {
        httpClientPost<Unit, RemoteActionRequest>("/api/v1/vms/${id.pathSegment()}/stop", RemoteActionRequest())
        _state.update { it.copy(lifecycle = SandboxLifecycle.STOPPED) }
    }

    override suspend fun destroy(id: String) {
        delete("/api/v1/vms/${id.pathSegment()}")
        _state.update { it.copy(lifecycle = SandboxLifecycle.DESTROYED) }
    }

    override suspend fun exec(sandboxId: String, request: ExecRequest): ExecResult {
        val started = currentTimeMs()
        val result: RemoteExecResult = post("/api/v1/sandboxes/${sandboxId.pathSegment()}/exec", request.toRemoteRequest())
        result.requireCurrentSchema()
        requireOutputWithinLimit(result)
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
        throw SandboxError.ProviderUnavailable(
            "Remote interactive execution requires the versioned WebSocket gateway contract",
        )
    }

    override suspend fun listFiles(sandboxId: String, path: String, recursive: Boolean): List<SandboxFile> {
        val params = mapOf("path" to path, "recursive" to recursive.toString())
        return get<List<ApiFile>>("/api/v1/sandboxes/${sandboxId.pathSegment()}/files?${params.toQuery()}").map { it.toFile() }
    }

    override suspend fun readFile(sandboxId: String, path: String, maxLength: Int): ByteArray {
        require(maxLength in 0..RemoteSandboxProtocol.MAX_FILE_BYTES) { "maxLength is outside the supported range" }
        val payload = get<RemoteFileContent>(
            "/api/v1/sandboxes/${sandboxId.pathSegment()}/files/read?path=${path.queryComponent()}&max_length=$maxLength",
            responseLimit = encodedSizeLimit(maxLength),
        )
        payload.requireCurrentSchema()
        val bytes = runCatching { Base64.decode(payload.content_base64) }
            .getOrElse { throw SandboxError.ProviderUnavailable("Gateway returned invalid file encoding") }
        if (bytes.size > maxLength) throw SandboxError.SandboxResourceLimit("file bytes", bytes.size.toLong(), maxLength.toLong())
        return bytes
    }

    override suspend fun writeFile(sandboxId: String, path: String, content: ByteArray) {
        require(content.size <= RemoteSandboxProtocol.MAX_FILE_BYTES) { "file exceeds remote write limit" }
        httpClientPost<Unit, RemoteWriteFileRequest>(
            "/api/v1/sandboxes/${sandboxId.pathSegment()}/files/write",
            RemoteWriteFileRequest(path = path, content_base64 = Base64.encode(content)),
        )
    }

    override suspend fun deleteFile(sandboxId: String, path: String) {
        delete("/api/v1/sandboxes/${sandboxId.pathSegment()}/files?path=${path.queryComponent()}")
    }

    override suspend fun moveFile(sandboxId: String, from: String, to: String) {
        httpClientPost<Unit, RemoteMoveFileRequest>(
            "/api/v1/sandboxes/${sandboxId.pathSegment()}/files/move",
            RemoteMoveFileRequest(from = from, to = to),
        )
    }

    override suspend fun listProcesses(sandboxId: String): List<SandboxProcess> {
        return get<List<ApiProcess>>("/api/v1/sandboxes/${sandboxId.pathSegment()}/processes").map { it.toProcess() }
    }

    override suspend fun killProcess(sandboxId: String, pid: Long, signal: String) {
        httpClientPost<Unit, RemoteSignalRequest>(
            "/api/v1/sandboxes/${sandboxId.pathSegment()}/processes/$pid/kill",
            RemoteSignalRequest(signal = signal),
        )
    }

    override suspend fun openPort(sandboxId: String, port: Int, protocol: String): ExposedPort {
        val exposed: ApiExposedPort = post(
            "/api/v1/sandboxes/${sandboxId.pathSegment()}/ports",
            RemoteOpenPortRequest(port = port, protocol = protocol),
        )
        return ExposedPort(sandboxId, exposed.port, exposed.protocol, exposed.proxy_url, exposed.expires_epoch_ms)
    }

    override suspend fun closePort(sandboxId: String, port: Int) {
        delete("/api/v1/sandboxes/${sandboxId.pathSegment()}/ports/$port")
    }


    override suspend fun snapshot(sandboxId: String, label: String): SandboxSnapshot {
        val snap: ApiSnapshot = post(
            "/api/v1/sandboxes/${sandboxId.pathSegment()}/snapshots",
            RemoteSnapshotRequest(label = label),
        )
        return SandboxSnapshot(snap.id, sandboxId, snap.label, snap.created_epoch_ms, snap.size_bytes)
    }

    // ---------- Gateway HTTP primitives ----------

    private suspend inline fun <reified T, reified B> post(path: String, body: B): T =
        httpClientPost(path, body)

    private suspend inline fun <reified T> get(path: String, responseLimit: Int = RemoteSandboxProtocol.MAX_RESPONSE_BYTES): T =
        httpClientGet(path, responseLimit)

    private suspend fun delete(path: String) {
        val token = validAccessToken()
        val resp: HttpResponse = http.delete(requestUrl(path)) {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        resp.assertOk("DELETE $path")
    }


    @Suppress("UNCHECKED_CAST")
    private suspend inline fun <reified T, reified B> httpClientPost(path: String, body: B): T {
        val token = validAccessToken()
        val resp = http.post(requestUrl(path)) {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        return resp.decodeChecked("POST $path")
    }

    @Suppress("UNCHECKED_CAST")
    private suspend inline fun <reified T> httpClientGet(path: String, responseLimit: Int): T {
        val token = validAccessToken()
        val resp = http.get(requestUrl(path)) {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        return resp.decodeChecked("GET $path", responseLimit)
    }

    @Suppress("UNCHECKED_CAST")
    private suspend inline fun <reified T> HttpResponse.decodeChecked(
        operation: String,
        responseLimit: Int = RemoteSandboxProtocol.MAX_RESPONSE_BYTES,
    ): T {
        assertOk(operation)
        if (T::class == Unit::class) return Unit as T
        val declared = headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (declared != null && declared > responseLimit) {
            throw SandboxError.SandboxResourceLimit("gateway response bytes", declared, responseLimit.toLong())
        }
        val text = bodyAsText()
        val actual = text.encodeToByteArray().size
        if (actual > responseLimit) {
            throw SandboxError.SandboxResourceLimit("gateway response bytes", actual.toLong(), responseLimit.toLong())
        }
        return runCatching { json.decodeFromString<T>(text) }
            .getOrElse { throw SandboxError.ProviderUnavailable("Gateway returned an invalid $operation response") }
    }

    private suspend fun HttpResponse.assertOk(operation: String) {
        if (status.value !in 200..299) {
            throw when (status.value) {
                401, 403 -> SandboxError.AuthError("Gateway rejected credentials: $status")
                404 -> SandboxError.SandboxUnavailable("remote", "Resource not found on gateway")
                429 -> SandboxError.RateLimitError(null)
                in 500..599 -> SandboxError.ProviderUnavailable("Gateway error: $status")
                else -> SandboxError.NetworkError()
            }
        }
    }

    // ---------- Serializers ----------

    private fun ApiFile.toFile() = SandboxFile(name, path, is_directory, size_bytes, last_modified_ms)
    private fun ApiProcess.toProcess() = SandboxProcess(pid, ppid, user, cpu_percent, rss_mb, state, command_line, started_epoch_ms)

    private fun requestUrl(path: String) = gatewayUrl.trimEnd('/') + "/" + path.trimStart('/')
    private fun String.pathSegment() = percentEncode()
    private fun String.queryComponent() = percentEncode()
    private fun String.percentEncode(): String = buildString {
        for (byte in this@percentEncode.encodeToByteArray()) {
            val value = byte.toInt() and 0xff
            val char = value.toChar()
            if ((char in 'a'..'z') || (char in 'A'..'Z') || (char in '0'..'9') || char in "-._~") append(char)
            else append('%').append(value.toString(16).uppercase().padStart(2, '0'))
        }
    }
    private fun Map<String, String>.toQuery() = entries.joinToString("&") { "${it.key.queryComponent()}=${it.value.queryComponent()}" }
    private fun encodedSizeLimit(decodedBytes: Int): Int = minOf(
        RemoteSandboxProtocol.MAX_RESPONSE_BYTES,
        ((decodedBytes.toLong() * 4L / 3L) + 1024L).toInt(),
    )
    private fun RemoteExecResult.requireCurrentSchema() {
        if (schema_version != RemoteSandboxProtocol.SCHEMA_VERSION) {
            throw SandboxError.ProviderUnavailable("Unsupported gateway protocol version: $schema_version")
        }
    }
    private fun RemoteFileContent.requireCurrentSchema() {
        if (schema_version != RemoteSandboxProtocol.SCHEMA_VERSION) {
            throw SandboxError.ProviderUnavailable("Unsupported gateway protocol version: $schema_version")
        }
    }
    private fun requireOutputWithinLimit(result: RemoteExecResult) {
        val bytes = result.stdout.orEmpty().encodeToByteArray().size.toLong() +
            result.stderr.orEmpty().encodeToByteArray().size.toLong()
        if (bytes > RemoteSandboxProtocol.MAX_COMMAND_OUTPUT_BYTES) {
            throw SandboxError.SandboxResourceLimit(
                "command output bytes",
                bytes,
                RemoteSandboxProtocol.MAX_COMMAND_OUTPUT_BYTES.toLong(),
            )
        }
    }

    companion object {
        private const val MAX_TOKEN_LIFETIME_MS = 15 * 60 * 1_000L

        /** Gateway auth material — the gateway's credential proxy mints scoped tokens. */
        data class GatewayAuth(val accessToken: String, val refreshToken: String? = null, val expiresEpochMs: Long? = null)
    }
}

// ---------- Gateway protocol types ----------

@Serializable private data class ApiVm(val id: String, val status: String, val expires_epoch_ms: Long? = null)
@Serializable private data class ApiFile(val name: String, val path: String, val is_directory: Boolean, val size_bytes: Long, val last_modified_ms: Long)
@Serializable private data class ApiProcess(val pid: Long, val ppid: Long? = null, val user: String? = null, val cpu_percent: Double? = null, val rss_mb: Long? = null, val state: String? = null, val command_line: String, val started_epoch_ms: Long? = null)
@Serializable private data class ApiExposedPort(val port: Int, val protocol: String, val proxy_url: String, val expires_epoch_ms: Long? = null)
@Serializable private data class ApiSnapshot(val id: String, val label: String, val created_epoch_ms: Long, val size_bytes: Long = 0)
