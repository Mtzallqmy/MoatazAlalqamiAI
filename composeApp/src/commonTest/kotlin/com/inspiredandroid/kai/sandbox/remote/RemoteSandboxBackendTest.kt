package com.inspiredandroid.kai.sandbox.remote

import com.inspiredandroid.kai.sandbox.backend.ExecRequest
import com.inspiredandroid.kai.sandbox.backend.ExecStreamListener
import com.inspiredandroid.kai.sandbox.backend.SandboxError
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class RemoteSandboxBackendTest {
    @Test
    fun `gateway must use https`() {
        assertFailsWith<SandboxError.ConfigurationError> {
            backend("http://gateway.example")
        }
    }

    @Test
    fun `expired token fails closed before network`() = runTest {
        var networkCalls = 0
        val backend = backend(
            engine = MockEngine {
                networkCalls++
                respond("[]", headers = jsonHeaders)
            },
            auth = RemoteSandboxBackend.GatewayAuth("expired", expiresEpochMs = 99),
        )

        assertFailsWith<SandboxError.AuthError> { backend.listProcesses("sandbox") }
        assertEquals(0, networkCalls)
    }

    @Test
    fun `token without expiry fails closed`() = runTest {
        val backend = backend(auth = RemoteSandboxBackend.GatewayAuth("not-scoped-by-time"))
        assertFailsWith<SandboxError.AuthError> { backend.listProcesses("sandbox") }
    }

    @Test
    fun `http auth error remains typed`() = runTest {
        val backend = backend(engine = MockEngine {
            respond("denied", status = HttpStatusCode.Unauthorized)
        })

        assertFailsWith<SandboxError.AuthError> { backend.listProcesses("sandbox") }
    }

    @Test
    fun `binary file response round trips without utf8 conversion`() = runTest {
        val expected = byteArrayOf(0, 1, 2, 0x7f, 0xff.toByte())
        val backend = backend(engine = MockEngine {
            respond(
                """{"schema_version":1,"content_base64":"${Base64.encode(expected)}"}""",
                headers = jsonHeaders,
            )
        })

        assertContentEquals(expected, backend.readFile("sandbox", "/workspace/data.bin", 32))
    }

    @Test
    fun `rest client does not claim interactive streaming`() = runTest {
        val backend = backend(engine = MockEngine { error("request must not run") })
        assertFalse(backend.capabilities.streamingExec)
        assertFailsWith<SandboxError.ProviderUnavailable> {
            backend.execStreaming("sandbox", ExecRequest("bash", pty = true), object : ExecStreamListener {})
        }
    }

    private fun backend(
        url: String = "https://gateway.example",
        engine: MockEngine = MockEngine { respond("[]", headers = jsonHeaders) },
        auth: RemoteSandboxBackend.GatewayAuth =
            RemoteSandboxBackend.GatewayAuth("short-lived", expiresEpochMs = 120_000),
    ) = RemoteSandboxBackend(
        gatewayUrl = url,
        fetchAuth = { auth },
        engine = engine,
        nowEpochMs = { 1_000 },
    )

    private companion object {
        val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    }
}
