package com.example.aiagent.context

import com.example.aiagent.agent.ChatMessage
import com.example.aiagent.agent.Role
import com.example.aiagent.config.ContextCompressionProperties
import com.example.aiagent.llm.DefaultLlmClientResolver
import com.example.aiagent.llm.LlmClient
import com.example.aiagent.llm.LlmProvider
import com.example.aiagent.llm.LlmRequest
import com.example.aiagent.llm.LlmResponse
import com.example.aiagent.llm.TokenUsage
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LlmConversationSummarizerTest {
    private val openAiClient = mockk<LlmClient> {
        every { provider } returns LlmProvider.OPENAI
        every { defaultModel } returns "openai-default"
    }
    private val openRouterClient = mockk<LlmClient> {
        every { provider } returns LlmProvider.OPENROUTER
        every { defaultModel } returns "openrouter/default"
    }
    private val resolver = DefaultLlmClientResolver(listOf(openAiClient, openRouterClient))

    @Test
    fun `summarizer uses configured provider model prompt existing summary and message chunk`() {
        val request = slot<LlmRequest>()
        every { openAiClient.chat(capture(request)) } returns LlmResponse(
            content = "  Summary v2  ",
            model = "summary-model-actual",
            usage = TokenUsage(500, 50, 550),
        )
        val summarizer = LlmConversationSummarizer(
            resolver,
            ContextCompressionProperties(
                provider = LlmProvider.OPENAI,
                model = "summary-model",
                systemPrompt = "Summary system instruction",
            ),
        )

        val result = summarizer.summarize(
            currentSummary = "Summary v1",
            messages = listOf(
                ChatMessage(Role.USER, "Question 6"),
                ChatMessage(Role.ASSISTANT, "Answer 6"),
            ),
        )

        assertEquals("Summary v2", result)
        assertEquals("summary-model", request.captured.model)
        assertEquals(ChatMessage(Role.SYSTEM, "Summary system instruction"), request.captured.messages[0])
        val updatePrompt = request.captured.messages[1]
        assertEquals(Role.USER, updatePrompt.role)
        assertTrue(updatePrompt.content.contains("Summary v1"))
        assertTrue(updatePrompt.content.contains("[USER]\nQuestion 6"))
        assertTrue(updatePrompt.content.contains("[ASSISTANT]\nAnswer 6"))
        assertTrue(updatePrompt.content.contains("новое самостоятельное полное summary"))
        verify(exactly = 1) { openAiClient.chat(any()) }
        verify(exactly = 0) { openRouterClient.chat(any()) }
    }

    @Test
    fun `summarizer can independently select OpenRouter`() {
        val request = slot<LlmRequest>()
        every { openRouterClient.chat(capture(request)) } returns LlmResponse(
            content = "Summary",
            model = "openai/summary-actual",
            usage = TokenUsage(50, 5, 55),
        )
        val summarizer = LlmConversationSummarizer(
            resolver,
            ContextCompressionProperties(
                provider = LlmProvider.OPENROUTER,
                model = "openai/summary-model",
            ),
        )

        summarizer.summarize(null, listOf(ChatMessage(Role.USER, "Question")))

        assertEquals("openai/summary-model", request.captured.model)
        assertTrue(request.captured.messages[1].content.contains("первое полное summary"))
        verify(exactly = 1) { openRouterClient.chat(any()) }
        verify(exactly = 0) { openAiClient.chat(any()) }
    }
}
