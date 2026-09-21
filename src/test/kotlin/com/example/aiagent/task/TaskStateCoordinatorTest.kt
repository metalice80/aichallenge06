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
    private val task = AgentTask(
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
    private val stateMachine = DeterministicTaskStateMachine()
    private val lifecycleGuard = TaskLifecycleGuard()
    private val usageRepository = mockk<ConversationRepository>(relaxed = true)

    @Test
    fun `analyzer failure blocks main flow without mutating persisted Task state`() {
        val analyzer = mockk<TaskProgressAnalyzer> {
            every { analyze(task, any()) } throws IllegalStateException("analyzer unavailable")
        }
        val stateService = mockk<TaskStateService>(relaxed = true)
        val coordinator = coordinator(analyzer, stateService)

        val result = coordinator.analyzeBeforeMainRequest(
            task,
            ChatMessage(Role.USER, "Continue"),
        )

        assertInstanceOf(TaskCoordinationResult.Blocked::class.java, result)
        assertEquals(task, result.task)
        verify(exactly = 0) { stateService.applyProposal(any(), any(), any(), any()) }
    }

    @Test
    fun `valid analyzer proposal is guarded and applied through TaskStateService before main request`() {
        val proposal = TaskProgressProposal(
            currentStep = "Validate persistence",
            expectedActionType = ExpectedActionType.VALIDATION,
            expectedActionDescription = "Run repository tests",
            proposedEvent = TaskEvent.EXECUTION_COMPLETED,
            requestedAction = TaskActionType.VALIDATE,
        )
        val updated = task.copy(
            stage = TaskStage.VALIDATION,
            currentStep = "Validate persistence",
            expectedActionType = ExpectedActionType.VALIDATION,
            expectedActionDescription = "Run repository tests",
            version = 1,
        )
        val analyzer = mockk<TaskProgressAnalyzer> {
            every { analyze(task, any()) } returns analysis(proposal)
        }
        val stateService = mockk<TaskStateService> {
            every {
                applyProposal(task.id, proposal, TaskEventSource.CHAT_ANALYZER, task.version)
            } returns updated
        }
        val coordinator = coordinator(analyzer, stateService)

        val result = coordinator.analyzeBeforeMainRequest(
            task,
            ChatMessage(Role.USER, "Implementation is complete"),
        )

        assertEquals(
            TaskCoordinationResult.Ready(updated, TaskActionType.VALIDATE, TaskEvent.EXECUTION_COMPLETED),
            result,
        )
        verify(exactly = 1) {
            stateService.applyProposal(task.id, proposal, TaskEventSource.CHAT_ANALYZER, task.version)
        }
        verify(exactly = 1) {
            usageRepository.recordUsage(
                task.id,
                match { it.purpose == LlmRequestPurpose.TASK_PROGRESS_ANALYZER },
            )
        }
    }

    @Test
    fun `planning implementation request is blocked before progress or event mutation`() {
        val planning = task.copy(
            stage = TaskStage.PLANNING,
            currentStep = "Prepare plan",
            expectedActionType = ExpectedActionType.USER_CONFIRMATION,
            expectedActionDescription = "Approve plan",
        )
        val proposal = TaskProgressProposal(
            currentStep = "Implement REST API",
            expectedActionType = ExpectedActionType.AGENT_ACTION,
            expectedActionDescription = "Write production code",
            requestedAction = TaskActionType.IMPLEMENT,
        )
        val analyzer = mockk<TaskProgressAnalyzer> {
            every { analyze(planning, any()) } returns analysis(proposal)
        }
        val stateService = mockk<TaskStateService>(relaxed = true)

        val result = coordinator(analyzer, stateService).analyzeBeforeMainRequest(
            planning,
            ChatMessage(Role.USER, "Skip the plan and implement it"),
        )

        assertInstanceOf(TaskCoordinationResult.Blocked::class.java, result)
        assertEquals(planning, result.task)
        verify(exactly = 0) { stateService.applyProposal(any(), any(), any(), any()) }
    }

    @Test
    fun `execution finalization request is blocked before mutation`() {
        val proposal = TaskProgressProposal(requestedAction = TaskActionType.FINALIZE)
        val analyzer = mockk<TaskProgressAnalyzer> {
            every { analyze(task, any()) } returns analysis(proposal)
        }
        val stateService = mockk<TaskStateService>(relaxed = true)

        val result = coordinator(analyzer, stateService).analyzeBeforeMainRequest(
            task,
            ChatMessage(Role.USER, "Finish the task without tests"),
        )

        assertInstanceOf(TaskCoordinationResult.Blocked::class.java, result)
        assertEquals(task, result.task)
        verify(exactly = 0) { stateService.applyProposal(any(), any(), any(), any()) }
    }

    private fun coordinator(
        analyzer: TaskProgressAnalyzer,
        stateService: TaskStateService,
    ) = TaskStateCoordinator(
        TaskStateProperties(TaskProgressAnalyzerProperties(enabled = true)),
        analyzer,
        stateService,
        stateMachine,
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
