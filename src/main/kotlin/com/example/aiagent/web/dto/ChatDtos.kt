package com.example.aiagent.web.dto

import com.example.aiagent.agent.AgentResponse
import com.example.aiagent.agent.ChatAgent
import com.example.aiagent.agent.ChatMessage
import com.example.aiagent.agent.LlmProviderOption
import com.example.aiagent.agent.Role
import com.example.aiagent.llm.LlmProvider
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

data class ChatResponse(
    val provider: LlmProvider,
    val content: String,
    val model: String,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val totalTokens: Int?,
    val responseTimeMs: Long,
) {
    companion object {
        fun from(response: AgentResponse): ChatResponse = ChatResponse(
            provider = response.provider,
            content = response.content,
            model = response.model,
            inputTokens = response.inputTokens,
            outputTokens = response.outputTokens,
            totalTokens = response.totalTokens,
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

data class ApiError(
    val message: String,
)
