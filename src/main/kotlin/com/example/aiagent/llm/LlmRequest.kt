package com.example.aiagent.llm

import com.example.aiagent.agent.ChatMessage

data class LlmRequest(
    val messages: List<ChatMessage>,
    val model: String,
)
