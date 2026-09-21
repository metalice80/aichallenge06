package com.example.aiagent.task

import java.time.Instant

enum class TaskStatus {
    ACTIVE,
    COMPLETED,
}

data class AgentTask(
    val id: Long,
    val name: String,
    val status: TaskStatus,
    val createdAt: Instant,
    val completedAt: Instant?,
    val selected: Boolean,
    val stage: TaskStage = if (status == TaskStatus.COMPLETED) TaskStage.DONE else TaskStage.PLANNING,
    val currentStep: String = if (status == TaskStatus.COMPLETED) {
        "Task completed"
    } else {
        "Define goals, requirements, and execution plan"
    },
    val expectedActionType: ExpectedActionType = if (status == TaskStatus.COMPLETED) {
        ExpectedActionType.NONE
    } else {
        ExpectedActionType.USER_INPUT
    },
    val expectedActionDescription: String? = if (status == TaskStatus.COMPLETED) {
        null
    } else {
        "Provide goals, requirements, and constraints"
    },
    val paused: Boolean = false,
) {
    init {
        require((status == TaskStatus.COMPLETED) == (stage == TaskStage.DONE)) {
            "Task status $status is inconsistent with stage $stage"
        }
        require(stage != TaskStage.DONE || !paused) { "DONE Task cannot be paused" }
        require(stage != TaskStage.DONE || expectedActionType == ExpectedActionType.NONE) {
            "DONE Task must not expect another action"
        }
    }

    val expectedAction: ExpectedAction
        get() = ExpectedAction(expectedActionType, expectedActionDescription)

    fun stateSnapshot() = TaskStateSnapshot(
        taskId = id,
        taskName = name,
        stage = stage,
        currentStep = currentStep,
        expectedActionType = expectedActionType,
        expectedActionDescription = expectedActionDescription,
        paused = paused,
    )
}
