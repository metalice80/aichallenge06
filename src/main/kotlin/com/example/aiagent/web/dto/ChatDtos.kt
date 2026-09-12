package com.example.aiagent.web.dto

import com.example.aiagent.agent.AgentResponse
import com.example.aiagent.agent.ChatAgent
import com.example.aiagent.agent.ChatMessage
import com.example.aiagent.agent.Role
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class ChatRequest(
    @field:NotBlank(message = "Сообщение не должно быть пустым.")
    @field:Size(
        max = ChatAgent.MAX_MESSAGE_LENGTH,
        message = "Сообщение не должно превышать ${ChatAgent.MAX_MESSAGE_LENGTH} символов.",
    )
    val message: String,
)

data class ChatResponse(
    val content: String,
    val model: String,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val totalTokens: Int?,
    val responseTimeMs: Long,
) {
    companion object {
        fun from(response: AgentResponse): ChatResponse = ChatResponse(
            content = response.content,
            model = response.model,
            inputTokens = response.inputTokens,
            outputTokens = response.outputTokens,
            totalTokens = response.totalTokens,
            responseTimeMs = response.responseTimeMs,
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
