package com.example.aiagent.agent

import com.example.aiagent.llm.LlmProvider
import com.example.aiagent.llm.TokenUsage

data class LlmRequestUsage(
    val provider: LlmProvider,
    val model: String,
    val tokenUsage: TokenUsage,
    val responseTimeMs: Long,
) {
    init {
        require(responseTimeMs >= 0) { "responseTimeMs must not be negative" }
    }
}
