package com.example.aiagent.memory

import com.example.aiagent.agent.ChatMessage
import com.example.aiagent.context.strategy.ContextStrategyType
import com.example.aiagent.profile.UserProfileSnapshot
import java.time.Instant

data class MemoryEntry(
    val key: String,
    val value: String,
) {
    init {
        require(key.isNotBlank()) { "Memory key must not be blank" }
        require(value.isNotBlank()) { "Memory value must not be blank" }
    }
}

data class MemoryLayerUpdate(
    val upsert: List<MemoryEntry> = emptyList(),
    val delete: List<String> = emptyList(),
)

data class MemoryUpdate(
    val working: MemoryLayerUpdate = MemoryLayerUpdate(),
    val longTerm: MemoryLayerUpdate = MemoryLayerUpdate(),
)

enum class MemoryLayer {
    WORKING,
    LONG_TERM,
}

enum class MemoryChangeType {
    ADDED,
    UPDATED,
    DELETED,
}

data class MemoryChange(
    val type: MemoryChangeType,
    val key: String,
    val oldValue: String? = null,
    val newValue: String? = null,
)

data class LastMemoryUpdate(
    val taskId: Long,
    val userMessage: String,
    val working: List<MemoryChange>,
    val longTerm: List<MemoryChange>,
    val error: String? = null,
    val updatedAt: Instant,
)

data class EffectiveContext(
    val taskId: Long,
    val strategy: ContextStrategyType,
    val systemPrompt: String,
    val longTermMemory: List<MemoryEntry>,
    val userProfile: UserProfileSnapshot?,
    val workingMemory: List<MemoryEntry>,
    val shortTerm: List<ChatMessage>,
    val currentUserMessage: ChatMessage,
    val preparedAt: Instant,
)

data class MemoryInspector(
    val shortTerm: List<ChatMessage>,
    val working: List<MemoryEntry>,
    val longTerm: List<MemoryEntry>,
    val lastUpdate: LastMemoryUpdate?,
    val effectiveContext: EffectiveContext?,
)
