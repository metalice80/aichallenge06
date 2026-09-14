package com.example.aiagent.agent

import com.example.aiagent.config.LlmProperties
import com.example.aiagent.context.ContextStateService
import com.example.aiagent.context.branch.ConversationBranch
import com.example.aiagent.context.branch.ConversationBranchService
import com.example.aiagent.context.strategy.ContextStrategyResolver
import com.example.aiagent.context.strategy.ContextStrategyType
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
    private val contextStrategyResolver: ContextStrategyResolver,
    private val contextStateService: ContextStateService,
    private val branchService: ConversationBranchService,
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
        val previousTokenUsage = conversation.tokenUsage()
        val previousSummary = conversation.summary()
        val userMessage = ChatMessage(Role.USER, content)
        val contextStrategy = contextStrategyResolver.resolve(request.contextStrategy)
        val contextPlan = contextStrategy.buildContext(conversation)
        val llmRequest = LlmRequest(
            model = model,
            messages = buildList {
                add(ChatMessage(Role.SYSTEM, properties.systemPrompt.trim()))
                addAll(contextPlan.contextMessages)
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
        conversation.addUsage(llmResponse.usage)
        val requestUsage = LlmRequestUsage(
            provider = request.provider,
            model = llmResponse.model,
            tokenUsage = llmResponse.usage,
            responseTimeMs = responseTimeMs,
        )
        try {
            conversationRepository.save(conversation, requestUsage)
        } catch (exception: RuntimeException) {
            conversation.restore(previousMessages, previousTokenUsage, previousSummary)
            throw exception
        }
        contextStrategy.afterSuccessfulExchange(userMessage, assistantMessage)

        return AgentResponse(
            provider = request.provider,
            content = assistantContent,
            model = llmResponse.model,
            currentUsage = llmResponse.usage,
            conversationUsage = conversation.tokenUsage(),
            responseTimeMs = responseTimeMs,
        )
    }

    @Synchronized
    override fun history(): List<ChatMessage> = conversation.messages()

    @Synchronized
    override fun state(): AgentState = AgentState(
        messages = conversation.messages(),
        conversationUsage = conversation.tokenUsage(),
    )

    override fun providers(): List<LlmProviderOption> =
        llmClientResolver.availableClients().map { client ->
            LlmProviderOption(
                provider = client.provider,
                displayName = client.provider.displayName,
                defaultModel = client.defaultModel,
            )
        }

    override fun contextStrategies(): List<ContextStrategyType> =
        contextStrategyResolver.availableTypes()

    override fun branches(): List<ConversationBranch> = branchService.branches()

    @Synchronized
    override fun createBranch(): ConversationBranch =
        branchService.createBranch(conversation.messages())

    @Synchronized
    override fun activateBranch(branchId: Long): AgentState = AgentState(
        messages = branchService.activateBranch(branchId, conversation.messages()),
        conversationUsage = conversation.tokenUsage(),
    )

    @Synchronized
    override fun reset() {
        contextStateService.reset()
        conversation.clear()
    }

    companion object {
        const val MAX_MESSAGE_LENGTH = 4_000
        const val MAX_MODEL_LENGTH = 200
        private const val NANOS_PER_MILLISECOND = 1_000_000
    }
}
