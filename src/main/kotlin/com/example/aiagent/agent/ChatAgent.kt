package com.example.aiagent.agent

import com.example.aiagent.config.LlmProperties
import com.example.aiagent.llm.InvalidLlmResponseException
import com.example.aiagent.llm.LlmClientResolver
import com.example.aiagent.llm.LlmRequest
import com.example.aiagent.persistence.ConversationRepository
import org.springframework.stereotype.Service

@Service
class ChatAgent(
    private val llmClientResolver: LlmClientResolver,
    private val conversation: Conversation,
    private val conversationRepository: ConversationRepository,
    private val properties: LlmProperties,
) : Agent {

    @Synchronized
    override fun sendMessage(request: AgentRequest): AgentResponse {
        val content = request.message.trim()
        if (content.isEmpty()) {
            throw InvalidMessageException("Message must not be blank")
        }
        if (content.length > MAX_MESSAGE_LENGTH) {
            throw InvalidMessageException("Message must not exceed $MAX_MESSAGE_LENGTH characters")
        }
        val model = request.model.trim()
        if (model.isEmpty()) {
            throw InvalidMessageException("Model must not be blank")
        }
        if (model.length > MAX_MODEL_LENGTH) {
            throw InvalidMessageException("Model must not exceed $MAX_MODEL_LENGTH characters")
        }

        val previousMessages = conversation.messages()
        val userMessage = ChatMessage(Role.USER, content)
        val llmRequest = LlmRequest(
            model = model,
            messages = buildList {
                add(ChatMessage(Role.SYSTEM, properties.systemPrompt.trim()))
                addAll(previousMessages)
                add(userMessage)
            },
        )

        val startedAt = System.nanoTime()
        val llmClient = llmClientResolver.resolve(request.provider)
        val llmResponse = llmClient.chat(llmRequest)
        val responseTimeMs = (System.nanoTime() - startedAt) / NANOS_PER_MILLISECOND
        val assistantContent = llmResponse.content.trim()
        if (assistantContent.isEmpty()) {
            throw InvalidLlmResponseException(request.provider)
        }

        val assistantMessage = ChatMessage(Role.ASSISTANT, assistantContent)
        conversation.addAll(listOf(userMessage, assistantMessage))
        try {
            conversationRepository.save(conversation)
        } catch (exception: RuntimeException) {
            conversation.clear()
            conversation.addAll(previousMessages)
            throw exception
        }

        return AgentResponse(
            provider = request.provider,
            content = assistantContent,
            model = llmResponse.model,
            inputTokens = llmResponse.inputTokens,
            outputTokens = llmResponse.outputTokens,
            totalTokens = llmResponse.totalTokens,
            responseTimeMs = responseTimeMs,
        )
    }

    @Synchronized
    override fun history(): List<ChatMessage> = conversation.messages()

    override fun providers(): List<LlmProviderOption> =
        llmClientResolver.availableClients().map { client ->
            LlmProviderOption(
                provider = client.provider,
                displayName = client.provider.displayName,
                defaultModel = client.defaultModel,
            )
        }

    @Synchronized
    override fun reset() {
        conversationRepository.clear()
        conversation.clear()
    }

    companion object {
        const val MAX_MESSAGE_LENGTH = 4_000
        const val MAX_MODEL_LENGTH = 200
        private const val NANOS_PER_MILLISECOND = 1_000_000
    }
}
