package com.inspiredandroid.kai.gateway

/** Determines the data-controller boundary without inspecting request content. */
object ProviderBoundary {
    fun crosses(
        sourceProviderId: String,
        sourceEndpoint: String,
        destinationProviderId: String,
        destinationEndpoint: String,
    ): Boolean {
        if (sourceProviderId != destinationProviderId) return true
        if (sourceProviderId != "openai-compatible") return false
        return !UrlNormalization.normalizeBaseUrl(sourceEndpoint).equals(
            UrlNormalization.normalizeBaseUrl(destinationEndpoint),
            ignoreCase = true,
        )
    }
}

/** Metadata-only approval request. Prompt content and credentials never enter approval logs. */
data class ProviderTransferRequest(
    val sourceProviderInstanceId: String,
    val destinationProviderInstanceId: String,
    val taskType: TaskType,
    val routingProfile: RoutingProfileId,
    val reason: AiRequestError.Category,
)

enum class ProviderTransferDecision { ApprovedOnce, Denied }

class ProviderTransferNotApprovedException(
    val request: ProviderTransferRequest,
) : Exception(
    "Fallback to another AI provider requires explicit approval " +
        "(${request.sourceProviderInstanceId} -> ${request.destinationProviderInstanceId})",
)

fun interface ProviderTransferApprovalGate {
    suspend fun decide(request: ProviderTransferRequest): ProviderTransferDecision

    companion object {
        /** Fail closed until a user-facing approval surface supplies an explicit decision. */
        val DenyByDefault = ProviderTransferApprovalGate { ProviderTransferDecision.Denied }
    }
}
