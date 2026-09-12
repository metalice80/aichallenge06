package com.example.aiagent.agent

interface Agent {
    fun sendMessage(request: AgentRequest): AgentResponse
    fun history(): List<ChatMessage>
    fun state(): AgentState
    fun providers(): List<LlmProviderOption>

    fun reset()
}
