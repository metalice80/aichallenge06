package com.example.aiagent.context.facts

interface MemoryFactRepository {
    fun findAll(taskId: Long): List<MemoryFact>
    fun apply(taskId: Long, update: FactsUpdate)
    fun clear(taskId: Long)
}
