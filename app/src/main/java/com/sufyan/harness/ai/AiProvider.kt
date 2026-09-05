package com.sufyan.harness.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

@Serializable
data class ChatMessage(
    val role: String, // system | user | assistant | tool
    val content: String,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
    val name: String? = null,
)

@Serializable
data class ToolSchema(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

@Serializable
data class ModelInfo(
    val id: String,
    val name: String,
    @SerialName("context_length") val contextLength: Int? = null,
    val promptPrice: Double? = null,
    val completionPrice: Double? = null,
) {
    val provider: String get() = id.substringBefore('/', "")
    val isFree: Boolean get() = (promptPrice ?: 0.0) == 0.0 && (completionPrice ?: 0.0) == 0.0
}

/** Incremental events emitted while a completion streams. */
sealed interface StreamEvent {
    data class Text(val delta: String) : StreamEvent
    data class Tools(val calls: List<ToolCall>) : StreamEvent
    data class Failed(val error: AiError) : StreamEvent
    data object Done : StreamEvent
}

data class AiError(
    val title: String,
    val detail: String,
    val retryable: Boolean,
)

/** Provider abstraction — OpenRouter is the first implementation, not the only possible one. */
interface AiProvider {
    val displayName: String
    suspend fun listModels(): Result<List<ModelInfo>>
    fun stream(
        model: String,
        messages: List<ChatMessage>,
        tools: List<ToolSchema>,
        temperature: Float,
    ): Flow<StreamEvent>
}
