package com.example.aiagent.task

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class TaskStateMachineTest {
    private val stateMachine = DeterministicTaskStateMachine()

    @Test
    fun `happy path reaches DONE through every required stage`() {
        val execution = stateMachine.transition(TaskStage.PLANNING, TaskEvent.PLAN_APPROVED)
        val validation = stateMachine.transition(execution, TaskEvent.EXECUTION_COMPLETED)
        val done = stateMachine.transition(validation, TaskEvent.VALIDATION_PASSED)

        assertEquals(TaskStage.EXECUTION, execution)
        assertEquals(TaskStage.VALIDATION, validation)
        assertEquals(TaskStage.DONE, done)
    }

    @Test
    fun `failed validation returns to execution`() {
        assertEquals(
            TaskStage.EXECUTION,
            stateMachine.transition(TaskStage.VALIDATION, TaskEvent.VALIDATION_FAILED),
        )
    }

    @Test
    fun `every unspecified stage event pair is rejected`() {
        val allowed = setOf(
            TaskStage.PLANNING to TaskEvent.PLAN_APPROVED,
            TaskStage.EXECUTION to TaskEvent.EXECUTION_COMPLETED,
            TaskStage.VALIDATION to TaskEvent.VALIDATION_PASSED,
            TaskStage.VALIDATION to TaskEvent.VALIDATION_FAILED,
        )

        TaskStage.entries.forEach { stage ->
            TaskEvent.entries.forEach { event ->
                if (stage to event !in allowed) {
                    assertThrows(InvalidTaskTransitionException::class.java) {
                        stateMachine.transition(stage, event)
                    }
                }
            }
        }
    }
}
