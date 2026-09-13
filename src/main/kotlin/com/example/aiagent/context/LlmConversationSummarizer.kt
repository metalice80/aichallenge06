package com.example.aiagent.context

import com.example.aiagent.agent.ChatMessage
import com.example.aiagent.agent.Role
import com.example.aiagent.config.ContextCompressionProperties
import com.example.aiagent.llm.InvalidLlmResponseException
import com.example.aiagent.llm.LlmClientResolver
import com.example.aiagent.llm.LlmRequest
import org.springframework.stereotype.Component

@Component
class LlmConversationSummarizer(
    private val llmClientResolver: LlmClientResolver,
    private val properties: ContextCompressionProperties,
) : ConversationSummarizer {
    override fun summarize(
        currentSummary: String?,
        messages: List<ChatMessage>,
    ): String {
        require(messages.isNotEmpty()) { "At least one message is required for summarization" }

        val response = llmClientResolver.resolve(properties.provider).chat(
            LlmRequest(
                model = properties.model.trim(),
                messages = listOf(
                    ChatMessage(Role.SYSTEM, properties.systemPrompt.trim()),
                    ChatMessage(Role.USER, buildSummaryRequest(currentSummary, messages)),
                ),
            ),
        )
        val summary = response.content.trim()
        if (summary.isEmpty()) {
            throw InvalidLlmResponseException(properties.provider)
        }
        return summary
    }

    private fun buildSummaryRequest(
        currentSummary: String?,
        messages: List<ChatMessage>,
    ): String = buildString {
        if (currentSummary == null) {
            appendLine("Создай первое полное summary для следующих сообщений диалога:")
        } else {
            appendLine("Текущее rolling summary:")
            appendLine(currentSummary)
            appendLine()
            appendLine("Обнови его следующей порцией сообщений:")
        }
        messages.forEach { message ->
            append('[')
            append(message.role.name)
            appendLine(']')
            appendLine(message.content)
        }
        appendLine()
        append("Верни только новое самостоятельное полное summary, заменяющее предыдущее.")
    }
}
