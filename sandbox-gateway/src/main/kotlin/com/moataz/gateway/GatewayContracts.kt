package com.moataz.gateway

import kotlinx.serialization.Serializable

object GatewayProtocol {
    const val SCHEMA_VERSION = 1
    const val MAX_OUTPUT_BYTES = 1024 * 1024
    const val MAX_FILE_BYTES = 2 * 1024 * 1024
}

data class GatewayTenant(
    val tenantId: String,
    val subjectId: String,
    val sessionId: String,
    val tokenId: String,
    val scopes: Set<String>,
)

@Serializable
data class CreateSandboxRequest(
    val schema_version: Int = GatewayProtocol.SCHEMA_VERSION,
    val distro: String,
    val profile: String,
    val network_policy: String,
    val workspace_root: String,
)

@Serializable
data class GatewaySandbox(
    val schema_version: Int = GatewayProtocol.SCHEMA_VERSION,
    val id: String,
    val status: String,
    val expires_epoch_ms: Long? = null,
)

@Serializable
data class ExecRequest(
    val schema_version: Int = GatewayProtocol.SCHEMA_VERSION,
    val command: String,
    val args: List<String> = emptyList(),
    val working_directory: String? = null,
    val environment: Map<String, String> = emptyMap(),
    val timeout_seconds: Long? = null,
    val stdin: List<String> = emptyList(),
    val pty: Boolean = false,
)

@Serializable
data class ExecResult(
    val schema_version: Int = GatewayProtocol.SCHEMA_VERSION,
    val exit_code: Int,
    val stdout: String = "",
    val stderr: String = "",
)

@Serializable
data class FileContent(
    val schema_version: Int = GatewayProtocol.SCHEMA_VERSION,
    val content_base64: String,
)

@Serializable
data class WriteFileRequest(
    val schema_version: Int = GatewayProtocol.SCHEMA_VERSION,
    val path: String,
    val content_base64: String,
)

@Serializable
data class GatewayWsFrame(
    val schema_version: Int = GatewayProtocol.SCHEMA_VERSION,
    val type: String,
    val request_id: String,
    val data: String? = null,
    val rows: Int? = null,
    val columns: Int? = null,
    val exit_code: Int? = null,
)

fun GatewayWsFrame.validateClientFrame() {
    require(schema_version == GatewayProtocol.SCHEMA_VERSION) { "unsupported schema" }
    require(request_id.isNotBlank()) { "request_id is required" }
    when (type) {
        "start" -> require(data != null && data.encodeToByteArray().size <= 64 * 1024) { "invalid start" }
        "stdin" -> require(data != null && data.encodeToByteArray().size <= 64 * 1024) { "invalid stdin" }
        "resize" -> require(rows in 1..1000 && columns in 1..1000) { "invalid terminal size" }
        "cancel" -> Unit
        else -> error("unsupported client frame")
    }
}
