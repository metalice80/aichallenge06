package com.example.aiagent.agent

import com.example.aiagent.config.AgentConfiguration
import com.example.aiagent.config.LlmProperties
import com.example.aiagent.llm.DefaultLlmClientResolver
import com.example.aiagent.llm.LlmClient
import com.example.aiagent.llm.LlmNetworkException
import com.example.aiagent.llm.LlmProvider
import com.example.aiagent.llm.LlmRequest
import com.example.aiagent.llm.LlmResponse
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
    private val agent = ChatAgent(resolver, conversation, conversationRepository, properties)

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
    fun `successful response is persisted as complete exchange`() {
        every { openAiClient.chat(any()) } returns response("Привет! Чем могу помочь?")

        agent.sendMessage(agentRequest("Привет"))

        assertEquals(
            listOf(
                ChatMessage(Role.USER, "Привет"),
                ChatMessage(Role.ASSISTANT, "Привет! Чем могу помочь?"),
            ),
            conversation.messages(),
        )
        verify(exactly = 1) { conversationRepository.save(conversation) }
    }

    @Test
    fun `provider can change without losing shared conversation`() {
        val routerRequest = slot<LlmRequest>()
        every { openAiClient.chat(any()) } returns response("JVM — это виртуальная машина Java.")
        every { openRouterClient.chat(capture(routerRequest)) } returns response(
            "Она исполняет байт-код.",
            "google/gemini-test",
        )

        agent.sendMessage(agentRequest("Что такое JVM?"))
        agent.sendMessage(
            agentRequest(
                message = "А зачем она нужна?",
                provider = LlmProvider.OPENROUTER,
                model = "google/gemini-test",
            ),
        )

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
    fun `response statistics include selected provider and model usage`() {
        every { openRouterClient.chat(any()) } returns LlmResponse(
            content = "Ответ",
            model = "openai/gpt-test",
            inputTokens = 21,
            outputTokens = 8,
            totalTokens = 29,
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
        assertEquals(21, result.inputTokens)
        assertEquals(8, result.outputTokens)
        assertEquals(29, result.totalTokens)
        assertTrue(result.responseTimeMs >= 0)
    }

    @Test
    fun `reset clears persisted conversation independently of provider`() {
        every { openAiClient.chat(any()) } returns response("Первый ответ")
        agent.sendMessage(agentRequest("Первый вопрос"))

        agent.reset()

        assertTrue(conversation.messages().isEmpty())
        verify(exactly = 1) { conversationRepository.clear() }
    }

    @Test
    fun `failed LLM request leaves memory and persistence unchanged`() {
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

        assertTrue(conversation.messages().isEmpty())
        verify(exactly = 0) { conversationRepository.save(any()) }
    }

    @Test
    fun `history loaded at startup is sent to a newly selected provider`() {
        val persistedConversation = Conversation().apply {
            addAll(
                listOf(
                    ChatMessage(Role.USER, "Меня зовут Алексей"),
                    ChatMessage(Role.ASSISTANT, "Приятно познакомиться, Алексей"),
                ),
            )
        }
        val loadingRepository = mockk<ConversationRepository>(relaxed = true)
        every { loadingRepository.load() } returns persistedConversation
        val restoredConversation = AgentConfiguration().conversation(loadingRepository)
        val restoredAgent = ChatAgent(resolver, restoredConversation, loadingRepository, properties)
        val request = slot<LlmRequest>()
        every { openRouterClient.chat(capture(request)) } returns response("Вы Алексей", "openai/gpt-test")

        restoredAgent.sendMessage(
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
        verify(exactly = 1) { loadingRepository.load() }
    }

    @Test
    fun `persistence failure rolls in-memory conversation back`() {
        every { openAiClient.chat(any()) } returns response("Ответ")
        every { conversationRepository.save(any()) } throws IllegalStateException("database unavailable")

        assertThrows(IllegalStateException::class.java) {
            agent.sendMessage(agentRequest("Вопрос"))
        }

        assertTrue(conversation.messages().isEmpty())
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
    }

    private fun agentRequest(
        message: String,
        provider: LlmProvider = LlmProvider.OPENAI,
        model: String = "gpt-test",
    ) = AgentRequest(message, provider, model)

    private fun response(content: String, model: String = "test-model") = LlmResponse(
        content = content,
        model = model,
        inputTokens = 10,
        outputTokens = 5,
        totalTokens = 15,
    )
}
