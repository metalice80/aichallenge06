package com.example.aiagent.task

import org.springframework.stereotype.Component

interface TaskStateMachine {
    fun transition(currentStage: TaskStage, event: TaskEvent): TaskStage
}

@Component
class DeterministicTaskStateMachine : TaskStateMachine {
    override fun transition(currentStage: TaskStage, event: TaskEvent): TaskStage =
        TRANSITIONS[currentStage to event]
            ?: throw InvalidTaskTransitionException(currentStage, event)

    companion object {
        private val TRANSITIONS = mapOf(
            (TaskStage.PLANNING to TaskEvent.PLAN_APPROVED) to TaskStage.EXECUTION,
            (TaskStage.EXECUTION to TaskEvent.EXECUTION_COMPLETED) to TaskStage.VALIDATION,
            (TaskStage.VALIDATION to TaskEvent.VALIDATION_PASSED) to TaskStage.DONE,
            (TaskStage.VALIDATION to TaskEvent.VALIDATION_FAILED) to TaskStage.EXECUTION,
        )
    }
}

class InvalidTaskTransitionException(
    stage: TaskStage,
    event: TaskEvent,
) : IllegalArgumentException("Task event $event is not valid in stage $stage")

class InvalidTaskStateException(message: String) : IllegalArgumentException(message)
