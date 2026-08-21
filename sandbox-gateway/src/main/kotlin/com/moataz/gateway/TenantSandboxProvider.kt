package com.moataz.gateway

interface TenantSandboxProvider {
    val providerId: String

    suspend fun create(tenant: GatewayTenant, request: CreateSandboxRequest): GatewaySandbox
    suspend fun exec(tenant: GatewayTenant, sandboxId: String, request: ExecRequest): ExecResult
    suspend fun openExec(
        tenant: GatewayTenant,
        sandboxId: String,
        request: ExecRequest,
        output: GatewayCommandOutput,
    ): GatewayCommandHandle
    suspend fun readFile(tenant: GatewayTenant, sandboxId: String, path: String, maxBytes: Int): ByteArray
    suspend fun writeFile(tenant: GatewayTenant, sandboxId: String, path: String, content: ByteArray)
}

interface GatewayCommandOutput {
    suspend fun stdout(text: String)
    suspend fun stderr(text: String)
}

interface GatewayCommandHandle {
    suspend fun writeInput(text: String)
    suspend fun resize(rows: Int, columns: Int)
    suspend fun cancel()
    suspend fun awaitExit(): Int
}

class ProviderNotConfigured(message: String = "No sandbox provider is configured") : RuntimeException(message)
class TenantAccessDenied : RuntimeException("Sandbox not found")
class GatewayLimitExceeded(message: String) : RuntimeException(message)
class GatewayRateLimitExceeded : RuntimeException("Rate limit exceeded")

/** Safe runtime default: the experimental gateway cannot provision until an operator injects a provider. */
object NotConfiguredSandboxProvider : TenantSandboxProvider {
    override val providerId = "not-configured"

    private fun unavailable(): Nothing = throw ProviderNotConfigured()
    override suspend fun create(tenant: GatewayTenant, request: CreateSandboxRequest): GatewaySandbox = unavailable()
    override suspend fun exec(tenant: GatewayTenant, sandboxId: String, request: ExecRequest): ExecResult = unavailable()
    override suspend fun openExec(
        tenant: GatewayTenant,
        sandboxId: String,
        request: ExecRequest,
        output: GatewayCommandOutput,
    ): GatewayCommandHandle = unavailable()
    override suspend fun readFile(tenant: GatewayTenant, sandboxId: String, path: String, maxBytes: Int): ByteArray = unavailable()
    override suspend fun writeFile(tenant: GatewayTenant, sandboxId: String, path: String, content: ByteArray): Unit = unavailable()
}
