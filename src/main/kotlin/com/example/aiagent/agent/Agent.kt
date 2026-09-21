package com.example.aiagent.agent

import com.example.aiagent.context.branch.ConversationBranch
import com.example.aiagent.context.strategy.ContextStrategyType
import com.example.aiagent.memory.MemoryInspector
import com.example.aiagent.task.AgentTask
interface Agent {
    fun sendMessage(request: AgentRequest): AgentResponse
    fun history(): List<ChatMessage>
    fun state(): AgentState
    fun providers(): List<LlmProviderOption>
    fun contextStrategies(): List<ContextStrategyType>
    fun tasks(): List<AgentTask>
    fun createTask(name: String): AgentState
    fun activateTask(taskId: Long): AgentState
    fun branches(): List<ConversationBranch>
    fun createBranch(): ConversationBranch
    fun activateBranch(branchId: Long): AgentState
    fun memory(contextStrategy: ContextStrategyType): MemoryInspector
    fun clearWorkingMemory()
    fun clearLongTermMemory()
    fun reset()
}
