package com.example.aiagent.memory

import com.example.aiagent.agent.ChatMessage
import com.example.aiagent.agent.Role
import com.example.aiagent.config.MemoryExtractorProperties
import com.example.aiagent.config.MemoryProperties
import com.example.aiagent.llm.InvalidLlmResponseException
import com.example.aiagent.llm.LlmClient
import com.example.aiagent.llm.LlmClientResolver
import com.example.aiagent.llm.LlmProvider
import com.example.aiagent.llm.LlmRequest
import com.example.aiagent.llm.LlmResponse
import com.example.aiagent.llm.StructuredOutputParser
import com.example.aiagent.llm.TokenUsage
import com.example.aiagent.task.AgentTask
import com.example.aiagent.task.TaskStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant

class LlmMemoryExtractorTest {
    private val client = mockk<LlmClient>()
    private val resolver = mockk<LlmClientResolver> {
        every { resolve(LlmProvider.OPENROUTER) } returns client
    }
    private val properties = MemoryProperties(
        extractor = MemoryExtractorProperties(
            provider = LlmProvider.OPENROUTER,
            model = "openai/memory-model",
            systemPrompt = "Classify WORKING and LONG_TERM",
        ),
    )
    private val extractor = LlmMemoryExtractor(
        resolver,
        properties,
        StructuredOutputParser(jacksonObjectMapper()),
    )
    private val task = AgentTask(
        id = 42,
        name = "Booking",
        status = TaskStatus.ACTIVE,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        completedAt = null,
        selected = true,
    )

    @Test
    fun `configured provider and model classify one message into both persistent layers`() {
        val request = slot<LlmRequest>()
        every { client.chat(capture(request)) } returns LlmResponse(
            content = """
                {
                  "working":{"upsert":[{"key":" database ","value":" PostgreSQL "}],"delete":[" old_goal "]},
                  "longTerm":{"upsert":[{"key":"preferred_code_language","value":"Kotlin"}],"delete":[]}
                }
            """.trimIndent(),
            model = "openai/memory-model",
            usage = TokenUsage(0, 0, 0),
        )

        val update = extractor.extract(
            userMessage = ChatMessage(
                Role.USER,
                "Для проекта PostgreSQL, а примеры я предпочитаю на Kotlin.",
            ),
            task = task,
            currentWorkingMemory = listOf(MemoryEntry("database", "SQLite")),
            currentLongTermMemory = emptyList(),
        )

        assertEquals(
            MemoryUpdate(
                working = MemoryLayerUpdate(
                    upsert = listOf(MemoryEntry("database", "PostgreSQL")),
                    delete = listOf("old_goal"),
                ),
                longTerm = MemoryLayerUpdate(
                    upsert = listOf(MemoryEntry("preferred_code_language", "Kotlin")),
                ),
            ),
            update,
        )
        assertEquals("openai/memory-model", request.captured.model)
        assertEquals(ChatMessage(Role.SYSTEM, "Classify WORKING and LONG_TERM"), request.captured.messages.first())
        assertEquals(true, request.captured.messages.last().content.contains("Current Task: Booking (id=42)"))
        assertEquals(true, request.captured.messages.last().content.contains("database = SQLite"))
        verify(exactly = 1) { resolver.resolve(LlmProvider.OPENROUTER) }
    }

    @Test
    fun `malformed structured response produces no partial MemoryUpdate`() {
        every { client.chat(any()) } returns LlmResponse(
            content = "not-json",
            model = "openai/memory-model",
            usage = TokenUsage(0, 0, 0),
        )

        assertThrows(InvalidLlmResponseException::class.java) {
            extractor.extract(
                ChatMessage(Role.USER, "Remember this"),
                task,
                emptyList(),
                emptyList(),
            )
        }
    }
}
