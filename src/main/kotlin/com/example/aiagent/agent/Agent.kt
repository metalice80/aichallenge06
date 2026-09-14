package com.example.aiagent.agent

import com.example.aiagent.context.branch.ConversationBranch
import com.example.aiagent.context.strategy.ContextStrategyType

interface Agent {
    fun sendMessage(request: AgentRequest): AgentResponse
    fun history(): List<ChatMessage>
    fun state(): AgentState
    fun providers(): List<LlmProviderOption>
    fun contextStrategies(): List<ContextStrategyType>
    fun branches(): List<ConversationBranch>
    fun createBranch(): ConversationBranch
    fun activateBranch(branchId: Long): AgentState

    fun reset()
}
