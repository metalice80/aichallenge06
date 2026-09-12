package com.example.aiagent.web

import com.example.aiagent.agent.Agent
import com.example.aiagent.agent.AgentResponse
import com.example.aiagent.agent.ChatMessage
import com.example.aiagent.agent.Role
import com.example.aiagent.web.dto.ChatRequest
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
        every { agent.sendMessage("Привет") } returns AgentResponse(
            content = "Здравствуйте",
            model = "test-model",
            inputTokens = 8,
            outputTokens = 3,
            totalTokens = 11,
            responseTimeMs = 125,
        )

        val response = controller.chat(ChatRequest("Привет"))

        assertEquals("Здравствуйте", response.content)
        assertEquals("test-model", response.model)
        assertEquals(8, response.inputTokens)
        assertEquals(3, response.outputTokens)
        assertEquals(11, response.totalTokens)
        assertEquals(125, response.responseTimeMs)
        verify(exactly = 1) { agent.sendMessage("Привет") }
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
    fun `reset delegates to agent and returns no content`() {
        every { agent.reset() } just runs

        val response = controller.reset()

        assertEquals(HttpStatus.NO_CONTENT, response.statusCode)
        assertNull(response.body)
        verify(exactly = 1) { agent.reset() }
    }
}
