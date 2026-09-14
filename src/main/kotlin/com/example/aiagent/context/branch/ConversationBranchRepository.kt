package com.example.aiagent.context.branch

import com.example.aiagent.agent.ChatMessage

interface ConversationBranchRepository {
    fun ensureMainInitialized(seedHistory: List<ChatMessage>)
    fun findAll(): List<ConversationBranch>
    fun activeBranch(): ConversationBranch
    fun effectiveHistory(branchId: Long): List<ChatMessage>
    fun createFromActive(): ConversationBranch
    fun activate(branchId: Long)
    fun appendToActive(messages: List<ChatMessage>)
    fun reset()
}
