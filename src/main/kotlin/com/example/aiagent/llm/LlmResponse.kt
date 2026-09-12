package com.example.aiagent.llm

data class LlmResponse(
    val content: String,
    val model: String,
    val usage: TokenUsage,
)
