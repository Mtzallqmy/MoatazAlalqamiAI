package com.moataz.gateway

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SandboxGatewayTest {
    private val now = Instant.now()
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val algorithm = Algorithm.HMAC256("test-only-secret-with-enough-entropy")
    private val verifier = JWT.require(algorithm).withIssuer(ISSUER).withAudience(AUDIENCE).build()
    private val json = Json { encodeDefaults = true }

    @Test
    fun `missing required jwt claim is unauthorized`() = testApplication {
        application { installSandboxGateway(config(FakeProvider())) }
        val invalid = JWT.create()
            .withIssuer(ISSUER).withAudience(AUDIENCE).withSubject("user")
            .withExpiresAt(Date.from(now.plusSeconds(300))).sign(algorithm)

        val response = client.post("/api/v1/sandboxes/s1/exec") {
            bearer(invalid)
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(ExecRequest(command = "true")))
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `token longer than configured lifetime is unauthorized`() = testApplication {
        application { installSandboxGateway(config(FakeProvider())) }
        val response = client.post("/api/v1/sandboxes/s1/exec") {
            bearer(token("tenant-a", expiresSeconds = 3600))
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(ExecRequest(command = "true")))
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `sandbox lookup is scoped to jwt tenant`() = testApplication {
        val provider = FakeProvider().apply { own("sandbox-a", "tenant-a") }
        application { installSandboxGateway(config(provider)) }

        val response = client.post("/api/v1/sandboxes/sandbox-a/exec") {
            bearer(token("tenant-b"))
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(ExecRequest(command = "pwd")))
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("tenant-b", provider.lastObservedTenant)
    }

    @Test
    fun `rate limit is enforced per tenant`() = testApplication {
        val provider = FakeProvider().apply { own("sandbox-a", "tenant-a") }
        application {
            installSandboxGateway(config(provider, TenantRateLimiter(maxRequests = 1)))
        }
        suspend fun request() = client.post("/api/v1/sandboxes/sandbox-a/exec") {
            bearer(token("tenant-a"))
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(ExecRequest(command = "true")))
        }

        assertEquals(HttpStatusCode.OK, request().status)
        assertEquals(HttpStatusCode.TooManyRequests, request().status)
    }

    @Test
    fun `one shot command output is bounded`() = testApplication {
        val provider = FakeProvider().apply {
            own("sandbox-a", "tenant-a")
            execStdout = "x".repeat(GatewayProtocol.MAX_OUTPUT_BYTES + 1)
        }
        application { installSandboxGateway(config(provider)) }
        val response = client.post("/api/v1/sandboxes/sandbox-a/exec") {
            bearer(token("tenant-a"))
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(ExecRequest(command = "cat")))
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
    }

    @Test
    fun `websocket streams output and forwards stdin resize and cancel`() = testApplication {
        val provider = FakeProvider().apply { own("sandbox-a", "tenant-a") }
        application { installSandboxGateway(config(provider)) }
        val wsClient = createClient { install(WebSockets) }

        wsClient.webSocket(
            urlString = "/api/v1/sandboxes/sandbox-a/exec/ws",
            request = { bearer(token("tenant-a")) },
        ) {
            val requestId = "request-1"
            send(Frame.Text(json.encodeToString(GatewayWsFrame(
                type = "start",
                request_id = requestId,
                data = json.encodeToString(ExecRequest(command = "bash", pty = true)),
            ))))
            val stdout = json.decodeFromString<GatewayWsFrame>((incoming.receive() as Frame.Text).readText())
            assertEquals("stdout", stdout.type)
            assertEquals("ready", stdout.data)

            send(Frame.Text(json.encodeToString(GatewayWsFrame(type = "stdin", request_id = requestId, data = "hello\n"))))
            send(Frame.Text(json.encodeToString(GatewayWsFrame(type = "resize", request_id = requestId, rows = 40, columns = 120))))
            send(Frame.Text(json.encodeToString(GatewayWsFrame(type = "cancel", request_id = requestId))))

            val exit = json.decodeFromString<GatewayWsFrame>((incoming.receive() as Frame.Text).readText())
            assertEquals("exit", exit.type)
            assertEquals(130, exit.exit_code)
        }

        assertEquals(listOf("hello\n"), provider.inputs)
        assertEquals(listOf(40 to 120), provider.sizes)
        assertTrue(provider.cancelled)
    }

    private fun config(
        provider: TenantSandboxProvider,
        rateLimiter: TenantRateLimiter = TenantRateLimiter(),
    ) = SandboxGatewayConfig(
        security = GatewaySecurityConfig(verifier, ISSUER, AUDIENCE, clock = clock),
        provider = provider,
        rateLimiter = rateLimiter,
    )

    private fun token(tenant: String, expiresSeconds: Long = 300): String = JWT.create()
        .withIssuer(ISSUER)
        .withAudience(AUDIENCE)
        .withSubject("user-$tenant")
        .withClaim("tenant_id", tenant)
        .withClaim("session_id", "session-1")
        .withClaim("scope", "sandbox:exec sandbox:provision sandbox:files:read sandbox:files:write")
        .withJWTId("jti-1")
        .withIssuedAt(Date.from(now))
        .withNotBefore(Date.from(now))
        .withExpiresAt(Date.from(now.plusSeconds(expiresSeconds)))
        .sign(algorithm)

    private fun io.ktor.client.request.HttpRequestBuilder.bearer(token: String) {
        header(HttpHeaders.Authorization, "Bearer $token")
    }

    private class FakeProvider : TenantSandboxProvider {
        override val providerId = "fake-test-provider"
        private val owners = mutableMapOf<String, String>()
        val inputs = mutableListOf<String>()
        val sizes = mutableListOf<Pair<Int, Int>>()
        var cancelled = false
        var lastObservedTenant: String? = null
        var execStdout = "ok"

        fun own(id: String, tenant: String) {
            owners[id] = tenant
        }

        private fun authorize(tenant: GatewayTenant, id: String) {
            lastObservedTenant = tenant.tenantId
            if (owners[id] != tenant.tenantId) throw TenantAccessDenied()
        }

        override suspend fun create(tenant: GatewayTenant, request: CreateSandboxRequest): GatewaySandbox {
            val id = "sandbox-${tenant.tenantId}"
            own(id, tenant.tenantId)
            return GatewaySandbox(id = id, status = "running")
        }

        override suspend fun exec(tenant: GatewayTenant, sandboxId: String, request: ExecRequest): ExecResult {
            authorize(tenant, sandboxId)
            return ExecResult(exit_code = 0, stdout = execStdout)
        }

        override suspend fun openExec(
            tenant: GatewayTenant,
            sandboxId: String,
            request: ExecRequest,
            output: GatewayCommandOutput,
        ): GatewayCommandHandle {
            authorize(tenant, sandboxId)
            output.stdout("ready")
            val exit = CompletableDeferred<Int>()
            return object : GatewayCommandHandle {
                override suspend fun writeInput(text: String) { inputs += text }
                override suspend fun resize(rows: Int, columns: Int) { sizes += rows to columns }
                override suspend fun cancel() { cancelled = true; exit.complete(130) }
                override suspend fun awaitExit(): Int = exit.await()
            }
        }

        override suspend fun readFile(tenant: GatewayTenant, sandboxId: String, path: String, maxBytes: Int): ByteArray {
            authorize(tenant, sandboxId)
            return byteArrayOf()
        }

        override suspend fun writeFile(tenant: GatewayTenant, sandboxId: String, path: String, content: ByteArray) {
            authorize(tenant, sandboxId)
        }
    }

    private companion object {
        const val ISSUER = "https://issuer.example"
        const val AUDIENCE = "moataz-sandbox"
    }
}
