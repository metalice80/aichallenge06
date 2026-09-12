package com.example.aiagent.llm

data class LlmResponse(
    val content: String,
    val model: String,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val totalTokens: Int?,
)
