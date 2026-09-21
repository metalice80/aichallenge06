package com.example.aiagent.task

import com.example.aiagent.agent.ChatMessage
import com.example.aiagent.agent.Role
import com.example.aiagent.config.TaskProgressAnalyzerProperties
import com.example.aiagent.config.TaskStateProperties
import com.example.aiagent.llm.LlmClient
import com.example.aiagent.llm.LlmClientResolver
import com.example.aiagent.llm.LlmProvider
import com.example.aiagent.llm.LlmRequest
import com.example.aiagent.llm.LlmResponse
import com.example.aiagent.llm.StructuredOutputParser
import com.example.aiagent.llm.TokenUsage
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant

class LlmTaskProgressAnalyzerTest {
    @Test
    fun `analyzer uses its configured provider model and isolated prompt`() {
        val client = mockk<LlmClient>()
        val resolver = mockk<LlmClientResolver> {
            every { resolve(LlmProvider.OPENROUTER) } returns client
        }
        val request = slot<LlmRequest>()
        every { client.chat(capture(request)) } returns LlmResponse(
            content = """
                {
                  "currentStep":"Implement repository adapter",
                  "expectedActionType":"AGENT_ACTION",
                  "expectedActionDescription":"Write SQLite repository code",
                  "suggestedEvent":null,
                  "requestedAction":"IMPLEMENT"
                }
            """.trimIndent(),
            model = "openai/analyzer-model",
            usage = TokenUsage(3, 2, 5),
        )
        val analyzer = LlmTaskProgressAnalyzer(
            resolver,
            TaskStateProperties(
                TaskProgressAnalyzerProperties(
                    enabled = true,
                    provider = LlmProvider.OPENROUTER,
                    model = "openai/analyzer-model",
                    systemPrompt = "Return deterministic task progress JSON",
                ),
            ),
            StructuredOutputParser(jacksonObjectMapper()),
        )
        val task = AgentTask(
            id = 1,
            name = "Booking",
            status = TaskStatus.ACTIVE,
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
            completedAt = null,
            selected = true,
            stage = TaskStage.EXECUTION,
            currentStep = "Implement persistence layer",
            expectedActionType = ExpectedActionType.AGENT_ACTION,
            expectedActionDescription = "Propose repository implementation",
        )

        val analysis = analyzer.analyze(
            task,
            ChatMessage(Role.USER, "Continue"),
        )
        val proposal = analysis.proposal

        assertEquals("openai/analyzer-model", request.captured.model)
        assertEquals(
            ChatMessage(Role.SYSTEM, "Return deterministic task progress JSON"),
            request.captured.messages.first(),
        )
        assertEquals(2, request.captured.messages.size)
        assertFalse(request.captured.messages.any { it.content.contains("USER PROFILE") })
        assertEquals(true, request.captured.messages.last().content.contains("Reference transition table"))
        assertEquals(true, request.captured.messages.last().content.contains("New user message:\nContinue"))
        assertEquals("Implement repository adapter", proposal.currentStep)
        assertEquals(ExpectedActionType.AGENT_ACTION, proposal.expectedActionType)
        assertEquals("Write SQLite repository code", proposal.expectedActionDescription)
        assertEquals(TaskActionType.IMPLEMENT, proposal.requestedAction)
        assertEquals(TokenUsage(3, 2, 5), analysis.usage)
        verify(exactly = 1) { resolver.resolve(LlmProvider.OPENROUTER) }
    }

    @Test
    fun `planning proposal cannot turn plan preparation into plan approval`() {
        val client = mockk<LlmClient>()
        val resolver = mockk<LlmClientResolver> {
            every { resolve(LlmProvider.OPENAI) } returns client
        }
        val request = slot<LlmRequest>()
        every { client.chat(capture(request)) } returns LlmResponse(
            content = """
                {
                  "currentStep":"Подготовить план реализации Booking Service",
                  "expectedActionType":"USER_CONFIRMATION",
                  "expectedActionDescription":"Утвердить план",
                  "suggestedEvent":"PLAN_APPROVED",
                  "requestedAction":"PLAN"
                }
            """.trimIndent(),
            model = "analyzer-model",
            usage = TokenUsage(3, 2, 5),
        )
        val analyzer = LlmTaskProgressAnalyzer(
            resolver,
            TaskStateProperties(),
            StructuredOutputParser(jacksonObjectMapper()),
        )
        val planning = AgentTask(
            id = 1,
            name = "Booking Service",
            status = TaskStatus.ACTIVE,
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
            completedAt = null,
            selected = true,
        )

        val proposal = analyzer.analyze(
            planning,
            ChatMessage(Role.USER, "Подготовь план реализации Booking Service."),
        ).proposal

        assertEquals(TaskActionType.PLAN, proposal.requestedAction)
        assertNull(proposal.suggestedEvent)
        assertEquals(ExpectedActionType.AGENT_ACTION, proposal.expectedActionType)
        assertEquals("Сформировать или обновить план реализации", proposal.expectedActionDescription)
        val prompt = request.captured.messages.last().content
        assertTrue(prompt.contains("TaskStage.PLANNING, TaskActionType.PLAN, and TaskEvent.PLAN_APPROVED"))
        assertTrue(prompt.contains("For PLAN in PLANNING, suggestedEvent must be null"))
    }
}
