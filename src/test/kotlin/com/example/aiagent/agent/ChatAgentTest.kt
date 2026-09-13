package com.example.aiagent.agent

import com.example.aiagent.config.AgentConfiguration
import com.example.aiagent.config.ContextCompressionProperties
import com.example.aiagent.config.LlmProperties
import com.example.aiagent.context.ConversationSummarizer
import com.example.aiagent.context.LlmConversationSummarizer
import com.example.aiagent.context.RollingConversationContextManager
import com.example.aiagent.llm.DefaultLlmClientResolver
import com.example.aiagent.llm.LlmClient
import com.example.aiagent.llm.LlmNetworkException
import com.example.aiagent.llm.LlmProvider
import com.example.aiagent.llm.LlmRequest
import com.example.aiagent.llm.LlmResponse
import com.example.aiagent.llm.TokenUsage
import com.example.aiagent.persistence.ConversationRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class ChatAgentTest {
    private val openAiClient = mockk<LlmClient> {
        every { provider } returns LlmProvider.OPENAI
        every { defaultModel } returns "openai-default"
    }
    private val openRouterClient = mockk<LlmClient> {
        every { provider } returns LlmProvider.OPENROUTER
        every { defaultModel } returns "router/default"
    }
    private val resolver = DefaultLlmClientResolver(listOf(openAiClient, openRouterClient))
    private val conversation = Conversation()
    private val conversationRepository = mockk<ConversationRepository>(relaxed = true)
    private val properties = LlmProperties(systemPrompt = "System instruction")
    private val summarizer = mockk<ConversationSummarizer>()
    private val contextManager = RollingConversationContextManager(
        ContextCompressionProperties(enabled = false),
        summarizer,
        conversationRepository,
    )
    private val agent = ChatAgent(
        resolver,
        conversation,
        conversationRepository,
        properties,
        contextManager,
    )

    @Test
    fun `OpenAI selection sends system prompt user message and chosen model only to OpenAI`() {
        val request = slot<LlmRequest>()
        every { openAiClient.chat(capture(request)) } returns response("Привет!", "gpt-test")

        val result = agent.sendMessage(agentRequest("  Привет  ", model = "gpt-test"))

        assertEquals("gpt-test", request.captured.model)
        assertEquals(
            listOf(
                ChatMessage(Role.SYSTEM, "System instruction"),
                ChatMessage(Role.USER, "Привет"),
            ),
            request.captured.messages,
        )
        assertEquals(LlmProvider.OPENAI, result.provider)
        verify(exactly = 1) { openAiClient.chat(any()) }
        verify(exactly = 0) { openRouterClient.chat(any()) }
    }

    @Test
    fun `OpenRouter selection forwards the chosen model`() {
        val request = slot<LlmRequest>()
        every { openRouterClient.chat(capture(request)) } returns response(
            "Ответ OpenRouter",
            "anthropic/claude-test",
        )

        agent.sendMessage(
            agentRequest(
                message = "Привет",
                provider = LlmProvider.OPENROUTER,
                model = "anthropic/claude-test",
            ),
        )

        assertEquals("anthropic/claude-test", request.captured.model)
        verify(exactly = 1) { openRouterClient.chat(any()) }
        verify(exactly = 0) { openAiClient.chat(any()) }
    }

    @Test
    fun `successful response persists the complete exchange and request usage`() {
        every { openAiClient.chat(any()) } returns response("Привет! Чем могу помочь?")
        val persistedUsage = slot<LlmRequestUsage>()

        agent.sendMessage(agentRequest("Привет"))

        assertEquals(
            listOf(
                ChatMessage(Role.USER, "Привет"),
                ChatMessage(Role.ASSISTANT, "Привет! Чем могу помочь?"),
            ),
            conversation.messages(),
        )
        verify(exactly = 1) { conversationRepository.save(conversation, capture(persistedUsage)) }
        assertEquals(LlmProvider.OPENAI, persistedUsage.captured.provider)
        assertEquals("test-model", persistedUsage.captured.model)
        assertEquals(TokenUsage(10, 5, 15), persistedUsage.captured.tokenUsage)
    }

    @Test
    fun `usage accumulates across providers and models while current usage stays separate`() {
        every { openAiClient.chat(any()) } returns response(
            content = "JVM — это виртуальная машина Java.",
            model = "gpt-test",
            usage = TokenUsage(100, 20, 120),
        )
        val routerRequest = slot<LlmRequest>()
        every { openRouterClient.chat(capture(routerRequest)) } returns response(
            content = "Она исполняет байт-код.",
            model = "google/gemini-test",
            usage = TokenUsage(180, 40, 220),
        )

        val first = agent.sendMessage(agentRequest("Что такое JVM?"))
        val second = agent.sendMessage(
            agentRequest(
                message = "А зачем она нужна?",
                provider = LlmProvider.OPENROUTER,
                model = "google/gemini-test",
            ),
        )

        assertEquals(TokenUsage(100, 20, 120), first.currentUsage)
        assertEquals(ConversationTokenUsage(100, 20, 120), first.conversationUsage)
        assertEquals(TokenUsage(180, 40, 220), second.currentUsage)
        assertEquals(ConversationTokenUsage(280, 60, 340), second.conversationUsage)
        assertEquals(
            listOf(
                ChatMessage(Role.SYSTEM, "System instruction"),
                ChatMessage(Role.USER, "Что такое JVM?"),
                ChatMessage(Role.ASSISTANT, "JVM — это виртуальная машина Java."),
                ChatMessage(Role.USER, "А зачем она нужна?"),
            ),
            routerRequest.captured.messages,
        )
    }

    @Test
    fun `response statistics use provider reported token usage`() {
        every { openRouterClient.chat(any()) } returns response(
            content = "Ответ",
            model = "openai/gpt-test",
            usage = TokenUsage(21, 8, 29),
        )

        val result = agent.sendMessage(
            agentRequest(
                message = "Вопрос",
                provider = LlmProvider.OPENROUTER,
                model = "openai/gpt-test",
            ),
        )

        assertEquals(LlmProvider.OPENROUTER, result.provider)
        assertEquals("openai/gpt-test", result.model)
        assertEquals(TokenUsage(21, 8, 29), result.currentUsage)
        assertEquals(ConversationTokenUsage(21, 8, 29), result.conversationUsage)
        assertTrue(result.responseTimeMs >= 0)
    }

    @Test
    fun `reset clears messages token usage and persisted state independently of provider`() {
        every { openAiClient.chat(any()) } returns response("Первый ответ")
        agent.sendMessage(agentRequest("Первый вопрос"))

        agent.reset()

        assertTrue(conversation.messages().isEmpty())
        assertEquals(ConversationTokenUsage.ZERO, conversation.tokenUsage())
        assertEquals(ConversationTokenUsage.ZERO, agent.state().conversationUsage)
        verify(exactly = 1) { conversationRepository.clear() }
    }

    @Test
    fun `failed LLM request leaves messages usage and persistence unchanged`() {
        every { openAiClient.chat(any()) } returns response(
            content = "Сохраненный ответ",
            usage = TokenUsage(100, 20, 120),
        )
        agent.sendMessage(agentRequest("Успешный запрос"))
        every { openRouterClient.chat(any()) } throws LlmNetworkException(
            LlmProvider.OPENROUTER,
            IOException("offline"),
        )

        assertThrows(LlmNetworkException::class.java) {
            agent.sendMessage(
                agentRequest(
                    message = "Повтори запрос",
                    provider = LlmProvider.OPENROUTER,
                    model = "openai/gpt-test",
                ),
            )
        }

        assertEquals(2, conversation.messages().size)
        assertEquals(ConversationTokenUsage(100, 20, 120), agent.state().conversationUsage)
        verify(exactly = 1) { conversationRepository.save(any(), any()) }
    }

    @Test
    fun `history and usage loaded at startup continue on a newly selected provider`() {
        val persistedConversation = Conversation().apply {
            restore(
                restoredMessages = listOf(
                    ChatMessage(Role.USER, "Меня зовут Алексей"),
                    ChatMessage(Role.ASSISTANT, "Приятно познакомиться, Алексей"),
                ),
                restoredTokenUsage = ConversationTokenUsage(90, 10, 100),
                restoredSummary = null,
            )
        }
        val loadingRepository = mockk<ConversationRepository>(relaxed = true)
        every { loadingRepository.load() } returns persistedConversation
        val restoredConversation = AgentConfiguration().conversation(loadingRepository)
        val restoredContextManager = RollingConversationContextManager(
            ContextCompressionProperties(enabled = false),
            summarizer,
            loadingRepository,
        )
        val restoredAgent = ChatAgent(
            resolver,
            restoredConversation,
            loadingRepository,
            properties,
            restoredContextManager,
        )
        val request = slot<LlmRequest>()
        every { openRouterClient.chat(capture(request)) } returns response("Вы Алексей", "openai/gpt-test")

        val result = restoredAgent.sendMessage(
            agentRequest(
                message = "Как меня зовут?",
                provider = LlmProvider.OPENROUTER,
                model = "openai/gpt-test",
            ),
        )

        assertEquals(
            listOf(
                ChatMessage(Role.SYSTEM, "System instruction"),
                ChatMessage(Role.USER, "Меня зовут Алексей"),
                ChatMessage(Role.ASSISTANT, "Приятно познакомиться, Алексей"),
                ChatMessage(Role.USER, "Как меня зовут?"),
            ),
            request.captured.messages,
        )
        assertEquals(ConversationTokenUsage(100, 15, 115), result.conversationUsage)
        verify(exactly = 1) { loadingRepository.load() }
    }

    @Test
    fun `persistence failure rolls messages and token usage back`() {
        every { openAiClient.chat(any()) } returns response("Ответ")
        every { conversationRepository.save(any(), any()) } throws IllegalStateException("database unavailable")

        assertThrows(IllegalStateException::class.java) {
            agent.sendMessage(agentRequest("Вопрос"))
        }

        assertTrue(conversation.messages().isEmpty())
        assertEquals(ConversationTokenUsage.ZERO, conversation.tokenUsage())
    }

    @Test
    fun `main and summary providers models and token usage remain independent`() {
        val mainRequest = slot<LlmRequest>()
        val summaryRequest = slot<LlmRequest>()
        every { openRouterClient.chat(capture(mainRequest)) } returns response(
            content = "Main answer",
            model = "anthropic/main-actual",
            usage = TokenUsage(21, 8, 29),
        )
        every { openAiClient.chat(capture(summaryRequest)) } returns response(
            content = "Summary v1",
            model = "summary-actual",
            usage = TokenUsage(100, 10, 110),
        )
        val compressionProperties = ContextCompressionProperties(
            summarizeAfterMessages = 2,
            summarizeEveryMessages = 1,
            provider = LlmProvider.OPENAI,
            model = "summary-model",
        )
        val summaryConversation = Conversation()
        val summaryContextManager = RollingConversationContextManager(
            compressionProperties,
            LlmConversationSummarizer(resolver, compressionProperties),
            conversationRepository,
        )
        val summaryAgent = ChatAgent(
            resolver,
            summaryConversation,
            conversationRepository,
            properties,
            summaryContextManager,
        )

        val result = summaryAgent.sendMessage(
            agentRequest(
                message = "Question",
                provider = LlmProvider.OPENROUTER,
                model = "anthropic/main-model",
            ),
        )

        assertEquals("anthropic/main-model", mainRequest.captured.model)
        assertEquals("summary-model", summaryRequest.captured.model)
        assertEquals(ConversationSummary("Summary v1", 1), summaryConversation.summary())
        assertEquals(TokenUsage(21, 8, 29), result.currentUsage)
        assertEquals(ConversationTokenUsage(21, 8, 29), result.conversationUsage)
        verify(exactly = 1) { openRouterClient.chat(any()) }
        verify(exactly = 1) { openAiClient.chat(any()) }
    }

    @Test
    fun `main LLM context contains summary and recent messages without summarized history`() {
        val previousMessages = (1..30).map { number ->
            ChatMessage(
                role = if (number % 2 == 1) Role.USER else Role.ASSISTANT,
                content = "Message $number",
            )
        }
        val summarizedConversation = Conversation().apply {
            restore(
                restoredMessages = previousMessages,
                restoredTokenUsage = ConversationTokenUsage.ZERO,
                restoredSummary = ConversationSummary("Summary v2", 20),
            )
        }
        val mainRequest = slot<LlmRequest>()
        every { openRouterClient.chat(capture(mainRequest)) } returns response("Answer")
        val summaryContextManager = RollingConversationContextManager(
            ContextCompressionProperties(
                summarizeAfterMessages = 200,
                summarizeEveryMessages = 100,
                model = "summary-model",
            ),
            summarizer,
            conversationRepository,
        )
        val summarizedAgent = ChatAgent(
            resolver,
            summarizedConversation,
            conversationRepository,
            properties,
            summaryContextManager,
        )

        summarizedAgent.sendMessage(
            agentRequest(
                message = "New question",
                provider = LlmProvider.OPENROUTER,
                model = "openai/main-model",
            ),
        )

        assertEquals(
            listOf(
                ChatMessage(Role.SYSTEM, "System instruction"),
                ChatMessage(
                    Role.SYSTEM,
                    "${RollingConversationContextManager.SUMMARY_CONTEXT_PREFIX}\nSummary v2",
                ),
            ) + previousMessages.drop(20) + ChatMessage(Role.USER, "New question"),
            mainRequest.captured.messages,
        )
        verify(exactly = 0) { summarizer.summarize(any(), any()) }
    }

    @Test
    fun `provider options expose configured defaults`() {
        assertEquals(
            listOf(
                LlmProviderOption(LlmProvider.OPENAI, "OpenAI", "openai-default"),
                LlmProviderOption(LlmProvider.OPENROUTER, "OpenRouter", "router/default"),
            ),
            agent.providers(),
        )
    }

    @Test
    fun `blank message is rejected without calling any provider`() {
        assertThrows(InvalidMessageException::class.java) {
            agent.sendMessage(agentRequest("   \n  "))
        }

        verify(exactly = 0) { openAiClient.chat(any()) }
        verify(exactly = 0) { openRouterClient.chat(any()) }
        assertTrue(conversation.messages().isEmpty())
        assertEquals(ConversationTokenUsage.ZERO, conversation.tokenUsage())
    }

    private fun agentRequest(
        message: String,
        provider: LlmProvider = LlmProvider.OPENAI,
        model: String = "gpt-test",
    ) = AgentRequest(message, provider, model)

    private fun response(
        content: String,
        model: String = "test-model",
        usage: TokenUsage = TokenUsage(10, 5, 15),
    ) = LlmResponse(
        content = content,
        model = model,
        usage = usage,
    )
}
