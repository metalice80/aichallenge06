package com.example.aiagent.invariant

interface TaskInvariantRepository {
    fun findByTaskId(taskId: Long): List<TaskInvariant>
    fun findEnabledByTaskId(taskId: Long): List<TaskInvariant>
    fun findByTaskAndId(taskId: Long, invariantId: Long): TaskInvariant?
    fun create(command: NewTaskInvariant): TaskInvariant
    fun update(invariant: TaskInvariant): TaskInvariant
    fun delete(taskId: Long, invariantId: Long): Boolean
    fun saveLastCheck(check: LastInvariantCheck)
    fun lastCheck(taskId: Long): LastInvariantCheck?
}

data class NewTaskInvariant(
    val taskId: Long,
    val type: InvariantType,
    val key: String,
    val value: String,
    val description: String?,
    val enabled: Boolean,
)
