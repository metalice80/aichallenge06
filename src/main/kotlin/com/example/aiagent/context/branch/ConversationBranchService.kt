package com.example.aiagent.context.branch

import com.example.aiagent.agent.ChatMessage
import org.springframework.stereotype.Component

@Component
class ConversationBranchService(
    private val repository: ConversationBranchRepository,
) {
    fun branches(taskId: Long): List<ConversationBranch> = repository.findAll(taskId)

    fun activeHistory(taskId: Long, seedHistory: List<ChatMessage>): List<ChatMessage> {
        repository.ensureMainInitialized(taskId, seedHistory)
        return repository.effectiveHistory(taskId, repository.activeBranch(taskId).id)
    }

    fun createBranch(taskId: Long, seedHistory: List<ChatMessage>): ConversationBranch {
        repository.ensureMainInitialized(taskId, seedHistory)
        return repository.createFromActive(taskId)
    }

    fun activateBranch(
        taskId: Long,
        branchId: Long,
        seedHistory: List<ChatMessage>,
    ): List<ChatMessage> {
        repository.ensureMainInitialized(taskId, seedHistory)
        repository.activate(taskId, branchId)
        return repository.effectiveHistory(taskId, branchId)
    }

    fun appendToActive(taskId: Long, messages: List<ChatMessage>) {
        repository.appendToActive(taskId, messages)
    }

    fun reset(taskId: Long) {
        repository.reset(taskId)
    }
}
