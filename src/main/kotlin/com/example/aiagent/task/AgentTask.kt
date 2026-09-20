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
)
