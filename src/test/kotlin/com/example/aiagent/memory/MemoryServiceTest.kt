package com.example.aiagent.memory

import com.example.aiagent.agent.ChatMessage
import com.example.aiagent.agent.Role
import com.example.aiagent.config.MemoryProperties
import com.example.aiagent.context.strategy.ContextPlan
import com.example.aiagent.context.strategy.ContextStrategyType
import com.example.aiagent.task.AgentTask
import com.example.aiagent.task.TaskStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class MemoryServiceTest {
    private val repository = mockk<MemoryRepository>(relaxed = true)
    private val extractor = mockk<MemoryExtractor>()
    private val service = MemoryService(
        repository,
        extractor,
        MemoryProperties(enabled = true),
        SecretRedactor(),
    )
    private val task = AgentTask(
        id = 7,
        name = "Payments",
        status = TaskStatus.ACTIVE,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        completedAt = null,
        selected = true,
    )

    @Test
    fun `context keeps long-term and working outside short-term with explicit precedence`() {
        every { repository.findLongTerm() } returns listOf(
            MemoryEntry("preferred_code_language", "Kotlin"),
        )
        every { repository.findWorking(7) } returns listOf(
            MemoryEntry("language", "Java"),
        )

        val context = service.context(task)

        assertEquals(2, context.messages.size)
        assertTrue(context.messages[0].content.contains("lowest memory priority"))
        assertTrue(context.messages[0].content.contains("preferred_code_language = Kotlin"))
        assertTrue(context.messages[1].content.contains("overrides Long-Term Memory"))
        assertTrue(context.messages[1].content.contains("language = Java"))
        assertTrue(context.messages[1].content.contains("current user message overrides"))
    }

    @Test
    fun `effective context persists logical sections and redacts secrets`() {
        val captured = slot<EffectiveContext>()
        every { repository.saveEffectiveContext(capture(captured)) } returns Unit
        val memoryContext = MemoryContext(
            longTerm = listOf(MemoryEntry("preferred_language", "Russian")),
            working = listOf(MemoryEntry("api_key", "sk-secret123456")),
            messages = emptyList(),
        )

        service.recordEffectiveContext(
            task = task,
            strategy = ContextStrategyType.SLIDING_WINDOW,
            systemPrompt = "Use Authorization: Bearer abcdefghijklmnop",
            memoryContext = memoryContext,
            contextPlan = ContextPlan(listOf(ChatMessage(Role.ASSISTANT, "previous"))),
            currentUserMessage = ChatMessage(Role.USER, "token=sk-current123456"),
        )

        assertTrue(captured.captured.systemPrompt.contains("[REDACTED]"))
        assertFalse(captured.captured.systemPrompt.contains("abcdefghijklmnop"))
        assertEquals("[REDACTED]", captured.captured.workingMemory.single().value)
        assertTrue(captured.captured.currentUserMessage.content.contains("[REDACTED]"))
        assertFalse(captured.captured.currentUserMessage.content.contains("sk-current123456"))
        assertEquals(listOf(ChatMessage(Role.ASSISTANT, "previous")), captured.captured.shortTerm)
    }

    @Test
    fun `extractor failure records diagnostics and never applies a memory mutation`() {
        every { repository.findWorking(7) } returns listOf(MemoryEntry("database", "PostgreSQL"))
        every { repository.findLongTerm() } returns listOf(MemoryEntry("answer_language", "Russian"))
        every { extractor.extract(any(), any(), any(), any()) } throws IllegalStateException("offline")

        service.extractAfterSuccessfulExchange(task, ChatMessage(Role.USER, "Use MySQL"))

        verify(exactly = 0) { repository.apply(any(), any(), any()) }
        verify(exactly = 1) { repository.recordFailure(7, "Use MySQL", "IllegalStateException") }
    }
}
