package com.example.aiagent.agent

import com.example.aiagent.llm.LlmProvider

data class LlmProviderOption(
    val provider: LlmProvider,
    val displayName: String,
    val defaultModel: String,
)
