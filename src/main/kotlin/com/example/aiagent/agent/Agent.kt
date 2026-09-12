package com.example.aiagent.agent

interface Agent {
    fun sendMessage(message: String): AgentResponse
    fun history(): List<ChatMessage>

    fun reset()
}
