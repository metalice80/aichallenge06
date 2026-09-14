package com.example.aiagent.context.facts

import com.example.aiagent.agent.ChatMessage

interface FactsExtractor {
    fun extract(
        existingFacts: List<MemoryFact>,
        userMessage: ChatMessage,
    ): FactsUpdate
}
