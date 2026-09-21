package com.example.aiagent.agent

import com.example.aiagent.context.branch.ConversationBranch
import com.example.aiagent.context.strategy.ContextStrategyType
import com.example.aiagent.memory.MemoryInspector
import com.example.aiagent.task.AgentTask
import com.example.aiagent.task.ExpectedActionType
import com.example.aiagent.task.TaskEvent
import com.example.aiagent.task.TaskProgressProposal
import com.example.aiagent.task.TaskStateHistoryEntry
interface Agent {
    fun sendMessage(request: AgentRequest): AgentResponse
    fun history(): List<ChatMessage>
    fun state(): AgentState
    fun providers(): List<LlmProviderOption>
    fun contextStrategies(): List<ContextStrategyType>
    fun tasks(): List<AgentTask>
    fun createTask(name: String): AgentState
    fun activateTask(taskId: Long): AgentState
    fun completeTask(taskId: Long): AgentTask
    fun applyTaskEvent(taskId: Long, event: TaskEvent, proposal: TaskProgressProposal? = null): AgentTask
    fun updateTaskProgress(
        taskId: Long,
        currentStep: String,
        expectedActionType: ExpectedActionType,
        expectedActionDescription: String?,
    ): AgentTask
    fun pauseTask(taskId: Long): AgentTask
    fun resumeTask(taskId: Long): AgentTask
    fun taskStateHistory(taskId: Long): List<TaskStateHistoryEntry>
    fun branches(): List<ConversationBranch>
    fun createBranch(): ConversationBranch
    fun activateBranch(branchId: Long): AgentState
    fun memory(contextStrategy: ContextStrategyType): MemoryInspector
    fun clearWorkingMemory()
    fun clearLongTermMemory()
    fun reset()
}
