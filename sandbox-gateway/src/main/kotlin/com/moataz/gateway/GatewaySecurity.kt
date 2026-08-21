package com.moataz.gateway

import com.auth0.jwt.interfaces.JWTVerifier
import io.ktor.server.auth.jwt.JWTCredential
import io.ktor.server.auth.jwt.JWTPrincipal
import java.time.Clock

data class GatewaySecurityConfig(
    val verifier: JWTVerifier,
    val issuer: String,
    val audience: String,
    val maxTokenLifetimeSeconds: Long = 15 * 60,
    val clock: Clock = Clock.systemUTC(),
)

internal fun GatewaySecurityConfig.validate(credential: JWTCredential): JWTPrincipal? {
    val payload = credential.payload
    val now = clock.instant()
    val expires = payload.expiresAt?.toInstant() ?: return null
    val issuedAt = payload.issuedAt?.toInstant() ?: return null
    val notBefore = payload.notBefore?.toInstant() ?: return null
    val tenant = payload.getClaim("tenant_id").asString()?.takeIf { it.isNotBlank() } ?: return null
    val session = payload.getClaim("session_id").asString()?.takeIf { it.isNotBlank() } ?: return null
    val tokenId = payload.id?.takeIf { it.isNotBlank() } ?: return null
    val subject = payload.subject?.takeIf { it.isNotBlank() } ?: return null
    val scope = payload.getClaim("scope").asString()?.split(' ')?.filter { it.isNotBlank() }?.toSet().orEmpty()
    if (
        payload.issuer != issuer ||
        audience !in payload.audience ||
        scope.isEmpty() ||
        issuedAt.isAfter(now) ||
        notBefore.isAfter(now) ||
        expires.isBefore(issuedAt) ||
        expires.isAfter(issuedAt.plusSeconds(maxTokenLifetimeSeconds)) ||
        !expires.isAfter(now) ||
        expires.isAfter(now.plusSeconds(maxTokenLifetimeSeconds))
    ) return null
    // Touch values here so a principal is never returned before all required claims are parsed.
    if (tenant.isEmpty() || session.isEmpty() || tokenId.isEmpty() || subject.isEmpty()) return null
    return JWTPrincipal(payload)
}

internal fun JWTPrincipal.toTenant(): GatewayTenant = GatewayTenant(
    tenantId = payload.getClaim("tenant_id").asString(),
    subjectId = payload.subject,
    sessionId = payload.getClaim("session_id").asString(),
    tokenId = payload.id,
    scopes = payload.getClaim("scope").asString().split(' ').filter { it.isNotBlank() }.toSet(),
)

class TenantRateLimiter(
    private val maxRequests: Int = 120,
    private val windowMillis: Long = 60_000,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    private data class Window(var startedAt: Long, var requests: Int)
    private val windows = mutableMapOf<String, Window>()

    @Synchronized
    fun allow(tenantId: String): Boolean {
        val now = nowMillis()
        val window = windows.getOrPut(tenantId) { Window(now, 0) }
        if (now - window.startedAt >= windowMillis) {
            window.startedAt = now
            window.requests = 0
        }
        if (window.requests >= maxRequests) return false
        window.requests++
        return true
    }
}
