package com.example.aiagent.memory

interface MemoryRepository {
    fun findWorking(taskId: Long): List<MemoryEntry>
    fun findLongTerm(): List<MemoryEntry>
    fun apply(taskId: Long, userMessage: String, update: MemoryUpdate): LastMemoryUpdate
    fun recordFailure(taskId: Long, userMessage: String, error: String): LastMemoryUpdate
    fun lastUpdate(taskId: Long): LastMemoryUpdate?
    fun saveEffectiveContext(context: EffectiveContext)
    fun effectiveContext(taskId: Long): EffectiveContext?
    fun clearWorking(taskId: Long)
    fun clearLongTerm()
}
