package com.example.aiagent.task

import org.springframework.stereotype.Service

@Service
class TaskService(
    private val repository: TaskRepository,
) {
    fun tasks(): List<AgentTask> = repository.findAll()

    fun activeTask(): AgentTask = repository.active()

    fun create(name: String): AgentTask {
        val normalized = name.trim()
        require(normalized.isNotEmpty()) { "Task name must not be blank" }
        require(normalized.length <= MAX_NAME_LENGTH) {
            "Task name must not exceed $MAX_NAME_LENGTH characters"
        }
        return repository.create(normalized)
    }

    fun activate(taskId: Long): AgentTask = repository.activate(taskId)


    companion object {
        const val MAX_NAME_LENGTH = 120
    }
}
