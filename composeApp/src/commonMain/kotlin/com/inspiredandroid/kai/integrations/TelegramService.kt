package com.inspiredandroid.kai.integrations

import com.inspiredandroid.kai.security.ProviderCredentialsResolver
import kotlinx.serialization.Serializable

/**
 * Telegram bot integration (section 21).
 *
 * Security posture — this is deliberately strict because a Telegram bot is
 * internet-facing:
 * - The bot token lives exclusively in the encrypted SecretStore vault.
 * - Only chat ids listed in [allowedChatIds] may interact with the bot —
 *   an unknown sender is ignored, never replied to. This is mandatory;
 *   a public bot token without an allow-list is a remote-control hole.
 * - The integration exposes read-only status reporting by default; command
 *   execution (task approve / reject) is off by default and must be enabled
 *   per-command by the user.
 */
object TelegramSecretKeys {
    const val BOT_TOKEN_KEY = "telegram_bot_token"
    const val ALLOWED_CHAT_IDS_KEY = "telegram_allowed_chats"
    const val COMMANDS_ENABLED_KEY = "telegram_commands_enabled"
}

@Serializable
data class TelegramStatus(
    val configured: Boolean,
    val allowedChatCount: Int,
    val commandsEnabled: Boolean,
    val lastDeliveryAt: Long? = null,
)

class TelegramService(
    private val resolver: ProviderCredentialsResolver,
) {
    suspend fun isConfigured(): Boolean {
        val token = resolver.secretStore.get(TelegramSecretKeys.BOT_TOKEN_KEY)
        return !token.isNullOrBlank()
    }

    suspend fun setBotToken(token: String) {
        resolver.secretStore.put(TelegramSecretKeys.BOT_TOKEN_KEY, token)
    }

    suspend fun clearBotToken() {
        resolver.secretStore.remove(TelegramSecretKeys.BOT_TOKEN_KEY)
    }

    suspend fun setAllowedChatIds(ids: List<String>) {
        resolver.secretStore.put(TelegramSecretKeys.ALLOWED_CHAT_IDS_KEY, ids.joinToString(","))
    }

    suspend fun getAllowedChatIds(): List<String> =
        resolver.secretStore.get(TelegramSecretKeys.ALLOWED_CHAT_IDS_KEY)
            .orEmpty().split(",").map { it.trim() }.filter { it.isNotBlank() }

    /** An inbound update is accepted ONLY if its chat id is allow-listed. */
    suspend fun isChatAllowed(chatId: String): Boolean {
        val allowed = getAllowedChatIds()
        return allowed.isNotEmpty() && chatId in allowed
    }

    suspend fun setCommandsEnabled(enabled: Boolean) {
        resolver.secretStore.put(TelegramSecretKeys.COMMANDS_ENABLED_KEY, enabled.toString())
    }

    suspend fun commandsEnabled(): Boolean =
        resolver.secretStore.get(TelegramSecretKeys.COMMANDS_ENABLED_KEY)?.toBoolean() ?: false

    suspend fun statusAsync(): TelegramStatus = TelegramStatus(
        configured = isConfigured(),
        allowedChatCount = getAllowedChatIds().size,
        commandsEnabled = commandsEnabled(),
    )
}
