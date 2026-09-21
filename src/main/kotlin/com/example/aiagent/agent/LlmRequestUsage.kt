package com.example.aiagent.agent

import com.example.aiagent.llm.LlmProvider
import com.example.aiagent.llm.TokenUsage

enum class LlmRequestPurpose {
    MAIN_REQUEST,
    INVARIANT_INPUT_GUARD,
    INVARIANT_OUTPUT_GUARD,
    INVARIANT_CORRECTIVE_RETRY,
}

data class LlmRequestUsage(
    val provider: LlmProvider,
    val purpose: LlmRequestPurpose = LlmRequestPurpose.MAIN_REQUEST,
    val model: String,
    val tokenUsage: TokenUsage,
    val responseTimeMs: Long,
) {
    init {
        require(responseTimeMs >= 0) { "responseTimeMs must not be negative" }
    }
}
