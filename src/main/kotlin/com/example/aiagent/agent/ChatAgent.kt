package com.example.aiagent.agent

import com.example.aiagent.config.OpenAiProperties
import com.example.aiagent.llm.InvalidLlmResponseException
import com.example.aiagent.llm.LlmClient
import com.example.aiagent.llm.LlmRequest
import org.springframework.stereotype.Service

@Service
class ChatAgent(
    private val llmClient: LlmClient,
    private val conversation: Conversation,
    private val properties: OpenAiProperties,
) : Agent {

    @Synchronized
    override fun sendMessage(message: String): AgentResponse {
        val content = message.trim()
        if (content.isEmpty()) {
            throw InvalidMessageException("Message must not be blank")
        }
        if (content.length > MAX_MESSAGE_LENGTH) {
            throw InvalidMessageException("Message must not exceed $MAX_MESSAGE_LENGTH characters")
        }

        val userMessage = ChatMessage(Role.USER, content)
        val request = LlmRequest(
            messages = buildList {
                add(ChatMessage(Role.SYSTEM, properties.systemPrompt.trim()))
                addAll(conversation.messages())
                add(userMessage)
            },
        )

        val startedAt = System.nanoTime()
        val llmResponse = llmClient.chat(request)
        val responseTimeMs = (System.nanoTime() - startedAt) / NANOS_PER_MILLISECOND
        val assistantContent = llmResponse.content.trim()
        if (assistantContent.isEmpty()) {
            throw InvalidLlmResponseException()
        }

        val assistantMessage = ChatMessage(Role.ASSISTANT, assistantContent)
        conversation.addAll(listOf(userMessage, assistantMessage))

        return AgentResponse(
            content = assistantContent,
            model = llmResponse.model,
            inputTokens = llmResponse.inputTokens,
            outputTokens = llmResponse.outputTokens,
            totalTokens = llmResponse.totalTokens,
            responseTimeMs = responseTimeMs,
        )
    }

    @Synchronized
    override fun reset() {
        conversation.clear()
    }

    companion object {
        const val MAX_MESSAGE_LENGTH = 4_000
        private const val NANOS_PER_MILLISECOND = 1_000_000
    }
}
