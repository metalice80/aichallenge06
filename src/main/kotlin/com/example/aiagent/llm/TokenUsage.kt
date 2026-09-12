package com.example.aiagent.llm

data class TokenUsage(
    val inputTokens: Long?,
    val outputTokens: Long?,
    val totalTokens: Long?,
) {
    init {
        require(inputTokens == null || inputTokens >= 0) { "inputTokens must not be negative" }
        require(outputTokens == null || outputTokens >= 0) { "outputTokens must not be negative" }
        require(totalTokens == null || totalTokens >= 0) { "totalTokens must not be negative" }
    }

    companion object {
        fun fromProvider(
            inputTokens: Long?,
            outputTokens: Long?,
            totalTokens: Long?,
        ): TokenUsage = TokenUsage(
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            totalTokens = totalTokens ?: if (inputTokens != null && outputTokens != null) {
                Math.addExact(inputTokens, outputTokens)
            } else {
                null
            },
        )
    }
}
