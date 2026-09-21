package com.example.aiagent.task

import com.example.aiagent.agent.ChatMessage
import com.example.aiagent.agent.LlmRequestPurpose
import com.example.aiagent.agent.Role
import com.example.aiagent.config.TaskProgressAnalyzerProperties
import com.example.aiagent.config.TaskStateProperties
import com.example.aiagent.llm.LlmProvider
import com.example.aiagent.llm.TokenUsage
import com.example.aiagent.persistence.ConversationRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.time.Instant

class TaskStateCoordinatorTest {
    private val executionTask = AgentTask(
        id = 9,
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
    private val planningTask = executionTask.copy(
        stage = TaskStage.PLANNING,
        currentStep = "Define implementation plan",
        expectedActionType = ExpectedActionType.USER_INPUT,
        expectedActionDescription = "Provide requirements",
    )
    private val lifecycleGuard = TaskLifecycleGuard()
    private val usageRepository = mockk<ConversationRepository>(relaxed = true)

    @Test
    fun `analyzer failure blocks main flow without mutating Task state`() {
        val analyzer = mockk<TaskProgressAnalyzer> {
            every { analyze(executionTask, any()) } throws IllegalStateException("analyzer unavailable")
        }
        val stateService = mockk<TaskStateService>(relaxed = true)

        val result = coordinator(analyzer, stateService).analyzeBeforeMainRequest(
            executionTask,
            ChatMessage(Role.USER, "Continue"),
        )

        assertInstanceOf(TaskCoordinationResult.Blocked::class.java, result)
        assertEquals(executionTask, result.task)
        verify(exactly = 0) { stateService.updateProgress(any(), any(), any(), any()) }
        verify(exactly = 0) { stateService.applyEvent(any(), any(), any(), any()) }
    }

    @Test
    fun `arbitrary analyzer event suggestion never calls state service or changes stage`() {
        val proposal = TaskProgressProposal(
            suggestedEvent = TaskEvent.VALIDATION_PASSED,
            requestedAction = TaskActionType.NONE,
        )
        val analyzer = mockk<TaskProgressAnalyzer> {
            every { analyze(planningTask, any()) } returns analysis(proposal)
        }
        val stateService = mockk<TaskStateService>(relaxed = true)

        val result = coordinator(analyzer, stateService).analyzeBeforeMainRequest(
            planningTask,
            ChatMessage(Role.USER, "План утверждаю."),
        )

        assertEquals(
            TaskCoordinationResult.Ready(
                planningTask,
                TaskActionType.NONE,
                TaskEvent.VALIDATION_PASSED,
            ),
            result,
        )
        assertEquals(TaskStage.PLANNING, result.task.stage)
        verify(exactly = 0) { stateService.updateProgress(any(), any(), any(), any()) }
        verify(exactly = 0) { stateService.applyEvent(any(), any(), any(), any()) }
        verify(exactly = 1) {
            usageRepository.recordUsage(
                planningTask.id,
                match { it.purpose == LlmRequestPurpose.TASK_PROGRESS_ANALYZER },
            )
        }
    }

    @Test
    fun `plan preparation is allowed and only updates progress within planning`() {
        val analyzerProposal = TaskProgressProposal(
            currentStep = "Подготовить план реализации Booking Service",
            expectedActionType = ExpectedActionType.USER_CONFIRMATION,
            expectedActionDescription = "Подтвердить план",
            suggestedEvent = TaskEvent.PLAN_APPROVED,
            requestedAction = TaskActionType.PLAN,
        )
        val update = TaskProgressUpdate(
            currentStep = analyzerProposal.currentStep,
            expectedActionType = ExpectedActionType.AGENT_ACTION,
            expectedActionDescription = "Сформировать или обновить план реализации",
        )
        val planned = planningTask.copy(
            currentStep = checkNotNull(update.currentStep),
            expectedActionType = checkNotNull(update.expectedActionType),
            expectedActionDescription = update.expectedActionDescription,
            version = 1,
        )
        val analyzer = mockk<TaskProgressAnalyzer> {
            every { analyze(planningTask, any()) } returns analysis(analyzerProposal)
        }
        val stateService = mockk<TaskStateService> {
            every {
                updateProgress(
                    planningTask.id,
                    update,
                    TaskEventSource.CHAT_ANALYZER,
                    planningTask.version,
                )
            } returns planned
        }

        val result = coordinator(analyzer, stateService).analyzeBeforeMainRequest(
            planningTask,
            ChatMessage(Role.USER, "Подготовь план реализации Booking Service."),
        )

        assertEquals(TaskCoordinationResult.Ready(planned, TaskActionType.PLAN, null), result)
        assertEquals(TaskStage.PLANNING, result.task.stage)
        verify(exactly = 1) {
            stateService.updateProgress(
                planningTask.id,
                update,
                TaskEventSource.CHAT_ANALYZER,
                planningTask.version,
            )
        }
        verify(exactly = 0) { stateService.applyEvent(any(), any(), any(), any()) }
    }

    @Test
    fun `explicit approval wording remains a suggestion and leaves planning unchanged`() {
        val proposal = TaskProgressProposal(
            suggestedEvent = TaskEvent.PLAN_APPROVED,
            requestedAction = TaskActionType.NONE,
        )
        val analyzer = mockk<TaskProgressAnalyzer> {
            every { analyze(planningTask, any()) } returns analysis(proposal)
        }
        val stateService = mockk<TaskStateService>(relaxed = true)

        val result = coordinator(analyzer, stateService).analyzeBeforeMainRequest(
            planningTask,
            ChatMessage(Role.USER, "План утверждаю."),
        )

        assertEquals(
            TaskCoordinationResult.Ready(planningTask, TaskActionType.NONE, TaskEvent.PLAN_APPROVED),
            result,
        )
        verify(exactly = 0) { stateService.updateProgress(any(), any(), any(), any()) }
        verify(exactly = 0) { stateService.applyEvent(any(), any(), any(), any()) }
    }

    @Test
    fun `approval plus implementation request is blocked against persisted planning stage`() {
        val proposal = TaskProgressProposal(
            suggestedEvent = TaskEvent.PLAN_APPROVED,
            requestedAction = TaskActionType.IMPLEMENT,
        )
        val analyzer = mockk<TaskProgressAnalyzer> {
            every { analyze(planningTask, any()) } returns analysis(proposal)
        }
        val stateService = mockk<TaskStateService>(relaxed = true)

        val result = coordinator(analyzer, stateService).analyzeBeforeMainRequest(
            planningTask,
            ChatMessage(Role.USER, "План утверждаю. Начинай реализацию."),
        )

        assertInstanceOf(TaskCoordinationResult.Blocked::class.java, result)
        assertEquals(TaskStage.PLANNING, result.task.stage)
        verify(exactly = 0) { stateService.updateProgress(any(), any(), any(), any()) }
        verify(exactly = 0) { stateService.applyEvent(any(), any(), any(), any()) }
    }

    @Test
    fun `successful plan response changes only progress to user confirmation`() {
        val planned = planningTask.copy(
            currentStep = "Подготовить план реализации Booking Service",
            expectedActionType = ExpectedActionType.AGENT_ACTION,
            expectedActionDescription = "Сформировать или обновить план реализации",
            version = 1,
        )
        val confirmation = TaskProgressUpdate(
            currentStep = "Согласовать подготовленный план",
            expectedActionType = ExpectedActionType.USER_CONFIRMATION,
            expectedActionDescription = "Подтвердить план или запросить изменения",
        )
        val stateService = mockk<TaskStateService> {
            every {
                updateProgress(
                    planned.id,
                    confirmation,
                    TaskEventSource.CHAT_ANALYZER,
                    planned.version,
                )
            } returns planned.copy(
                currentStep = checkNotNull(confirmation.currentStep),
                expectedActionType = checkNotNull(confirmation.expectedActionType),
                expectedActionDescription = confirmation.expectedActionDescription,
                version = 2,
            )
        }

        coordinator(mockk(relaxed = true), stateService).afterSuccessfulMainResponse(
            TaskCoordinationResult.Ready(planned, TaskActionType.PLAN, null),
        )

        verify(exactly = 1) {
            stateService.updateProgress(
                planned.id,
                confirmation,
                TaskEventSource.CHAT_ANALYZER,
                planned.version,
            )
        }
        verify(exactly = 0) { stateService.applyEvent(any(), any(), any(), any()) }
    }

    @Test
    fun `execution finalization remains blocked without explicit validation`() {
        val analyzer = mockk<TaskProgressAnalyzer> {
            every { analyze(executionTask, any()) } returns
                analysis(TaskProgressProposal(requestedAction = TaskActionType.FINALIZE))
        }
        val stateService = mockk<TaskStateService>(relaxed = true)

        val result = coordinator(analyzer, stateService).analyzeBeforeMainRequest(
            executionTask,
            ChatMessage(Role.USER, "Finish the task without tests"),
        )

        assertInstanceOf(TaskCoordinationResult.Blocked::class.java, result)
        assertEquals(executionTask, result.task)
        verify(exactly = 0) { stateService.updateProgress(any(), any(), any(), any()) }
        verify(exactly = 0) { stateService.applyEvent(any(), any(), any(), any()) }
    }

    private fun coordinator(
        analyzer: TaskProgressAnalyzer,
        stateService: TaskStateService,
    ) = TaskStateCoordinator(
        TaskStateProperties(TaskProgressAnalyzerProperties(enabled = true)),
        analyzer,
        stateService,
        lifecycleGuard,
        usageRepository,
    )

    private fun analysis(proposal: TaskProgressProposal) = TaskProgressAnalysis(
        proposal = proposal,
        provider = LlmProvider.OPENAI,
        model = "analyzer",
        usage = TokenUsage(3, 2, 5),
        responseTimeMs = 4,
    )
}
