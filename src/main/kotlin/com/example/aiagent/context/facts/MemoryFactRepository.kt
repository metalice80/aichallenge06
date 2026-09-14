package com.example.aiagent.context.facts

interface MemoryFactRepository {
    fun findAll(): List<MemoryFact>
    fun apply(update: FactsUpdate)
    fun clear()
}
