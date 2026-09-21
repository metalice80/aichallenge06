package com.example.aiagent.context

import com.example.aiagent.agent.ChatMessage
import com.example.aiagent.agent.Role
import com.example.aiagent.config.LlmProperties
import com.example.aiagent.context.strategy.ContextPlan
import com.example.aiagent.context.strategy.ContextStrategyType
import com.example.aiagent.invariant.InvariantType
import com.example.aiagent.invariant.TaskInvariantSet
import com.example.aiagent.invariant.TaskInvariantSnapshot
import com.example.aiagent.memory.MemoryContext
import com.example.aiagent.memory.MemoryEntry
import com.example.aiagent.memory.SecretRedactor
import com.example.aiagent.profile.ExpertiseLevel
import com.example.aiagent.profile.ResponseFormat
import com.example.aiagent.profile.ResponseLanguage
import com.example.aiagent.profile.ResponseStyle
import com.example.aiagent.profile.UserProfile
import com.example.aiagent.task.AgentTask
import com.example.aiagent.task.TaskStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class EffectiveContextBuilderTest {
    private val builder = EffectiveContextBuilder(
        LlmProperties(systemPrompt = "Application defaults"),
        SecretRedactor(),
    )
    private val task = AgentTask(
        id = 7,
        name = "Payment Service",
        status = TaskStatus.ACTIVE,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        completedAt = null,
        selected = true,
    )

    @Test
    fun `same request produces distinct Developer Student and Manager personalization contexts`() {
        val developer = profile(
            id = 1,
            name = "Developer",
            expertise = ExpertiseLevel.ADVANCED,
            style = ResponseStyle.CONCISE,
            format = ResponseFormat.CODE_FIRST,
            custom = "Do not explain basic syntax.",
        )
        val student = profile(
            id = 2,
            name = "Student",
            expertise = ExpertiseLevel.BEGINNER,
            style = ResponseStyle.EDUCATIONAL,
            format = ResponseFormat.STEP_BY_STEP,
            custom = "Explain technical terms in simple words.",
        )
        val manager = profile(
            id = 3,
            name = "Manager",
            expertise = ExpertiseLevel.INTERMEDIATE,
            style = ResponseStyle.CONCISE,
            format = ResponseFormat.STRUCTURED,
            custom = "Focus on purpose, risks, and consequences; minimize implementation details.",
        )

        val contexts = listOf(developer, student, manager).map { selected ->
            builder.build(
                task = task,
                profile = selected,
                strategy = ContextStrategyType.SLIDING_WINDOW,
                memoryContext = MemoryContext(emptyList(), emptyList()),
                contextPlan = ContextPlan(emptyList()),
                currentUserMessage = ChatMessage(Role.USER, "Explain optimistic locking and show an example."),
            )
        }
        val profileMessages = contexts.map { it.messages[1].content }

        assertEquals(3, profileMessages.distinct().size)
        assertTrue(profileMessages[0].contains("Advanced"))
        assertTrue(profileMessages[0].contains("code example before"))
        assertTrue(profileMessages[1].contains("Beginner"))
        assertTrue(profileMessages[1].contains("ordered sequence of steps"))
        assertTrue(profileMessages[2].contains("Structured"))
        assertTrue(profileMessages[2].contains("risks, and consequences"))
        assertNotEquals(contexts[0].diagnostic.userProfile, contexts[1].diagnostic.userProfile)
    }

    @Test
    fun `context order makes current message then Task then Profile override lower priorities`() {
        val prepared = builder.build(
            task = task,
            profile = profile(
                id = 1,
                name = "Developer",
                expertise = ExpertiseLevel.ADVANCED,
                style = ResponseStyle.CONCISE,
                format = ResponseFormat.CODE_FIRST,
                custom = "Prefer Kotlin examples.",
            ),
            strategy = ContextStrategyType.SLIDING_WINDOW,
            memoryContext = MemoryContext(
                longTerm = listOf(MemoryEntry("preferred_answer_language", "English")),
                working = listOf(
                    MemoryEntry("language", "Java"),
                    MemoryEntry("database", "PostgreSQL"),
                ),
            ),
            contextPlan = ContextPlan(listOf(ChatMessage(Role.ASSISTANT, "Previous answer"))),
            currentUserMessage = ChatMessage(Role.USER, "Answer in English."),
        )

        assertEquals(Role.SYSTEM, prepared.messages[0].role)
        assertTrue(prepared.messages[1].content.startsWith("LONG-TERM MEMORY"))
        assertTrue(prepared.messages[1].content.contains("Explicit User Profile"))
        assertTrue(prepared.messages[2].content.startsWith("USER PROFILE \"Developer\""))
        assertTrue(prepared.messages[2].content.contains("overrides Long-Term Memory"))
        assertTrue(prepared.messages[2].content.contains("Respond in Russian"))
        assertTrue(prepared.messages[2].content.contains("Prefer Kotlin examples"))
        assertTrue(prepared.messages[3].content.startsWith("WORKING MEMORY for Task \"Payment Service\""))
        assertTrue(prepared.messages[3].content.contains("language = Java"))
        assertTrue(prepared.messages[3].content.contains("overrides User Profile"))
        assertTrue(prepared.messages[4].content.startsWith("TASK STATE"))
        assertTrue(prepared.messages[4].content.contains("Stage: PLANNING"))
        assertTrue(prepared.messages[4].content.contains("Current Step: Define goals, requirements, and execution plan"))
        assertEquals(ChatMessage(Role.USER, "Answer in English."), prepared.messages.last())
        assertEquals("Developer", prepared.diagnostic.userProfile?.name)
        assertEquals(listOf(MemoryEntry("language", "Java"), MemoryEntry("database", "PostgreSQL")), prepared.diagnostic.workingMemory)
    }

    @Test
    fun `Profile Kotlin remains available when Task has no language constraint`() {
        val prepared = builder.build(
            task = task,
            profile = profile(
                id = 1,
                name = "Developer",
                expertise = ExpertiseLevel.ADVANCED,
                style = ResponseStyle.CONCISE,
                format = ResponseFormat.CODE_FIRST,
                custom = "Use Kotlin for code examples.",
            ),
            strategy = ContextStrategyType.BRANCHING,
            memoryContext = MemoryContext(
                longTerm = emptyList(),
                working = listOf(MemoryEntry("database", "PostgreSQL")),
            ),
            contextPlan = ContextPlan(emptyList()),
            currentUserMessage = ChatMessage(Role.USER, "Show a small REST controller."),
        )

        assertTrue(prepared.messages[1].content.contains("Use Kotlin for code examples"))
        assertTrue(prepared.messages[2].content.contains("database = PostgreSQL"))
        assertTrue(prepared.messages[2].content.contains("overrides User Profile"))
        assertTrue(prepared.messages.none { it.content.contains("language = Java") })
    }

    @Test
    fun `Task state stays in effective context when sliding window has no progress messages`() {
        val executionTask = task.copy(
            stage = com.example.aiagent.task.TaskStage.EXECUTION,
            currentStep = "Implement persistence layer",
            expectedActionType = com.example.aiagent.task.ExpectedActionType.AGENT_ACTION,
            expectedActionDescription = "Propose repository implementation",
            paused = true,
        )

        val prepared = builder.build(
            task = executionTask,
            profile = null,
            strategy = ContextStrategyType.SLIDING_WINDOW,
            memoryContext = MemoryContext(emptyList(), emptyList()),
            contextPlan = ContextPlan(emptyList()),
            currentUserMessage = ChatMessage(Role.USER, "Продолжай."),
        )

        assertEquals(3, prepared.messages.size)
        assertTrue(prepared.messages[1].content.startsWith("TASK STATE"))
        assertTrue(prepared.messages[1].content.contains("Stage: EXECUTION"))
        assertTrue(prepared.messages[1].content.contains("Current Step: Implement persistence layer"))
        assertTrue(prepared.messages[1].content.contains("Expected Action Type: AGENT_ACTION"))
        assertTrue(prepared.messages[1].content.contains("Paused: true"))
        assertEquals(executionTask.stateSnapshot(), prepared.diagnostic.taskState)
        assertTrue(prepared.diagnostic.shortTerm.isEmpty())
    }

    @Test
    fun `enabled invariant snapshot is a hard boundary in request and exact inspector metadata`() {
        val snapshot = TaskInvariantSnapshot(
            id = 14,
            taskId = task.id,
            taskName = task.name,
            type = InvariantType.TECHNICAL_DECISION,
            key = "database",
            value = "PostgreSQL\nprimary",
            description = "Do not replace with \"MongoDB\".",
        )

        val prepared = builder.build(
            task = task,
            profile = null,
            strategy = ContextStrategyType.SLIDING_WINDOW,
            memoryContext = MemoryContext(emptyList(), emptyList()),
            contextPlan = ContextPlan(emptyList()),
            invariantSet = TaskInvariantSet(task.id, task.name, listOf(snapshot)),
            currentUserMessage = ChatMessage(Role.USER, "Design persistence"),
        )

        val invariantBlock = prepared.messages[1].content
        assertTrue(invariantBlock.startsWith("TASK INVARIANTS"))
        assertTrue(invariantBlock.contains("[TECHNICAL_DECISION] #14"))
        assertTrue(invariantBlock.contains("Value: \"PostgreSQL\\nprimary\""))
        assertTrue(invariantBlock.contains("Description: \"Do not replace with \\\"MongoDB\\\".\""))
        assertEquals(listOf(snapshot), prepared.diagnostic.taskInvariants)
        assertTrue(prepared.messages[2].content.startsWith("TASK STATE"))
    }

    private fun profile(
        id: Long,
        name: String,
        expertise: ExpertiseLevel,
        style: ResponseStyle,
        format: ResponseFormat,
        custom: String,
    ) = UserProfile(
        id = id,
        name = name,
        responseLanguage = ResponseLanguage.RUSSIAN,
        expertiseLevel = expertise,
        responseStyle = style,
        responseFormat = format,
        customInstructions = custom,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
        active = true,
    )
}
