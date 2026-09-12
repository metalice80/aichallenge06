package com.example.aiagent.agent

import com.example.aiagent.config.OpenAiProperties
import com.example.aiagent.config.AgentConfiguration
import com.example.aiagent.llm.LlmClient
import com.example.aiagent.llm.LlmNetworkException
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
    private val llmClient = mockk<LlmClient>()
    private val conversation = Conversation()
    private val conversationRepository = mockk<ConversationRepository>(relaxed = true)
    private val properties = OpenAiProperties(
        apiKey = "test-key",
        model = "test-model",
        systemPrompt = "System instruction",
    )
    private val agent = ChatAgent(llmClient, conversation, conversationRepository, properties)

    @Test
    fun `first message includes system prompt and user message`() {
        val request = slot<LlmRequest>()
        every { llmClient.chat(capture(request)) } returns response("Привет!")

        val result = agent.sendMessage("  Привет  ")

        assertEquals(
            listOf(
                ChatMessage(Role.SYSTEM, "System instruction"),
                ChatMessage(Role.USER, "Привет"),
            ),
            request.captured.messages,
        )
        assertEquals("Привет!", result.content)
        assertEquals("test-model", result.model)
        assertEquals(10, result.inputTokens)
        assertEquals(5, result.outputTokens)
        assertEquals(15, result.totalTokens)
        assertTrue(result.responseTimeMs >= 0)
        verify(exactly = 1) { llmClient.chat(any()) }
    }

    @Test
    fun `successful model response is saved as assistant message`() {
        every { llmClient.chat(any()) } returns response("Привет! Чем могу помочь?")

        agent.sendMessage("Привет")

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
    fun `second message includes the complete previous exchange`() {
        val requests = mutableListOf<LlmRequest>()
        every { llmClient.chat(capture(requests)) } returnsMany listOf(
            response("JVM — это виртуальная машина Java."),
            response("Она исполняет байт-код."),
        )

        agent.sendMessage("Что такое JVM?")
        agent.sendMessage("А зачем она нужна?")

        assertEquals(
            listOf(
                ChatMessage(Role.SYSTEM, "System instruction"),
                ChatMessage(Role.USER, "Что такое JVM?"),
                ChatMessage(Role.ASSISTANT, "JVM — это виртуальная машина Java."),
                ChatMessage(Role.USER, "А зачем она нужна?"),
            ),
            requests[1].messages,
        )
        verify(exactly = 2) { llmClient.chat(any()) }
    }

    @Test
    fun `reset removes previous context but preserves system prompt`() {
        val requests = mutableListOf<LlmRequest>()
        every { llmClient.chat(capture(requests)) } returnsMany listOf(
            response("Первый ответ"),
            response("Новый ответ"),
        )

        agent.sendMessage("Первый вопрос")
        agent.reset()
        agent.sendMessage("Новый вопрос")

        assertEquals(
            listOf(
                ChatMessage(Role.SYSTEM, "System instruction"),
                ChatMessage(Role.USER, "Новый вопрос"),
            ),
            requests[1].messages,
        )
        assertEquals(
            listOf(
                ChatMessage(Role.USER, "Новый вопрос"),
                ChatMessage(Role.ASSISTANT, "Новый ответ"),
            ),
            conversation.messages(),
        )
        verify(exactly = 1) { conversationRepository.clear() }
    }

    @Test
    fun `failed request does not change conversation and can be retried`() {
        val failedRequest = slot<LlmRequest>()
        every { llmClient.chat(capture(failedRequest)) } throws LlmNetworkException(IOException("offline"))

        assertThrows(LlmNetworkException::class.java) {
            agent.sendMessage("Повтори запрос")
        }
        assertTrue(conversation.messages().isEmpty())
        verify(exactly = 0) { conversationRepository.save(any()) }

        val retryRequest = slot<LlmRequest>()
        every { llmClient.chat(capture(retryRequest)) } returns response("Готово")
        agent.sendMessage("Повтори запрос")

        assertEquals(failedRequest.captured.messages, retryRequest.captured.messages)
        assertEquals(2, conversation.messages().size)
    }

    @Test
    fun `history loaded at startup is included in the next request`() {
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
        val restoredAgent = ChatAgent(llmClient, restoredConversation, loadingRepository, properties)
        val request = slot<LlmRequest>()
        every { llmClient.chat(capture(request)) } returns response("Вы Алексей")

        restoredAgent.sendMessage("Как меня зовут?")

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
        every { llmClient.chat(any()) } returns response("Ответ")
        every { conversationRepository.save(any()) } throws IllegalStateException("database unavailable")

        assertThrows(IllegalStateException::class.java) {
            agent.sendMessage("Вопрос")
        }

        assertTrue(conversation.messages().isEmpty())
    }

    @Test
    fun `blank message is rejected without calling LLM`() {
        assertThrows(InvalidMessageException::class.java) {
            agent.sendMessage("   \n  ")
        }

        verify(exactly = 0) { llmClient.chat(any()) }
        assertTrue(conversation.messages().isEmpty())
    }

    private fun response(content: String) = LlmResponse(
        content = content,
        model = "test-model",
        inputTokens = 10,
        outputTokens = 5,
        totalTokens = 15,
    )
}
