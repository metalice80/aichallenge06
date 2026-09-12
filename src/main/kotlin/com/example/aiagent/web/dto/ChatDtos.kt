package com.example.aiagent.web.dto

import com.example.aiagent.agent.AgentResponse
import com.example.aiagent.agent.AgentState
import com.example.aiagent.agent.ChatAgent
import com.example.aiagent.agent.ChatMessage
import com.example.aiagent.agent.ConversationTokenUsage
import com.example.aiagent.agent.LlmProviderOption
import com.example.aiagent.agent.Role
import com.example.aiagent.llm.LlmProvider
import com.example.aiagent.llm.TokenUsage
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class ChatRequest(
    @field:NotBlank(message = "Сообщение не должно быть пустым.")
    @field:Size(
        max = ChatAgent.MAX_MESSAGE_LENGTH,
        message = "Сообщение не должно превышать ${ChatAgent.MAX_MESSAGE_LENGTH} символов.",
    )
    val message: String,
    val provider: LlmProvider,
    @field:NotBlank(message = "Модель не должна быть пустой.")
    @field:Size(
        max = ChatAgent.MAX_MODEL_LENGTH,
        message = "Название модели не должно превышать ${ChatAgent.MAX_MODEL_LENGTH} символов.",
    )
    val model: String,
)

data class TokenUsageResponse(
    val inputTokens: Long?,
    val outputTokens: Long?,
    val totalTokens: Long?,
) {
    companion object {
        fun from(usage: TokenUsage): TokenUsageResponse = TokenUsageResponse(
            inputTokens = usage.inputTokens,
            outputTokens = usage.outputTokens,
            totalTokens = usage.totalTokens,
        )
    }
}

data class ConversationTokenUsageResponse(
    val inputTokens: Long,
    val outputTokens: Long,
    val totalTokens: Long,
) {
    companion object {
        fun from(usage: ConversationTokenUsage): ConversationTokenUsageResponse =
            ConversationTokenUsageResponse(
                inputTokens = usage.inputTokens,
                outputTokens = usage.outputTokens,
                totalTokens = usage.totalTokens,
            )
    }
}

data class ChatResponse(
    val provider: LlmProvider,
    val content: String,
    val model: String,
    val currentUsage: TokenUsageResponse,
    val conversationUsage: ConversationTokenUsageResponse,
    val responseTimeMs: Long,
) {
    companion object {
        fun from(response: AgentResponse): ChatResponse = ChatResponse(
            provider = response.provider,
            content = response.content,
            model = response.model,
            currentUsage = TokenUsageResponse.from(response.currentUsage),
            conversationUsage = ConversationTokenUsageResponse.from(response.conversationUsage),
            responseTimeMs = response.responseTimeMs,
        )
    }
}

data class LlmProviderOptionResponse(
    val provider: LlmProvider,
    val displayName: String,
    val defaultModel: String,
) {
    companion object {
        fun from(option: LlmProviderOption): LlmProviderOptionResponse = LlmProviderOptionResponse(
            provider = option.provider,
            displayName = option.displayName,
            defaultModel = option.defaultModel,
        )
    }
}

data class ChatHistoryResponse(
    val role: Role,
    val content: String,
) {
    companion object {
        fun from(message: ChatMessage): ChatHistoryResponse = ChatHistoryResponse(
            role = message.role,
            content = message.content,
        )
    }
}

data class ChatStateResponse(
    val messages: List<ChatHistoryResponse>,
    val conversationUsage: ConversationTokenUsageResponse,
) {
    companion object {
        fun from(state: AgentState): ChatStateResponse = ChatStateResponse(
            messages = state.messages
                .filterNot { it.role == Role.SYSTEM }
                .map(ChatHistoryResponse::from),
            conversationUsage = ConversationTokenUsageResponse.from(state.conversationUsage),
        )
    }
}

data class ApiError(
    val message: String,
)
