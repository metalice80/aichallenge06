package com.example.aiagent.agent

import com.example.aiagent.llm.TokenUsage

data class ConversationTokenUsage(
    val inputTokens: Long,
    val outputTokens: Long,
    val totalTokens: Long,
) {
    init {
        require(inputTokens >= 0) { "inputTokens must not be negative" }
        require(outputTokens >= 0) { "outputTokens must not be negative" }
        require(totalTokens >= 0) { "totalTokens must not be negative" }
    }

    fun plus(usage: TokenUsage): ConversationTokenUsage {
        val inputIncrement = usage.inputTokens ?: 0
        val outputIncrement = usage.outputTokens ?: 0
        val totalIncrement = usage.totalTokens
            ?: Math.addExact(inputIncrement, outputIncrement)

        return ConversationTokenUsage(
            inputTokens = Math.addExact(inputTokens, inputIncrement),
            outputTokens = Math.addExact(outputTokens, outputIncrement),
            totalTokens = Math.addExact(totalTokens, totalIncrement),
        )
    }

    companion object {
        val ZERO = ConversationTokenUsage(0, 0, 0)
    }
}
