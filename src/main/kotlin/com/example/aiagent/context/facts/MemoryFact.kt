package com.example.aiagent.context.facts

data class MemoryFact(
    val key: String,
    val value: String,
) {
    init {
        require(key.isNotBlank()) { "Fact key must not be blank" }
        require(value.isNotBlank()) { "Fact value must not be blank" }
    }
}

data class FactsUpdate(
    val upsert: List<MemoryFact> = emptyList(),
    val deleteKeys: List<String> = emptyList(),
)
