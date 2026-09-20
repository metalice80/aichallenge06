package com.example.aiagent.context

import com.example.aiagent.agent.ChatMessage
import com.example.aiagent.agent.Role
import com.example.aiagent.config.ContextStrategiesProperties
import com.example.aiagent.config.FactsExtractorProperties
import com.example.aiagent.config.StickyFactsStrategyProperties
import com.example.aiagent.context.facts.FactsUpdate
import com.example.aiagent.context.facts.LlmFactsExtractor
import com.example.aiagent.context.facts.MemoryFact
import com.example.aiagent.llm.InvalidLlmResponseException
import com.example.aiagent.llm.LlmClient
import com.example.aiagent.llm.LlmClientResolver
import com.example.aiagent.llm.LlmProvider
import com.example.aiagent.llm.LlmRequest
import com.example.aiagent.llm.LlmResponse
import com.example.aiagent.llm.TokenUsage
import com.example.aiagent.llm.StructuredOutputParser
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper

class LlmFactsExtractorTest {
    private val client = mockk<LlmClient>()
    private val resolver = mockk<LlmClientResolver> {
        every { resolve(LlmProvider.OPENROUTER) } returns client
    }
    private val properties = ContextStrategiesProperties(
        stickyFacts = StickyFactsStrategyProperties(
            extractor = FactsExtractorProperties(
                provider = LlmProvider.OPENROUTER,
                model = "facts/model",
                systemPrompt = "Extract durable facts",
            ),
        ),
    )
    private val extractor = LlmFactsExtractor(
        resolver,
        properties,
        StructuredOutputParser(jacksonObjectMapper()),
    )

    @Test
    fun `extractor uses its configured provider model prompt and maps upserts and deletes`() {
        val request = slot<LlmRequest>()
        every { client.chat(capture(request)) } returns LlmResponse(
            content = """{"upsert":[{"key":" language ","value":" Kotlin "}],"delete":[" old "]}""",
            model = "facts/model",
            usage = TokenUsage(0, 0, 0),
        )

        val update = extractor.extract(
            existingFacts = listOf(MemoryFact("database", "SQLite")),
            userMessage = ChatMessage(Role.USER, "I prefer Kotlin now"),
        )

        assertEquals(
            FactsUpdate(
                upsert = listOf(MemoryFact("language", "Kotlin")),
                deleteKeys = listOf("old"),
            ),
            update,
        )
        assertEquals("facts/model", request.captured.model)
        assertEquals(ChatMessage(Role.SYSTEM, "Extract durable facts"), request.captured.messages.first())
        assertEquals(Role.USER, request.captured.messages.last().role)
        assertEquals(true, request.captured.messages.last().content.contains("database = SQLite"))
        assertEquals(true, request.captured.messages.last().content.contains("I prefer Kotlin now"))
        verify(exactly = 1) { resolver.resolve(LlmProvider.OPENROUTER) }
    }

    @Test
    fun `malformed extractor response fails without producing a partial update`() {
        every { client.chat(any()) } returns LlmResponse(
            content = "not-json",
            model = "facts/model",
            usage = TokenUsage(0, 0, 0),
        )

        assertThrows(InvalidLlmResponseException::class.java) {
            extractor.extract(emptyList(), ChatMessage(Role.USER, "Remember this"))
        }
    }
}
