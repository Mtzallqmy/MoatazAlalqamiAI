package com.inspiredandroid.kai.runtime

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeDiagnosticsTest {
    @Test fun `redacts assignments json cli arguments and credential urls`() {
        val rawSecrets = listOf(
            "plain-secret-value",
            "json-secret-value",
            "cli-secret-value",
            "github-secret-value",
        )
        val input = """
            api_key=plain-secret-value
            {"token":"json-secret-value"}
            command --password cli-secret-value
            https://x-access-token:github-secret-value@github.com/owner/repo.git
        """.trimIndent()

        val redacted = RuntimeDiagnosticRedactor.redact(input)

        rawSecrets.forEach { assertFalse(it in redacted) }
        assertTrue("[REDACTED]" in redacted)
    }

    @Test fun `redacts bearer known provider tokens and private keys`() {
        val privateKeyBody = "super-private-material"
        val input = """
            Authorization: Bearer abcdefghijklmnopqrstuvwxyz.123456
            sk-abcdefghijklmnopqrstuvwxyz123456
            -----BEGIN OPENSSH PRIVATE KEY-----
            $privateKeyBody
            -----END OPENSSH PRIVATE KEY-----
        """.trimIndent()

        val redacted = RuntimeDiagnosticRedactor.redact(input)

        assertFalse("abcdefghijklmnopqrstuvwxyz.123456" in redacted)
        assertFalse("abcdefghijklmnopqrstuvwxyz123456" in redacted)
        assertFalse(privateKeyBody in redacted)
        assertTrue("[REDACTED PRIVATE KEY]" in redacted)
    }

    @Test fun `diagnostic sink applies redaction before retaining events`() {
        val sink = InMemoryRuntimeDiagnostics(limit = 1)
        sink.record(RuntimeDiagnosticEvent("probe", "tool --token secret-token-value", 1, 2, "api_key=secret-key-value", null))

        val event = sink.snapshot().single()
        assertFalse("secret-token-value" in event.command.orEmpty())
        assertFalse("secret-key-value" in event.stderrTail.orEmpty())
    }

    @Test fun `diagnostic sink remains bounded and export contains operational fields`() {
        val sink = InMemoryRuntimeDiagnostics(limit = 2)
        repeat(3) { sink.record(RuntimeDiagnosticEvent("stage-$it", "true", 0, it.toLong(), null, null)) }

        val retained = sink.snapshot()
        assertTrue(retained.size == 2)
        assertTrue(retained.first().stage == "stage-1")
    }
}
