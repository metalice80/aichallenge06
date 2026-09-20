package com.example.aiagent.task

interface TaskRepository {
    fun findAll(): List<AgentTask>
    fun active(): AgentTask
    fun findById(taskId: Long): AgentTask?
    fun create(name: String): AgentTask
    fun activate(taskId: Long): AgentTask
    fun complete(taskId: Long): AgentTask
}
