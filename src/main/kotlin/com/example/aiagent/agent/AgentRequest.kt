package com.example.aiagent.agent

import com.example.aiagent.llm.LlmProvider

data class AgentRequest(
    val message: String,
    val provider: LlmProvider,
    val model: String,
)
