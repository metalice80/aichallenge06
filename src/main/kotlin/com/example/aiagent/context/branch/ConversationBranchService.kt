package com.example.aiagent.context.branch

import com.example.aiagent.agent.ChatMessage
import org.springframework.stereotype.Component

@Component
class ConversationBranchService(
    private val repository: ConversationBranchRepository,
) {
    fun branches(): List<ConversationBranch> = repository.findAll()

    fun activeHistory(seedHistory: List<ChatMessage>): List<ChatMessage> {
        repository.ensureMainInitialized(seedHistory)
        return repository.effectiveHistory(repository.activeBranch().id)
    }

    fun createBranch(seedHistory: List<ChatMessage>): ConversationBranch {
        repository.ensureMainInitialized(seedHistory)
        return repository.createFromActive()
    }

    fun activateBranch(
        branchId: Long,
        seedHistory: List<ChatMessage>,
    ): List<ChatMessage> {
        repository.ensureMainInitialized(seedHistory)
        repository.activate(branchId)
        return repository.effectiveHistory(branchId)
    }

    fun appendToActive(messages: List<ChatMessage>) {
        repository.appendToActive(messages)
    }

    fun reset() {
        repository.reset()
    }
}
