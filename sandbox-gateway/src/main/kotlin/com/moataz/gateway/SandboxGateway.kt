package com.moataz.gateway

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.serialization.kotlinx.json.json
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import java.util.Base64
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class SandboxGatewayConfig(
    val security: GatewaySecurityConfig,
    val provider: TenantSandboxProvider = NotConfiguredSandboxProvider,
    val rateLimiter: TenantRateLimiter = TenantRateLimiter(),
)

private class ScopeDenied : RuntimeException()

fun Application.installSandboxGateway(config: SandboxGatewayConfig) {
    val jsonCodec = Json { ignoreUnknownKeys = false; encodeDefaults = true }

    install(ContentNegotiation) { json(jsonCodec) }
    install(WebSockets)
    install(StatusPages) {
        exception<ProviderNotConfigured> { call, _ ->
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "sandbox_provider_not_configured"))
        }
        exception<TenantAccessDenied> { call, _ ->
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "sandbox_not_found"))
        }
        exception<GatewayLimitExceeded> { call, _ ->
            call.respond(HttpStatusCode.PayloadTooLarge, mapOf("error" to "gateway_limit_exceeded"))
        }
        exception<GatewayRateLimitExceeded> { call, _ ->
            call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "rate_limited"))
        }
        exception<ScopeDenied> { call, _ ->
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "scope_denied"))
        }
        exception<IllegalArgumentException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_request"))
        }
    }
    install(Authentication) {
        jwt("gateway-jwt") {
            verifier(config.security.verifier)
            validate { credential -> config.security.validate(credential) }
            challenge { _, _ ->
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid_token"))
            }
        }
    }

    routing {
        get("/health") {
            call.respond(mapOf("status" to "experimental", "provider" to config.provider.providerId))
        }
        authenticate("gateway-jwt") {
            route("/api/v1") {
                post("/vms") {
                    val tenant = call.gatewayTenant("sandbox:provision", config.rateLimiter)
                    val request = call.receive<CreateSandboxRequest>().requireSchema()
                    call.respond(HttpStatusCode.Created, config.provider.create(tenant, request))
                }
                post("/sandboxes/{id}/exec") {
                    val tenant = call.gatewayTenant("sandbox:exec", config.rateLimiter)
                    val id = call.parameters["id"]?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException()
                    val result = config.provider.exec(tenant, id, call.receive<ExecRequest>().requireSchema())
                    result.requireWithinOutputLimit()
                    call.respond(result)
                }
                get("/sandboxes/{id}/files/read") {
                    val tenant = call.gatewayTenant("sandbox:files:read", config.rateLimiter)
                    val id = call.parameters["id"]?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException()
                    val path = requireWorkspacePath(call.request.queryParameters["path"] ?: throw IllegalArgumentException())
                    val maxBytes = call.request.queryParameters["max_length"]?.toIntOrNull()
                        ?.takeIf { it in 0..GatewayProtocol.MAX_FILE_BYTES } ?: throw IllegalArgumentException()
                    val bytes = config.provider.readFile(tenant, id, path, maxBytes)
                    if (bytes.size > maxBytes) throw GatewayLimitExceeded("file exceeds requested limit")
                    call.respond(FileContent(content_base64 = Base64.getEncoder().encodeToString(bytes)))
                }
                post("/sandboxes/{id}/files/write") {
                    val tenant = call.gatewayTenant("sandbox:files:write", config.rateLimiter)
                    val id = call.parameters["id"]?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException()
                    val request = call.receive<WriteFileRequest>().requireSchema()
                    val bytes = runCatching { Base64.getDecoder().decode(request.content_base64) }
                        .getOrElse { throw IllegalArgumentException() }
                    if (bytes.size > GatewayProtocol.MAX_FILE_BYTES) throw GatewayLimitExceeded("file exceeds write limit")
                    config.provider.writeFile(tenant, id, request.path, bytes)
                    call.respond(HttpStatusCode.NoContent)
                }
                webSocket("/sandboxes/{id}/exec/ws") {
                    val principal = call.principal<JWTPrincipal>() ?: run {
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "invalid token"))
                        return@webSocket
                    }
                    val tenant = principal.toTenant()
                    if ("sandbox:exec" !in tenant.scopes || !config.rateLimiter.allow(tenant.tenantId)) {
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "request denied"))
                        return@webSocket
                    }
                    val id = call.parameters["id"]?.takeIf { it.isNotBlank() } ?: run {
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "invalid sandbox"))
                        return@webSocket
                    }
                    val first = incoming.receive() as? Frame.Text ?: run {
                        close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "start frame required"))
                        return@webSocket
                    }
                    val start = runCatching { jsonCodec.decodeFromString<GatewayWsFrame>(first.readText()) }
                        .getOrElse {
                            close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "invalid start frame"))
                            return@webSocket
                        }
                    runCatching { start.validateClientFrame() }.getOrElse {
                        close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "invalid start frame"))
                        return@webSocket
                    }
                    if (start.type != "start") {
                        close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "start frame required"))
                        return@webSocket
                    }
                    val exec = runCatching { jsonCodec.decodeFromString<ExecRequest>(start.data.orEmpty()).requireSchema() }
                        .getOrElse {
                            close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "invalid exec request"))
                            return@webSocket
                        }
                    val sendMutex = Mutex()
                    var outputBytes = 0L
                    suspend fun sendFrame(frame: GatewayWsFrame) {
                        sendMutex.withLock { send(Frame.Text(jsonCodec.encodeToString(frame))) }
                    }
                    suspend fun sendOutput(type: String, text: String) {
                        sendMutex.withLock {
                            outputBytes += text.encodeToByteArray().size
                            if (outputBytes > GatewayProtocol.MAX_OUTPUT_BYTES) {
                                throw GatewayLimitExceeded("output limit")
                            }
                            send(Frame.Text(jsonCodec.encodeToString(
                                GatewayWsFrame(type = type, request_id = start.request_id, data = text),
                            )))
                        }
                    }
                    val output = object : GatewayCommandOutput {
                        override suspend fun stdout(text: String) {
                            sendOutput("stdout", text)
                        }

                        override suspend fun stderr(text: String) {
                            sendOutput("stderr", text)
                        }
                    }
                    val handle = try {
                        config.provider.openExec(tenant, id, exec, output)
                    } catch (_: TenantAccessDenied) {
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "sandbox not found"))
                        return@webSocket
                    } catch (_: ProviderNotConfigured) {
                        close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "provider unavailable"))
                        return@webSocket
                    } catch (_: GatewayLimitExceeded) {
                        close(CloseReason(CloseReason.Codes.TOO_BIG, "output limit"))
                        return@webSocket
                    }
                    var exited = false
                    val exitJob = launch {
                        val exitCode = handle.awaitExit()
                        sendFrame(GatewayWsFrame(type = "exit", request_id = start.request_id, exit_code = exitCode))
                        exited = true
                        close(CloseReason(CloseReason.Codes.NORMAL, "command exited"))
                    }
                    try {
                        for (raw in incoming) {
                            val text = raw as? Frame.Text ?: continue
                            val frame = jsonCodec.decodeFromString<GatewayWsFrame>(text.readText())
                            frame.validateClientFrame()
                            if (frame.request_id != start.request_id) throw IllegalArgumentException()
                            when (frame.type) {
                                "stdin" -> handle.writeInput(frame.data.orEmpty())
                                "resize" -> handle.resize(frame.rows!!, frame.columns!!)
                                "cancel" -> handle.cancel()
                            }
                        }
                    } finally {
                        if (!exited) handle.cancel()
                        exitJob.cancelAndJoin()
                    }
                }
            }
        }
    }
}

private fun io.ktor.server.application.ApplicationCall.gatewayTenant(
    requiredScope: String,
    rateLimiter: TenantRateLimiter,
): GatewayTenant {
    val tenant = principal<JWTPrincipal>()?.toTenant() ?: throw ScopeDenied()
    if (requiredScope !in tenant.scopes) throw ScopeDenied()
    if (!rateLimiter.allow(tenant.tenantId)) throw GatewayRateLimitExceeded()
    return tenant
}

private fun CreateSandboxRequest.requireSchema() = apply {
    require(schema_version == GatewayProtocol.SCHEMA_VERSION)
}

private fun ExecRequest.requireSchema() = apply {
    require(schema_version == GatewayProtocol.SCHEMA_VERSION)
    require(command.isNotBlank() && command.length <= 4096)
    require(args.size <= 1024 && environment.size <= 512)
    working_directory?.let(::requireWorkspacePath)
}

private fun WriteFileRequest.requireSchema() = apply {
    require(schema_version == GatewayProtocol.SCHEMA_VERSION)
    requireWorkspacePath(path)
}

private fun requireWorkspacePath(path: String): String {
    require(path == "/workspace" || path.startsWith("/workspace/")) { "path must stay inside /workspace" }
    require('\u0000' !in path) { "path contains NUL" }
    require(path.split('/').none { it == "." || it == ".." }) { "path traversal is forbidden" }
    return path
}

private fun ExecResult.requireWithinOutputLimit() {
    val bytes = stdout.encodeToByteArray().size.toLong() + stderr.encodeToByteArray().size.toLong()
    if (bytes > GatewayProtocol.MAX_OUTPUT_BYTES) throw GatewayLimitExceeded("command output limit")
}
