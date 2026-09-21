package com.example.aiagent.web

import com.example.aiagent.agent.Agent
import com.example.aiagent.agent.AgentState
import com.example.aiagent.agent.AgentRequest
import com.example.aiagent.agent.AgentResponse
import com.example.aiagent.agent.ChatMessage
import com.example.aiagent.agent.ConversationTokenUsage
import com.example.aiagent.agent.LlmProviderOption
import com.example.aiagent.agent.Role
import com.example.aiagent.context.branch.ConversationBranch
import com.example.aiagent.context.strategy.ContextStrategyType
import com.example.aiagent.llm.LlmProvider
import com.example.aiagent.llm.TokenUsage
import com.example.aiagent.memory.MemoryEntry
import com.example.aiagent.memory.MemoryInspector
import com.example.aiagent.task.AgentTask
import com.example.aiagent.task.ExpectedActionType
import com.example.aiagent.task.TaskEvent
import com.example.aiagent.task.TaskStage
import com.example.aiagent.task.TaskStateHistoryEntry
import com.example.aiagent.task.TaskStateHistoryEvent
import com.example.aiagent.task.TaskStatus
import com.example.aiagent.web.dto.ChatRequest
import com.example.aiagent.web.dto.ConversationTokenUsageResponse
import com.example.aiagent.web.dto.CreateTaskRequest
import com.example.aiagent.web.dto.TaskEventRequest
import com.example.aiagent.web.dto.UpdateTaskProgressRequest
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.time.Instant

class ChatControllerTest {
    private val agent = mockk<Agent>()
    private val controller = ChatController(agent)

    @Test
    fun `chat delegates to agent and maps response`() {
        val request = AgentRequest(
            "Привет",
            LlmProvider.OPENROUTER,
            "openai/gpt-test",
            ContextStrategyType.STICKY_FACTS,
        )
        every { agent.sendMessage(request) } returns AgentResponse(
            provider = LlmProvider.OPENROUTER,
            content = "Здравствуйте",
            model = "test-model",
            currentUsage = TokenUsage(8, 3, 11),
            conversationUsage = ConversationTokenUsage(108, 23, 131),
            responseTimeMs = 125,
        )

        val response = controller.chat(
            ChatRequest(
                "Привет",
                LlmProvider.OPENROUTER,
                "openai/gpt-test",
                ContextStrategyType.STICKY_FACTS,
            ),

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
    fun `context strategies expose all selectable implementations`() {
        every { agent.contextStrategies() } returns ContextStrategyType.entries

        val response = controller.contextStrategies()

        assertEquals(ContextStrategyType.entries, response.map { it.type })
        assertEquals(listOf("Sliding Window", "Sticky Facts", "Branching"), response.map { it.displayName })
    }

    @Test
    fun `branch endpoints expose parent checkpoint create and activation state`() {
        val main = ConversationBranch(1, "Main", null, 0, active = true)
        val child = ConversationBranch(2, "Branch 1", 1, 4, active = true)
        every { agent.branches() } returns listOf(main)
        every { agent.createBranch() } returns child
        every { agent.activateBranch(2) } returns AgentState(
            messages = listOf(
                ChatMessage(Role.SYSTEM, "Internal"),
                ChatMessage(Role.USER, "Branch question"),
                ChatMessage(Role.ASSISTANT, "Branch answer"),
            ),
            conversationUsage = ConversationTokenUsage(10, 5, 15),
        )

        val listed = controller.branches()
        val created = controller.createBranch()
        val activated = controller.activateBranch(2)

        assertEquals(1, listed.single().id)
        assertEquals(1, created.parentBranchId)
        assertEquals(4, created.checkpointMessageCount)
        assertEquals(listOf("Branch question", "Branch answer"), activated.messages.map { it.content })
        assertEquals(ConversationTokenUsageResponse(10, 5, 15), activated.conversationUsage)
        verify(exactly = 1) { agent.createBranch() }
        verify(exactly = 1) { agent.activateBranch(2) }
    }

    @Test
    fun `Task endpoints list create switch and complete persistent Tasks`() {
        val createdAt = Instant.parse("2026-01-01T00:00:00Z")
        val task = AgentTask(4, "Booking", TaskStatus.ACTIVE, createdAt, null, selected = true)
        val state = AgentState(emptyList(), ConversationTokenUsage.ZERO)
        every { agent.tasks() } returns listOf(task)
        every { agent.createTask("Booking") } returns state
        every { agent.activateTask(4) } returns state
        every { agent.completeTask(4) } returns task.copy(
            status = TaskStatus.COMPLETED,
            completedAt = createdAt.plusSeconds(10),
            stage = TaskStage.DONE,
            currentStep = "Task completed",
            expectedActionType = ExpectedActionType.NONE,
            expectedActionDescription = null,
        )

        assertEquals("Booking", controller.tasks().single().name)
        assertEquals(0, controller.createTask(CreateTaskRequest("Booking")).messages.size)
        assertEquals(0, controller.activateTask(4).messages.size)
        assertEquals(TaskStatus.COMPLETED, controller.completeTask(4).status)
    }


    @Test
    fun `Task state endpoints expose progress events pause resume and history`() {
        val createdAt = Instant.parse("2026-01-01T00:00:00Z")
        val task = AgentTask(
            id = 7,
            name = "Booking",
            status = TaskStatus.ACTIVE,
            createdAt = createdAt,
            completedAt = null,
            selected = true,
            stage = TaskStage.EXECUTION,
            currentStep = "Implement persistence layer",
            expectedActionType = ExpectedActionType.AGENT_ACTION,
            expectedActionDescription = "Propose repository implementation",
        )
        val validation = task.copy(
            stage = TaskStage.VALIDATION,
            currentStep = "Validate persistence",
            expectedActionType = ExpectedActionType.VALIDATION,
            expectedActionDescription = "Run repository tests",
        )
        every {
            agent.updateTaskProgress(
                7,
                "Implement SQLite repository",
                ExpectedActionType.AGENT_ACTION,
                "Write repository implementation",
            )
        } returns task.copy(currentStep = "Implement SQLite repository")
        every { agent.applyTaskEvent(7, TaskEvent.EXECUTION_COMPLETED, any()) } returns validation
        every { agent.pauseTask(7) } returns task.copy(paused = true)
        every { agent.resumeTask(7) } returns task
        every { agent.taskStateHistory(7) } returns listOf(
            TaskStateHistoryEntry(
                id = 1,
                taskId = 7,
                event = TaskStateHistoryEvent.EXECUTION_COMPLETED,
                fromStage = TaskStage.EXECUTION,
                toStage = TaskStage.VALIDATION,
                paused = false,
                currentStep = "Validate persistence",
                description = "Run repository tests",
                createdAt = createdAt,
            ),
        )

        val progress = controller.updateTaskProgress(
            7,
            UpdateTaskProgressRequest(
                "Implement SQLite repository",
                ExpectedActionType.AGENT_ACTION,
                "Write repository implementation",
            ),
        )
        val transitioned = controller.applyTaskEvent(
            7,
            TaskEventRequest(
                event = TaskEvent.EXECUTION_COMPLETED,
                currentStep = "Validate persistence",
                expectedActionType = ExpectedActionType.VALIDATION,
                expectedActionDescription = "Run repository tests",
            ),
        )

        assertEquals("Implement SQLite repository", progress.currentStep)
        assertEquals(TaskStage.VALIDATION, transitioned.stage)
        assertEquals(true, controller.pauseTask(7).paused)
        assertEquals(false, controller.resumeTask(7).paused)
        assertEquals(TaskStateHistoryEvent.EXECUTION_COMPLETED.name, controller.taskStateHistory(7).single().event)
    }
    @Test
    fun `memory endpoint exposes three layers and clear actions remain separate`() {
        every { agent.memory(ContextStrategyType.SLIDING_WINDOW) } returns MemoryInspector(
            shortTerm = listOf(ChatMessage(Role.USER, "Recent")),
            working = listOf(MemoryEntry("database", "PostgreSQL")),
            longTerm = listOf(MemoryEntry("answer_language", "Russian")),
            lastUpdate = null,
            effectiveContext = null,
        )
        every { agent.clearWorkingMemory() } just runs
        every { agent.clearLongTermMemory() } just runs

        val response = controller.memory(ContextStrategyType.SLIDING_WINDOW)
        val workingClear = controller.clearWorkingMemory()
        val longTermClear = controller.clearLongTermMemory()

        assertEquals("Recent", response.shortTerm.single().content)
        assertEquals("database", response.working.single().key)
        assertEquals("answer_language", response.longTerm.single().key)
        assertEquals(HttpStatus.NO_CONTENT, workingClear.statusCode)
        assertEquals(HttpStatus.NO_CONTENT, longTermClear.statusCode)
        verify(exactly = 1) { agent.clearWorkingMemory() }
        verify(exactly = 1) { agent.clearLongTermMemory() }
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
