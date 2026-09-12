package com.example.aiagent.agent

data class AgentState(
    val messages: List<ChatMessage>,
    val conversationUsage: ConversationTokenUsage,
)
