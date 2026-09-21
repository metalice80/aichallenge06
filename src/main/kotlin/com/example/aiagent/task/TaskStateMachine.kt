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
    val currentStage: TaskStage,
    val event: TaskEvent,
) : IllegalArgumentException("Task event $event is not valid in stage $currentStage")

open class InvalidTaskStateException(
    message: String,
    val code: String = "INVALID_TASK_STATE",
) : IllegalArgumentException(message)

class TaskNotFoundException(taskId: Long) :
    InvalidTaskStateException("Task $taskId does not exist", "TASK_NOT_FOUND")

class TaskStateConflictException(
    val taskId: Long,
    val expectedVersion: Long,
    val actualVersion: Long?,
) : InvalidTaskStateException(
    "Task $taskId version conflict: expected $expectedVersion, actual ${actualVersion ?: "unknown"}",
    "TASK_VERSION_CONFLICT",
)

class TaskPausedException(taskId: Long) :
    InvalidTaskStateException("Task $taskId is paused; resume it before continuing", "TASK_PAUSED")
