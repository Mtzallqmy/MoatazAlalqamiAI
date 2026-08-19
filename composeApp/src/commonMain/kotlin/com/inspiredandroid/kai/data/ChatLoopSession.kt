/*
 * Moataz Alalqami AI — Chat Loop Session
 *
 * Isolated domain boundary: pure chat-turn orchestration with tool loops.
 * Kept free of SMS/Email/Notification/Heartbeat concerns so the provider and
 * chat layers can be evolved (and unit-tested) independently of the repository
 * that aggregates all device features.
 */
package com.inspiredandroid.kai.data

import com.inspiredandroid.kai.ui.chat.History
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.uuid.Uuid
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Result of a single provider chat call within a tool loop. */
data class ChatLoopSessionResult(
    val textContent: String,
    val reasoningContent: String? = null,
    val isThinkingContent: Boolean = false,
    val toolCalls: List<com.inspiredandroid.kai.ui.chat.ToolCallInfo>,
)

/** Final output of one complete tool-loop session. */
data class ChatLoopTurn(
    val content: String,
    val reasoningContent: String? = null,
)

/** Reason the loop stopped asking the model for more tool calls. */
enum class ChatLoopBailoutReason { LIMIT_REACHED, REPEATING }

/** Bailout prompts sent to the model when the loop stops tool calling. */
object ChatLoopBailoutPrompt {
    fun bailoutPrompt(reason: ChatLoopBailoutReason): String = when (reason) {
        ChatLoopBailoutReason.LIMIT_REACHED -> "You have reached the tool call limit. Please respond with the best answer you have so far based on the information gathered."
        ChatLoopBailoutReason.REPEATING -> "You are repeating the same tool calls. Please respond with the best answer you have so far."
    }
}

/** Strategy that wraps one provider's wire protocol behind the tool loop. */
interface ChatLoopStrategy {
    suspend fun chat(history: List<History>, systemPrompt: String?): ChatLoopSessionResult
    suspend fun bailout(history: List<History>, systemPrompt: String?, reason: ChatLoopBailoutReason): String
    /**
     * Context budget used to trim raw history between tool rounds. Providers that send the
     * history as-is (Gemini, Anthropic) declare their window here; the OpenAI-compatible
     * strategy trims the built message list inside [chat] instead and leaves this null.
     */
    val historyContextWindowTokens: Int? get() = null
}

object ChatLoopConstants {
    const val MAX_TOOL_ITERATIONS = 15
    const val MAX_REPEATED_TOOL_CALLS = 3
    const val MIN_TOOL_DISPLAY_MS = 2000L
    const val ESTIMATED_CHARS_PER_TOKEN = 4
    const val DEFAULT_CONTEXT_WINDOW_TOKENS = 16_000
    const val COMPACTION_THRESHOLD = 0.7
    const val COMPACTION_KEEP_RECENT = 4
}

/** Detects whether the current batch of tool calls repeats a recent pattern. */
fun isRepeatingToolCalls(recentSignatures: List<String>, currentSignatures: List<String>): Boolean {
    if (currentSignatures.isEmpty()) return false
    val batchSize = currentSignatures.size
    var consecutiveCount = 0
    var i = recentSignatures.size - batchSize
    while (i >= 0) {
        val slice = recentSignatures.subList(i, i + batchSize)
        if (slice == currentSignatures) {
            consecutiveCount++
            i -= batchSize
        } else {
            break
        }
    }
    return consecutiveCount + 1 >= ChatLoopConstants.MAX_REPEATED_TOOL_CALLS
}

/**
 * Trims OpenAI-compatible messages to fit within the estimated context window by
 * dropping oldest messages (keeping the system prompt and most recent messages).
 */
fun trimMessagesForContext(
    messages: List<com.inspiredandroid.kai.network.dtos.openaicompatible.OpenAICompatibleChatRequestDto.Message>,
    contextWindowTokens: Int = ModelCatalog.DEFAULT_CONTEXT_WINDOW_TOKENS,
): List<com.inspiredandroid.kai.network.dtos.openaicompatible.OpenAICompatibleChatRequestDto.Message> {
    val maxChars = contextWindowTokens * ChatLoopConstants.ESTIMATED_CHARS_PER_TOKEN
    val totalChars = messages.sumOf { estimateMessageChars(it) }
    if (totalChars <= maxChars) return messages
    val systemMessages = messages.takeWhile { it.role == "system" }
    val nonSystemMessages = messages.drop(systemMessages.size)
    val systemChars = systemMessages.sumOf { estimateMessageChars(it) }
    val availableChars = maxChars - systemChars
    // Group each assistant tool-call turn together with the tool responses that follow it so
    // trimming never strands one without the other. Strict OpenAI-compatible providers (e.g.
    // DeepSeek via OpenCode Zen) reject an assistant `tool_calls` message that isn't followed
    // by its tool responses, and a `tool` message without a preceding `tool_calls`.
    val groups = mutableListOf<List<com.inspiredandroid.kai.network.dtos.openaicompatible.OpenAICompatibleChatRequestDto.Message>>()
    var index = 0
    while (index < nonSystemMessages.size) {
        val msg = nonSystemMessages[index]
        if (msg.role == "assistant" && !msg.tool_calls.isNullOrEmpty()) {
            var end = index + 1
            while (end < nonSystemMessages.size && nonSystemMessages[end].role == "tool") {
                end++
            }
            groups.add(nonSystemMessages.subList(index, end).toList())
            index = end
        } else {
            groups.add(listOf(msg))
            index++
        }
    }
    val kept = mutableListOf<com.inspiredandroid.kai.network.dtos.openaicompatible.OpenAICompatibleChatRequestDto.Message>()
    var usedChars = 0
    for (group in groups.asReversed()) {
        val groupChars = group.sumOf { estimateMessageChars(it) }
        if (usedChars + groupChars > availableChars) break
        kept.addAll(0, group)
        usedChars += groupChars
    }
    return systemMessages + kept
}

/** Trims History entries to fit within the estimated context window (Gemini/Anthropic loops). */
fun trimHistoryForContext(
    history: List<History>,
    systemPromptChars: Int = 0,
    contextWindowTokens: Int = ModelCatalog.DEFAULT_CONTEXT_WINDOW_TOKENS,
): List<History> {
    val maxChars = contextWindowTokens * ChatLoopConstants.ESTIMATED_CHARS_PER_TOKEN
    val totalChars = history.sumOf { it.content.length } + systemPromptChars
    if (totalChars <= maxChars) return history
    val availableChars = maxChars - systemPromptChars
    val kept = mutableListOf<History>()
    var usedChars = 0
    for (msg in history.reversed()) {
        val msgChars = msg.content.length
        if (usedChars + msgChars > availableChars) break
        kept.add(0, msg)
        usedChars += msgChars
    }
    return kept
}

private fun estimateMessageChars(msg: com.inspiredandroid.kai.network.dtos.openaicompatible.OpenAICompatibleChatRequestDto.Message): Int {
    val contentChars = when (val content = msg.content) {
        is JsonArray -> {
            content.sumOf { element ->
                val obj = element as? JsonObject
                val type = (obj?.get("type") as? JsonPrimitive)?.content
                if (type == "text") {
                    (obj["text"] as? JsonPrimitive)?.content?.length ?: 0
                } else {
                    100 // Fixed small cost for image references
                }
            }
        }
        is JsonPrimitive -> content.content.length
        else -> content?.toString()?.length ?: 0
    }
    return contentChars + msg.role.length
}
