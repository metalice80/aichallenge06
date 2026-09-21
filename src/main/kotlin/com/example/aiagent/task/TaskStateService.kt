package com.example.aiagent.task

import org.slf4j.LoggerFactory
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
        source: TaskEventSource = TaskEventSource.REST_API,
        expectedVersion: Long? = null,
    ): AgentTask {
        val current = requireTask(taskId)
        verifyExpectedVersion(current, expectedVersion)
        if (current.paused) throw TaskPausedException(taskId)

        val nextStage = try {
            stateMachine.transition(current.stage, event)
        } catch (exception: InvalidTaskTransitionException) {
            logger.info(
                "Task state event task={} event={} source={} from={} paused={} result=REJECTED code=INVALID_TASK_TRANSITION",
                taskId,
                event,
                source,
                current.stage,
                current.paused,
            )
            throw exception
        }
        val defaults = defaultProgress(event)
        val currentStep = normalizedCurrentStep(proposal?.currentStep, defaults.currentStep)
        val expectedActionType = if (nextStage == TaskStage.DONE) {
            ExpectedActionType.NONE
        } else {
            proposal?.expectedActionType ?: defaults.expectedActionType
        }
        val expectedActionDescription = normalizedActionDescription(
            expectedActionType,
            if (nextStage == TaskStage.DONE) null else proposal?.expectedActionDescription,
            defaults.expectedActionDescription,
        )
        val now = Instant.now()
        val nextVersion = current.version + 1
        val updated = repository.updateState(
            taskId = taskId,
            state = PersistedTaskState(
                stage = nextStage,
                currentStep = currentStep,
                expectedActionType = expectedActionType,
                expectedActionDescription = expectedActionDescription,
                paused = false,
                status = if (nextStage == TaskStage.DONE) TaskStatus.COMPLETED else TaskStatus.ACTIVE,
                completedAt = if (nextStage == TaskStage.DONE) now else null,
                expectedVersion = current.version,
            ),
            history = NewTaskStateHistoryEntry(
                taskId = taskId,
                event = TaskStateHistoryEvent.from(event),
                source = source,
                fromStage = current.stage,
                toStage = nextStage,
                paused = false,
                currentStep = currentStep,
                expectedActionType = expectedActionType,
                expectedActionDescription = expectedActionDescription,
                version = nextVersion,
                createdAt = now,
            ),
        )
        logger.info(
            "Task state event task={} event={} source={} from={} to={} paused={} result=APPLIED version={}",
            taskId,
            event,
            source,
            current.stage,
            nextStage,
            updated.paused,
            updated.version,
        )
        return updated
    }

    @Transactional
    fun applyProposal(
        taskId: Long,
        proposal: TaskProgressProposal,
        source: TaskEventSource = TaskEventSource.CHAT_ANALYZER,
        expectedVersion: Long? = null,
    ): AgentTask {
        proposal.proposedEvent?.let {
            return applyEvent(taskId, it, proposal, source, expectedVersion)
        }
        val current = requireTask(taskId)
        verifyExpectedVersion(current, expectedVersion)
        ensureProgressMutable(current)

        val currentStep = normalizedCurrentStep(proposal.currentStep, current.currentStep)
        val expectedActionType = proposal.expectedActionType ?: current.expectedActionType
        val expectedDescriptionFallback = if (expectedActionType == current.expectedActionType) {
            current.expectedActionDescription
        } else {
            defaultProgress(current.stage).expectedActionDescription
        }
        val expectedActionDescription = normalizedActionDescription(
            expectedActionType,
            proposal.expectedActionDescription,
            expectedDescriptionFallback,
        )
        if (
            currentStep == current.currentStep &&
            expectedActionType == current.expectedActionType &&
            expectedActionDescription == current.expectedActionDescription
        ) {
            return current
        }

        val now = Instant.now()
        return repository.updateState(
            taskId,
            current.toPersistedState(
                currentStep = currentStep,
                expectedActionType = expectedActionType,
                expectedActionDescription = expectedActionDescription,
            ),
            NewTaskStateHistoryEntry(
                taskId = taskId,
                event = TaskStateHistoryEvent.PROGRESS_UPDATED,
                source = source,
                fromStage = current.stage,
                toStage = current.stage,
                paused = current.paused,
                currentStep = currentStep,
                expectedActionType = expectedActionType,
                expectedActionDescription = expectedActionDescription,
                version = current.version + 1,
                createdAt = now,
            ),
        )
    }

    @Transactional
    fun pause(
        taskId: Long,
        source: TaskEventSource = TaskEventSource.REST_API,
        expectedVersion: Long? = null,
    ): AgentTask {
        val current = requireTask(taskId)
        verifyExpectedVersion(current, expectedVersion)
        if (current.stage == TaskStage.DONE) {
            throw InvalidTaskStateException("DONE Task cannot be paused", "TASK_DONE")
        }
        if (current.paused) {
            throw InvalidTaskStateException("Task $taskId is already paused", "TASK_ALREADY_PAUSED")
        }
        return updatePauseState(current, paused = true, TaskStateHistoryEvent.PAUSE, source)
    }

    @Transactional
    fun resume(
        taskId: Long,
        source: TaskEventSource = TaskEventSource.REST_API,
        expectedVersion: Long? = null,
    ): AgentTask {
        val current = requireTask(taskId)
        verifyExpectedVersion(current, expectedVersion)
        if (!current.paused) {
            throw InvalidTaskStateException("Task $taskId is not paused", "TASK_NOT_PAUSED")
        }
        return updatePauseState(current, paused = false, TaskStateHistoryEvent.RESUME, source)
    }

    private fun updatePauseState(
        current: AgentTask,
        paused: Boolean,
        event: TaskStateHistoryEvent,
        source: TaskEventSource,
    ): AgentTask {
        val now = Instant.now()
        val updated = repository.updateState(
            current.id,
            current.toPersistedState(paused = paused),
            NewTaskStateHistoryEntry(
                taskId = current.id,
                event = event,
                source = source,
                fromStage = current.stage,
                toStage = current.stage,
                paused = paused,
                currentStep = current.currentStep,
                expectedActionType = current.expectedActionType,
                expectedActionDescription = current.expectedActionDescription,
                version = current.version + 1,
                createdAt = now,
            ),
        )
        logger.info(
            "Task state event task={} event={} source={} from={} to={} paused={} result=APPLIED version={}",
            current.id,
            event,
            source,
            current.stage,
            current.stage,
            updated.paused,
            updated.version,
        )
        return updated
    }

    private fun verifyExpectedVersion(current: AgentTask, expectedVersion: Long?) {
        if (expectedVersion != null && current.version != expectedVersion) {
            throw TaskStateConflictException(current.id, expectedVersion, current.version)
        }
    }

    private fun ensureProgressMutable(task: AgentTask) {
        if (task.stage == TaskStage.DONE || task.status == TaskStatus.COMPLETED) {
            throw InvalidTaskStateException("Completed Task is read-only", "TASK_DONE")
        }
        if (task.paused) throw TaskPausedException(task.id)
    }

    private fun requireTask(taskId: Long): AgentTask =
        repository.findById(taskId) ?: throw TaskNotFoundException(taskId)

    private fun normalizedCurrentStep(value: String?, fallback: String): String {
        val normalized = value?.trim()
        return if (
            normalized.isNullOrEmpty() ||
            normalized.length > MAX_CURRENT_STEP_LENGTH
        ) {
            fallback
        } else {
            normalized
        }
    }

    private fun normalizedActionDescription(
        type: ExpectedActionType,
        value: String?,
        fallback: String?,
    ): String? {
        if (type == ExpectedActionType.NONE) return null
        val normalized = value?.trim()
        if (!normalized.isNullOrEmpty() && normalized.length <= MAX_EXPECTED_ACTION_DESCRIPTION_LENGTH) {
            return normalized
        }
        val normalizedFallback = fallback?.trim()
        if (!normalizedFallback.isNullOrEmpty() &&
            normalizedFallback.length <= MAX_EXPECTED_ACTION_DESCRIPTION_LENGTH
        ) {
            return normalizedFallback
        }
        throw InvalidTaskStateException(
            "Expected action description is required for $type",
            "INVALID_EXPECTED_ACTION",
        )
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

    private fun defaultProgress(stage: TaskStage): Progress = when (stage) {
        TaskStage.PLANNING -> Progress(
            "Define goals, requirements, and execution plan",
            ExpectedActionType.USER_CONFIRMATION,
            "Approve the plan before implementation",
        )
        TaskStage.EXECUTION -> defaultProgress(TaskEvent.PLAN_APPROVED)
        TaskStage.VALIDATION -> defaultProgress(TaskEvent.EXECUTION_COMPLETED)
        TaskStage.DONE -> defaultProgress(TaskEvent.VALIDATION_PASSED)
    }

    private data class Progress(
        val currentStep: String,
        val expectedActionType: ExpectedActionType,
        val expectedActionDescription: String?,
    )

    companion object {
        const val MAX_CURRENT_STEP_LENGTH = 500
        const val MAX_EXPECTED_ACTION_DESCRIPTION_LENGTH = 1_000
        private val logger = LoggerFactory.getLogger(TaskStateService::class.java)
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
    expectedVersion = version,
)
