package com.example.aiagent.task

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class TaskStateService(
    private val repository: TaskRepository,
    private val stateMachine: TaskStateMachine,
) {
    fun state(taskId: Long): AgentTask = requireTask(taskId)

    fun history(taskId: Long): List<TaskStateHistoryEntry> {
        requireTask(taskId)
        return repository.stateHistory(taskId)
    }

    @Transactional
    fun applyEvent(
        taskId: Long,
        event: TaskEvent,
        proposal: TaskProgressProposal? = null,
    ): AgentTask {
        val current = requireTask(taskId)
        ensureMutable(current)
        val nextStage = stateMachine.transition(current.stage, event)
        val defaults = defaultProgress(event)
        val currentStep = normalizedCurrentStep(proposal?.currentStep ?: defaults.currentStep)
        val expectedActionType = if (nextStage == TaskStage.DONE) {
            ExpectedActionType.NONE
        } else {
            proposal?.expectedActionType ?: defaults.expectedActionType
        }
        val expectedActionDescription = normalizedActionDescription(
            expectedActionType,
            if (nextStage == TaskStage.DONE) null else proposal?.expectedActionDescription
                ?: defaults.expectedActionDescription,
        )
        val now = Instant.now()
        return repository.updateState(
            taskId = taskId,
            state = PersistedTaskState(
                stage = nextStage,
                currentStep = currentStep,
                expectedActionType = expectedActionType,
                expectedActionDescription = expectedActionDescription,
                paused = false,
                status = if (nextStage == TaskStage.DONE) TaskStatus.COMPLETED else TaskStatus.ACTIVE,
                completedAt = if (nextStage == TaskStage.DONE) now else null,
            ),
            history = NewTaskStateHistoryEntry(
                taskId = taskId,
                event = TaskStateHistoryEvent.from(event),
                fromStage = current.stage,
                toStage = nextStage,
                paused = false,
                currentStep = currentStep,
                description = expectedActionDescription,
                createdAt = now,
            ),
        )
    }

    @Transactional
    fun updateProgress(
        taskId: Long,
        currentStep: String,
        expectedActionType: ExpectedActionType,
        expectedActionDescription: String?,
    ): AgentTask {
        val current = requireTask(taskId)
        ensureMutable(current)
        val normalizedStep = normalizedCurrentStep(currentStep)
        val normalizedDescription = normalizedActionDescription(expectedActionType, expectedActionDescription)
        val now = Instant.now()
        return repository.updateState(
            taskId,
            current.toPersistedState(
                currentStep = normalizedStep,
                expectedActionType = expectedActionType,
                expectedActionDescription = normalizedDescription,
            ),
            NewTaskStateHistoryEntry(
                taskId = taskId,
                event = TaskStateHistoryEvent.PROGRESS_UPDATED,
                fromStage = current.stage,
                toStage = current.stage,
                paused = current.paused,
                currentStep = normalizedStep,
                description = normalizedDescription,
                createdAt = now,
            ),
        )
    }

    @Transactional
    fun pause(taskId: Long): AgentTask {
        val current = requireTask(taskId)
        if (current.stage == TaskStage.DONE) {
            throw InvalidTaskStateException("DONE Task cannot be paused")
        }
        if (current.paused) {
            throw InvalidTaskStateException("Task $taskId is already paused")
        }
        return updatePauseState(current, paused = true, TaskStateHistoryEvent.PAUSE)
    }

    @Transactional
    fun resume(taskId: Long): AgentTask {
        val current = requireTask(taskId)
        if (!current.paused) {
            throw InvalidTaskStateException("Task $taskId is not paused")
        }
        return updatePauseState(current, paused = false, TaskStateHistoryEvent.RESUME)
    }

    @Transactional
    fun applyProposal(taskId: Long, proposal: TaskProgressProposal): AgentTask {
        proposal.proposedEvent?.let { return applyEvent(taskId, it, proposal) }
        val current = requireTask(taskId)
        val currentStep = proposal.currentStep ?: current.currentStep
        val actionType = proposal.expectedActionType ?: current.expectedActionType
        val actionDescription = if (proposal.expectedActionType == null) {
            current.expectedActionDescription
        } else {
            proposal.expectedActionDescription
        }
        if (
            currentStep == current.currentStep &&
            actionType == current.expectedActionType &&
            actionDescription == current.expectedActionDescription
        ) {
            return current
        }
        return updateProgress(taskId, currentStep, actionType, actionDescription)
    }

    private fun updatePauseState(
        current: AgentTask,
        paused: Boolean,
        event: TaskStateHistoryEvent,
    ): AgentTask {
        val now = Instant.now()
        return repository.updateState(
            current.id,
            current.toPersistedState(paused = paused),
            NewTaskStateHistoryEntry(
                taskId = current.id,
                event = event,
                fromStage = current.stage,
                toStage = current.stage,
                paused = paused,
                currentStep = current.currentStep,
                description = current.expectedActionDescription,
                createdAt = now,
            ),
        )
    }

    private fun ensureMutable(task: AgentTask) {
        if (task.stage == TaskStage.DONE || task.status == TaskStatus.COMPLETED) {
            throw InvalidTaskStateException("Completed Task is read-only")
        }
        if (task.paused) {
            throw InvalidTaskStateException("Paused Task must be resumed before its state can change")
        }
    }

    private fun requireTask(taskId: Long): AgentTask =
        repository.findById(taskId) ?: throw InvalidTaskStateException("Task $taskId does not exist")

    private fun normalizedCurrentStep(value: String): String {
        val normalized = value.trim()
        if (normalized.isEmpty()) {
            throw InvalidTaskStateException("Current step must not be blank")
        }
        if (normalized.length > MAX_CURRENT_STEP_LENGTH) {
            throw InvalidTaskStateException("Current step must not exceed $MAX_CURRENT_STEP_LENGTH characters")
        }
        return normalized
    }

    private fun normalizedActionDescription(type: ExpectedActionType, value: String?): String? {
        if (type == ExpectedActionType.NONE) return null
        val normalized = value?.trim().orEmpty()
        if (normalized.isEmpty()) {
            throw InvalidTaskStateException("Expected action description is required for $type")
        }
        if (normalized.length > MAX_EXPECTED_ACTION_DESCRIPTION_LENGTH) {
            throw InvalidTaskStateException(
                "Expected action description must not exceed $MAX_EXPECTED_ACTION_DESCRIPTION_LENGTH characters",
            )
        }
        return normalized
    }

    private fun defaultProgress(event: TaskEvent): Progress = when (event) {
        TaskEvent.PLAN_APPROVED -> Progress(
            "Execute the approved plan",
            ExpectedActionType.AGENT_ACTION,
            "Perform the next implementation step",
        )
        TaskEvent.EXECUTION_COMPLETED -> Progress(
            "Validate the completed work",
            ExpectedActionType.VALIDATION,
            "Run validation and verify the acceptance criteria",
        )
        TaskEvent.VALIDATION_FAILED -> Progress(
            "Address validation failures",
            ExpectedActionType.AGENT_ACTION,
            "Fix the issues found during validation",
        )
        TaskEvent.VALIDATION_PASSED -> Progress(
            "Task completed",
            ExpectedActionType.NONE,
            null,
        )
    }

    private data class Progress(
        val currentStep: String,
        val expectedActionType: ExpectedActionType,
        val expectedActionDescription: String?,
    )

    companion object {
        const val MAX_CURRENT_STEP_LENGTH = 500
        const val MAX_EXPECTED_ACTION_DESCRIPTION_LENGTH = 1_000
    }
}

private fun AgentTask.toPersistedState(
    currentStep: String = this.currentStep,
    expectedActionType: ExpectedActionType = this.expectedActionType,
    expectedActionDescription: String? = this.expectedActionDescription,
    paused: Boolean = this.paused,
) = PersistedTaskState(
    stage = stage,
    currentStep = currentStep,
    expectedActionType = expectedActionType,
    expectedActionDescription = expectedActionDescription,
    paused = paused,
    status = status,
    completedAt = completedAt,
)
