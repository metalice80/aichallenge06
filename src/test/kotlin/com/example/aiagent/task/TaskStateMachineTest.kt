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
    fun `full four by four matrix allows exactly four and rejects twelve deterministically`() {
        val allowed = mapOf(
            (TaskStage.PLANNING to TaskEvent.PLAN_APPROVED) to TaskStage.EXECUTION,
            (TaskStage.EXECUTION to TaskEvent.EXECUTION_COMPLETED) to TaskStage.VALIDATION,
            (TaskStage.VALIDATION to TaskEvent.VALIDATION_PASSED) to TaskStage.DONE,
            (TaskStage.VALIDATION to TaskEvent.VALIDATION_FAILED) to TaskStage.EXECUTION,
        )
        var acceptedCount = 0
        var rejectedCount = 0

        TaskStage.entries.forEach { stage ->
            TaskEvent.entries.forEach { event ->
                val expected = allowed[stage to event]
                if (expected != null) {
                    assertEquals(expected, stateMachine.transition(stage, event))
                    assertEquals(expected, stateMachine.transition(stage, event))
                    acceptedCount += 1
                } else {
                    val exception = assertThrows(InvalidTaskTransitionException::class.java) {
                        stateMachine.transition(stage, event)
                    }
                    assertEquals(stage, exception.currentStage)
                    assertEquals(event, exception.event)
                    rejectedCount += 1
                }
            }
        }

        assertEquals(4, acceptedCount)
        assertEquals(12, rejectedCount)
    }
}
