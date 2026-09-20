package com.example.aiagent.memory

import com.example.aiagent.agent.ChatMessage
import com.example.aiagent.task.AgentTask

interface MemoryExtractor {
    fun extract(
        userMessage: ChatMessage,
        task: AgentTask,
        currentWorkingMemory: List<MemoryEntry>,
        currentLongTermMemory: List<MemoryEntry>,
    ): MemoryUpdate
}
