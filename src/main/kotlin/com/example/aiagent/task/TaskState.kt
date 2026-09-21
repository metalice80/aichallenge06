package com.example.aiagent.task

import java.time.Instant

enum class TaskStage {
    PLANNING,
    EXECUTION,
    VALIDATION,
    DONE,
}

enum class TaskEvent {
    PLAN_APPROVED,
    EXECUTION_COMPLETED,
    VALIDATION_PASSED,
    VALIDATION_FAILED,
}

enum class TaskActionType {
    PLAN,
    IMPLEMENT,
    VALIDATE,
    FINALIZE,
    STATUS,
    NONE,
}

enum class TaskEventSource {
    USER_INTERFACE,
    REST_API,
    CHAT_ANALYZER,
}

enum class ExpectedActionType {
    USER_INPUT,
    USER_CONFIRMATION,
    AGENT_ACTION,
    VALIDATION,
    NONE,
}

data class ExpectedAction(
    val type: ExpectedActionType,
    val description: String?,
)

data class TaskStateSnapshot(
    val taskId: Long,
    val taskName: String,
    val stage: TaskStage,
    val currentStep: String,
    val expectedActionType: ExpectedActionType,
    val expectedActionDescription: String?,
    val paused: Boolean,
    val version: Long = 0,
)

enum class TaskStateHistoryEvent {
    TASK_CREATED,
    PLAN_APPROVED,
    EXECUTION_COMPLETED,
    VALIDATION_PASSED,
    VALIDATION_FAILED,
    PROGRESS_UPDATED,
    PAUSE,
    RESUME,
    ;

    companion object {
        fun from(event: TaskEvent): TaskStateHistoryEvent = valueOf(event.name)
    }
}

data class TaskStateHistoryEntry(
    val id: Long,
    val taskId: Long,
    val event: TaskStateHistoryEvent,
    val source: TaskEventSource?,
    val fromStage: TaskStage?,
    val toStage: TaskStage,
    val paused: Boolean,
    val currentStep: String,
    val expectedActionType: ExpectedActionType,
    val expectedActionDescription: String?,
    val version: Long,
    val createdAt: Instant,
)

data class NewTaskStateHistoryEntry(
    val taskId: Long,
    val event: TaskStateHistoryEvent,
    val source: TaskEventSource?,
    val fromStage: TaskStage?,
    val toStage: TaskStage,
    val paused: Boolean,
    val currentStep: String,
    val expectedActionType: ExpectedActionType,
    val expectedActionDescription: String?,
    val version: Long,
    val createdAt: Instant,
)

data class PersistedTaskState(
    val stage: TaskStage,
    val currentStep: String,
    val expectedActionType: ExpectedActionType,
    val expectedActionDescription: String?,
    val paused: Boolean,
    val status: TaskStatus,
    val completedAt: Instant?,
    val expectedVersion: Long,
)

data class TaskProgressProposal(
    val currentStep: String? = null,
    val expectedActionType: ExpectedActionType? = null,
    val expectedActionDescription: String? = null,
    val suggestedEvent: TaskEvent? = null,
    val requestedAction: TaskActionType? = null,
    val reason: String? = null,
)

data class TaskProgressUpdate(
    val currentStep: String? = null,
    val expectedActionType: ExpectedActionType? = null,
    val expectedActionDescription: String? = null,
)
