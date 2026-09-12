package com.example.aiagent.agent

import com.example.aiagent.llm.LlmProvider

data class AgentResponse(
    val provider: LlmProvider,
    val content: String,
    val model: String,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val totalTokens: Int?,
    val responseTimeMs: Long,
)
