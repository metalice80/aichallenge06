package com.example.aiagent.invariant

import com.example.aiagent.task.AgentTask
import com.example.aiagent.task.TaskRepository
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class TaskInvariantService(
    private val repository: TaskInvariantRepository,
    private val taskRepository: TaskRepository,
) {
    fun list(taskId: Long): List<TaskInvariant> {
        requireTask(taskId)
        return repository.findByTaskId(taskId)
    }

    fun get(taskId: Long, invariantId: Long): TaskInvariant {
        requireTask(taskId)
        return requireInvariant(taskId, invariantId)
    }

    @Transactional
    fun create(taskId: Long, command: CreateTaskInvariant): TaskInvariant {
        requireTask(taskId)
        val normalized = normalize(command)
        return conflictSafe(normalized.key) {
            repository.create(
                NewTaskInvariant(
                    taskId = taskId,
                    type = normalized.type,
                    key = normalized.key,
                    value = normalized.value,
                    description = normalized.description,
                    enabled = normalized.enabled,
                ),
            )
        }
    }

    @Transactional
    fun update(taskId: Long, invariantId: Long, command: UpdateTaskInvariant): TaskInvariant {
        requireTask(taskId)
        val existing = requireInvariant(taskId, invariantId)
        val normalized = normalize(command)
        return conflictSafe(normalized.key) {
            repository.update(
                existing.copy(
                    type = normalized.type,
                    key = normalized.key,
                    value = normalized.value,
                    description = normalized.description,
                    enabled = normalized.enabled,
                ),
            )
        }
    }

    @Transactional
    fun setEnabled(taskId: Long, invariantId: Long, enabled: Boolean): TaskInvariant {
        requireTask(taskId)
        val existing = requireInvariant(taskId, invariantId)
        if (existing.enabled == enabled) return existing
        return conflictSafe(existing.key) {
            repository.update(existing.copy(enabled = enabled))
        }
    }

    @Transactional
    fun delete(taskId: Long, invariantId: Long) {
        requireTask(taskId)
        requireInvariant(taskId, invariantId)
        if (!repository.delete(taskId, invariantId)) {
            throw TaskInvariantNotFoundException(
                "Invariant $invariantId does not exist for Task $taskId.",
            )
        }
    }

    fun findEnabled(taskId: Long): List<TaskInvariant> {
        requireTask(taskId)
        return repository.findEnabledByTaskId(taskId)
    }

    fun snapshot(task: AgentTask): TaskInvariantSet {
        val invariants = repository.findEnabledByTaskId(task.id).map { invariant ->
            TaskInvariantSnapshot(
                id = invariant.id,
                taskId = task.id,
                taskName = task.name,
                type = invariant.type,
                key = invariant.key,
                value = invariant.value,
                description = invariant.description,
            )
        }
        return TaskInvariantSet(task.id, task.name, invariants.toList())
    }

    fun recordCheck(check: LastInvariantCheck) = repository.saveLastCheck(check)

    fun lastCheck(taskId: Long): LastInvariantCheck? {
        requireTask(taskId)
        return repository.lastCheck(taskId)
    }

    private fun normalize(command: CreateTaskInvariant) = NormalizedInvariant(
        type = command.type,
        key = normalizeKey(command.key),
        value = normalizeValue(command.value),
        description = normalizeDescription(command.description),
        enabled = command.enabled,
    )

    private fun normalize(command: UpdateTaskInvariant) = NormalizedInvariant(
        type = command.type,
        key = normalizeKey(command.key),
        value = normalizeValue(command.value),
        description = normalizeDescription(command.description),
        enabled = command.enabled,
    )

    private fun normalizeKey(value: String): String {
        val normalized = value.trim().lowercase()
        if (normalized.isEmpty()) {
            throw InvalidTaskInvariantException("Invariant key must not be blank.")
        }
        if (normalized.length > MAX_KEY_LENGTH) {
            throw InvalidTaskInvariantException("Invariant key must not exceed $MAX_KEY_LENGTH characters.")
        }
        return normalized
    }

    private fun normalizeValue(value: String): String {
        val normalized = value.trim()
        if (normalized.isEmpty()) {
            throw InvalidTaskInvariantException("Invariant value must not be blank.")
        }
        if (normalized.length > MAX_VALUE_LENGTH) {
            throw InvalidTaskInvariantException("Invariant value must not exceed $MAX_VALUE_LENGTH characters.")
        }
        return normalized
    }

    private fun normalizeDescription(value: String?): String? {
        val normalized = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        if (normalized.length > MAX_DESCRIPTION_LENGTH) {
            throw InvalidTaskInvariantException(
                "Invariant description must not exceed $MAX_DESCRIPTION_LENGTH characters.",
            )
        }
        return normalized
    }

    private fun requireTask(taskId: Long): AgentTask =
        taskRepository.findById(taskId)
            ?: throw TaskInvariantNotFoundException("Task $taskId does not exist.")

    private fun requireInvariant(taskId: Long, invariantId: Long): TaskInvariant =
        repository.findByTaskAndId(taskId, invariantId)
            ?: throw TaskInvariantNotFoundException(
                "Invariant $invariantId does not exist for Task $taskId.",
            )

    private fun <T> conflictSafe(key: String, action: () -> T): T = try {
        action()
    } catch (exception: DataAccessException) {
        val isUniqueConstraint = generateSequence<Throwable>(exception) { it.cause }
            .any { it.message?.contains("SQLITE_CONSTRAINT_UNIQUE") == true }
        if (!isUniqueConstraint) {
            throw exception
        }
        throw TaskInvariantConflictException(
            "Active invariant with key \"$key\" already exists for this Task. " +
                "Edit or disable the existing invariant first.",
        )
    }

    private data class NormalizedInvariant(
        val type: InvariantType,
        val key: String,
        val value: String,
        val description: String?,
        val enabled: Boolean,
    )

    companion object {
        const val MAX_KEY_LENGTH = 120
        const val MAX_VALUE_LENGTH = 1_000
        const val MAX_DESCRIPTION_LENGTH = 2_000
    }
}
