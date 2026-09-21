package com.example.aiagent.task

import com.example.aiagent.agent.ChatMessage
import com.example.aiagent.agent.Role
import com.example.aiagent.config.TaskProgressAnalyzerProperties
import com.example.aiagent.config.TaskStateProperties
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
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

    @Test
    fun `analyzer failure does not mutate persisted Task state or escape into chat flow`() {
        val analyzer = mockk<TaskProgressAnalyzer> {
            every { analyze(task, any()) } throws IllegalStateException("analyzer unavailable")
        }
        val stateService = mockk<TaskStateService>(relaxed = true)
        val coordinator = TaskStateCoordinator(
            TaskStateProperties(TaskProgressAnalyzerProperties(enabled = true)),
            analyzer,
            stateService,
        )

        val result = coordinator.analyzeBeforeMainRequest(
            task,
            ChatMessage(Role.USER, "Continue"),
        )

        assertEquals(task, result)

        verify(exactly = 0) { stateService.applyProposal(any(), any()) }
    }

    @Test
    fun `valid analyzer proposal is applied through TaskStateService before main request`() {
        val proposal = TaskProgressProposal(
            currentStep = "Validate persistence",
            expectedActionType = ExpectedActionType.VALIDATION,
            expectedActionDescription = "Run repository tests",
            proposedEvent = TaskEvent.EXECUTION_COMPLETED,
        )
        val updated = task.copy(
            stage = TaskStage.VALIDATION,
            currentStep = "Validate persistence",
            expectedActionType = ExpectedActionType.VALIDATION,
            expectedActionDescription = "Run repository tests",
        )
        val analyzer = mockk<TaskProgressAnalyzer> {
            every { analyze(task, any()) } returns proposal
        }
        val stateService = mockk<TaskStateService> {
            every { applyProposal(task.id, proposal) } returns updated
        }
        val coordinator = TaskStateCoordinator(
            TaskStateProperties(TaskProgressAnalyzerProperties(enabled = true)),
            analyzer,
            stateService,
        )

        val result = coordinator.analyzeBeforeMainRequest(
            task,
            ChatMessage(Role.USER, "Implementation is complete"),
        )

        assertEquals(updated, result)
        verify(exactly = 1) { stateService.applyProposal(task.id, proposal) }
    }
}
