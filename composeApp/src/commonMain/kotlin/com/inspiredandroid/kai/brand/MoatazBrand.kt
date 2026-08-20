package com.inspiredandroid.kai.brand

/** User-facing names. Legacy package/database/preferences identifiers live elsewhere. */
object MoatazBrand {
    const val masterBrand = "Moataz"
    const val productName = "Moataz Alalqami AI"
    const val shortName = "Moataz"
    const val codeName = "Moataz Code"
    const val terminalName = "Moataz Terminal"
    const val runtimeName = "Moataz Runtime"
    const val agentsName = "Moataz Agents"
    const val gatewayName = "Moataz Gateway"
    const val workspaceName = "Moataz Workspace"
}

enum class AssistantAvatarStyle { Monogram, Orb, Custom }

enum class AssistantTone { CalmTechnical, Concise, Friendly, Custom }

/**
 * The assistant persona is deliberately separate from the product brand. Its
 * system identity describes an AI assistant and never impersonates a person.
 */
data class AssistantIdentity(
    val id: String,
    val displayName: String,
    val shortName: String,
    val systemIdentity: String,
    val avatarStyle: AssistantAvatarStyle,
    val tone: AssistantTone,
) {
    init {
        require(id.isNotBlank())
        require(displayName.isNotBlank())
        require(systemIdentity.contains("AI", ignoreCase = true)) {
            "Assistant system identity must explicitly describe an AI"
        }
    }

    companion object {
        val Default = AssistantIdentity(
            id = "moataz-assistant-v1",
            displayName = "Moataz",
            shortName = "Moataz",
            systemIdentity = "Moataz AI assistant and agent",
            avatarStyle = AssistantAvatarStyle.Monogram,
            tone = AssistantTone.CalmTechnical,
        )
    }
}

interface AssistantIdentityProvider {
    val current: AssistantIdentity
}

class DefaultAssistantIdentityProvider(
    override val current: AssistantIdentity = AssistantIdentity.Default,
) : AssistantIdentityProvider
