package com.example.aiagent.agent

data class AgentResponse(
    val content: String,
    val model: String,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val totalTokens: Int?,
    val responseTimeMs: Long,
)
