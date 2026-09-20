package com.example.aiagent.context

import com.example.aiagent.context.branch.ConversationBranchService
import com.example.aiagent.context.facts.MemoryFactRepository
import com.example.aiagent.persistence.ConversationRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class ContextStateService(
    private val conversationRepository: ConversationRepository,
    private val factsRepository: MemoryFactRepository,
    private val branchService: ConversationBranchService,
) {
    @Transactional
    fun reset(taskId: Long) {
        conversationRepository.clear(taskId)
        factsRepository.clear(taskId)
        branchService.reset(taskId)
    }
}
