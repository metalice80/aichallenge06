package com.example.aiagent.web

import com.example.aiagent.agent.Agent
import com.example.aiagent.agent.AgentState
import com.example.aiagent.agent.AgentRequest
import com.example.aiagent.agent.AgentResponse
import com.example.aiagent.agent.ChatMessage
import com.example.aiagent.agent.ConversationTokenUsage
import com.example.aiagent.agent.LlmProviderOption
import com.example.aiagent.agent.Role
import com.example.aiagent.llm.LlmProvider
import com.example.aiagent.llm.TokenUsage
import com.example.aiagent.web.dto.ChatRequest
import com.example.aiagent.web.dto.ConversationTokenUsageResponse
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

class ChatControllerTest {
    private val agent = mockk<Agent>()
    private val controller = ChatController(agent)

    @Test
    fun `chat delegates to agent and maps response`() {
        val request = AgentRequest("Привет", LlmProvider.OPENROUTER, "openai/gpt-test")
        every { agent.sendMessage(request) } returns AgentResponse(
            provider = LlmProvider.OPENROUTER,
            content = "Здравствуйте",
            model = "test-model",
            currentUsage = TokenUsage(8, 3, 11),
            conversationUsage = ConversationTokenUsage(108, 23, 131),
            responseTimeMs = 125,
        )

        val response = controller.chat(
            ChatRequest("Привет", LlmProvider.OPENROUTER, "openai/gpt-test"),
        )

        assertEquals(LlmProvider.OPENROUTER, response.provider)
        assertEquals("Здравствуйте", response.content)
        assertEquals("test-model", response.model)
        assertEquals(8L, response.currentUsage.inputTokens)
        assertEquals(3L, response.currentUsage.outputTokens)
        assertEquals(11L, response.currentUsage.totalTokens)
        assertEquals(108L, response.conversationUsage.inputTokens)
        assertEquals(23L, response.conversationUsage.outputTokens)
        assertEquals(131L, response.conversationUsage.totalTokens)
        assertEquals(125, response.responseTimeMs)
        verify(exactly = 1) { agent.sendMessage(request) }
    }

    @Test
    fun `history hides system messages and maps persisted chat`() {
        every { agent.history() } returns listOf(
            ChatMessage(Role.SYSTEM, "System"),
            ChatMessage(Role.USER, "Вопрос"),
            ChatMessage(Role.ASSISTANT, "Ответ"),
        )

        val response = controller.history()

        assertEquals(listOf(Role.USER, Role.ASSISTANT), response.map { it.role })
        assertEquals(listOf("Вопрос", "Ответ"), response.map { it.content })
        verify(exactly = 1) { agent.history() }
    }

    @Test
    fun `state restores visible messages and cumulative usage`() {
        every { agent.state() } returns AgentState(
            messages = listOf(
                ChatMessage(Role.SYSTEM, "System"),
                ChatMessage(Role.USER, "Вопрос"),
                ChatMessage(Role.ASSISTANT, "Ответ"),
            ),
            conversationUsage = ConversationTokenUsage(280, 60, 340),
        )

        val response = controller.state()

        assertEquals(listOf(Role.USER, Role.ASSISTANT), response.messages.map { it.role })
        assertEquals(listOf("Вопрос", "Ответ"), response.messages.map { it.content })
        assertEquals(ConversationTokenUsageResponse(280, 60, 340), response.conversationUsage)
        verify(exactly = 1) { agent.state() }
    }

    @Test
    fun `providers expose configured model defaults`() {
        every { agent.providers() } returns listOf(
            LlmProviderOption(LlmProvider.OPENAI, "OpenAI", "gpt-default"),
            LlmProviderOption(LlmProvider.OPENROUTER, "OpenRouter", "openai/router-default"),
        )

        val response = controller.providers()

        assertEquals(listOf(LlmProvider.OPENAI, LlmProvider.OPENROUTER), response.map { it.provider })
        assertEquals(listOf("gpt-default", "openai/router-default"), response.map { it.defaultModel })
    }

    @Test
    fun `reset delegates to agent and returns no content`() {
        every { agent.reset() } just runs

        val response = controller.reset()

        assertEquals(HttpStatus.NO_CONTENT, response.statusCode)
        assertNull(response.body)
        verify(exactly = 1) { agent.reset() }
    }
}
