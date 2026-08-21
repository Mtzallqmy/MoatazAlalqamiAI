package com.inspiredandroid.kai.sandbox.remote

import com.inspiredandroid.kai.sandbox.backend.ExecRequest
import com.inspiredandroid.kai.sandbox.backend.ResourceProfile
import kotlinx.serialization.Serializable

/** Versioned wire contract shared by the Android client and a future gateway. */
object RemoteSandboxProtocol {
    const val SCHEMA_VERSION: Int = 1
    const val MAX_RESPONSE_BYTES: Int = 4 * 1024 * 1024
    const val MAX_FILE_BYTES: Int = 2 * 1024 * 1024
    const val MAX_COMMAND_OUTPUT_BYTES: Int = 1024 * 1024
}

@Serializable
data class RemoteExecRequest(
    val schema_version: Int = RemoteSandboxProtocol.SCHEMA_VERSION,
    val command: String,
    val args: List<String> = emptyList(),
    val working_directory: String? = null,
    val environment: Map<String, String> = emptyMap(),
    val timeout_seconds: Long? = null,
    val stdin: List<String> = emptyList(),
    val pty: Boolean = false,
)

@Serializable
data class RemoteExecResult(
    val schema_version: Int = RemoteSandboxProtocol.SCHEMA_VERSION,
    val exit_code: Int,
    val stdout: String? = null,
    val stderr: String? = null,
)

@Serializable
data class RemoteFileContent(
    val schema_version: Int = RemoteSandboxProtocol.SCHEMA_VERSION,
    val content_base64: String,
)

@Serializable
data class RemoteWriteFileRequest(
    val schema_version: Int = RemoteSandboxProtocol.SCHEMA_VERSION,
    val path: String,
    val content_base64: String,
)

@Serializable
data class RemoteCreateVmRequest(
    val schema_version: Int = RemoteSandboxProtocol.SCHEMA_VERSION,
    val distro: String,
    val profile: String,
    val network_policy: String,
    val workspace_root: String,
)

@Serializable
data class RemoteActionRequest(
    val schema_version: Int = RemoteSandboxProtocol.SCHEMA_VERSION,
)

@Serializable
data class RemoteMoveFileRequest(
    val schema_version: Int = RemoteSandboxProtocol.SCHEMA_VERSION,
    val from: String,
    val to: String,
)

@Serializable
data class RemoteSignalRequest(
    val schema_version: Int = RemoteSandboxProtocol.SCHEMA_VERSION,
    val signal: String,
)

@Serializable
data class RemoteOpenPortRequest(
    val schema_version: Int = RemoteSandboxProtocol.SCHEMA_VERSION,
    val port: Int,
    val protocol: String,
)

@Serializable
data class RemoteSnapshotRequest(
    val schema_version: Int = RemoteSandboxProtocol.SCHEMA_VERSION,
    val label: String,
)

internal fun ExecRequest.toRemoteRequest() = RemoteExecRequest(
    command = command,
    args = args,
    working_directory = workingDirectory,
    environment = environment,
    timeout_seconds = timeout?.inWholeSeconds,
    stdin = stdin,
    pty = pty,
)

/** Identity supplied by verified gateway authentication, never by request JSON. */
data class SandboxTenant(
    val tenantId: String,
    val subjectId: String,
)

/**
 * Server-side provider boundary. Every operation requires a verified tenant,
 * preventing provider implementations from accidentally performing unscoped
 * lookups by sandbox id alone.
 */
interface TenantSandboxProvider {
    val providerId: String

    suspend fun isAvailable(): Boolean
    suspend fun quotas(tenant: SandboxTenant): ProviderQuotas
    suspend fun createVm(tenant: SandboxTenant, template: VmTemplate, profile: ResourceProfile): VmHandle
    suspend fun startVm(tenant: SandboxTenant, id: String)
    suspend fun stopVm(tenant: SandboxTenant, id: String)
    suspend fun destroyVm(tenant: SandboxTenant, id: String)
    suspend fun listVms(tenant: SandboxTenant): List<VmHandle>
}
