package com.example.aiagent.context.strategy

import com.example.aiagent.agent.ChatMessage
import com.example.aiagent.agent.Conversation
import com.example.aiagent.context.branch.ConversationBranchService
import org.springframework.stereotype.Component

@Component
class BranchingContextStrategy(
    private val branchService: ConversationBranchService,
) : ContextStrategy {
    override val type = ContextStrategyType.BRANCHING

    override fun buildContext(conversation: Conversation): ContextPlan {
        val history = branchService.activeHistory(conversation.messages())
        return ContextPlan(
            contextMessages = history,
        )
    }

    override fun afterSuccessfulExchange(
        userMessage: ChatMessage,
        assistantMessage: ChatMessage,
    ) {
        branchService.appendToActive(listOf(userMessage, assistantMessage))
    }
}
