package com.example.aiagent.memory

import com.example.aiagent.agent.ChatMessage
import com.example.aiagent.agent.Role
import com.example.aiagent.config.MemoryProperties
import com.example.aiagent.task.AgentTask
import com.example.aiagent.task.TaskStatus
import com.example.aiagent.profile.ExpertiseLevel
import com.example.aiagent.profile.ResponseFormat
import com.example.aiagent.profile.ResponseLanguage
import com.example.aiagent.profile.ResponseStyle
import com.example.aiagent.profile.UserProfileSnapshot
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

        assertEquals(listOf(MemoryEntry("preferred_code_language", "Kotlin")), context.longTerm)
        assertEquals(listOf(MemoryEntry("language", "Java")), context.working)
    }

    @Test
    fun `effective context persists logical sections and redacts secrets`() {
        val captured = slot<EffectiveContext>()
        every { repository.saveEffectiveContext(capture(captured)) } returns Unit
        val context = EffectiveContext(
            taskId = task.id,
            strategy = com.example.aiagent.context.strategy.ContextStrategyType.SLIDING_WINDOW,
            systemPrompt = "Use Authorization: Bearer abcdefghijklmnop",
            longTermMemory = listOf(MemoryEntry("preferred_language", "Russian")),
            userProfile = UserProfileSnapshot(
                id = 3,
                name = "Developer",
                responseLanguage = ResponseLanguage.RUSSIAN,
                expertiseLevel = ExpertiseLevel.ADVANCED,
                responseStyle = ResponseStyle.CONCISE,
                responseFormat = ResponseFormat.CODE_FIRST,
                customInstructions = "token=sk-profile123456",
            ),
            workingMemory = listOf(MemoryEntry("api_key", "sk-secret123456")),
            shortTerm = listOf(ChatMessage(Role.ASSISTANT, "previous")),
            currentUserMessage = ChatMessage(Role.USER, "token=sk-current123456"),
            preparedAt = Instant.parse("2026-01-02T00:00:00Z"),
        )

        service.recordEffectiveContext(context)
        assertTrue(captured.captured.systemPrompt.contains("[REDACTED]"))
        assertFalse(captured.captured.systemPrompt.contains("abcdefghijklmnop"))
        assertEquals("[REDACTED]", captured.captured.workingMemory.single().value)
        assertTrue(captured.captured.currentUserMessage.content.contains("[REDACTED]"))
        assertTrue(captured.captured.userProfile!!.customInstructions.contains("[REDACTED]"))
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
