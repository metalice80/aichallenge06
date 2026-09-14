package com.example.aiagent.agent

import com.example.aiagent.llm.LlmProvider
import com.example.aiagent.context.strategy.ContextStrategyType

data class AgentRequest(
    val message: String,
    val provider: LlmProvider,
    val model: String,
    val contextStrategy: ContextStrategyType = ContextStrategyType.SLIDING_WINDOW,
)
