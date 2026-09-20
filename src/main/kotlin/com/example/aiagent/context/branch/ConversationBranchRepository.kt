package com.example.aiagent.context.branch

import com.example.aiagent.agent.ChatMessage

interface ConversationBranchRepository {
    fun ensureMainInitialized(taskId: Long, seedHistory: List<ChatMessage>)
    fun findAll(taskId: Long): List<ConversationBranch>
    fun activeBranch(taskId: Long): ConversationBranch
    fun effectiveHistory(taskId: Long, branchId: Long): List<ChatMessage>
    fun createFromActive(taskId: Long): ConversationBranch
    fun activate(taskId: Long, branchId: Long)
    fun appendToActive(taskId: Long, messages: List<ChatMessage>)
    fun reset(taskId: Long)
}
