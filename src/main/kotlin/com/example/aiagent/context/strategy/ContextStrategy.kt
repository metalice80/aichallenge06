package com.example.aiagent.context.strategy

import com.example.aiagent.agent.ChatMessage
import com.example.aiagent.agent.Conversation

enum class ContextStrategyType(val displayName: String) {
    SLIDING_WINDOW("Sliding Window"),
    STICKY_FACTS("Sticky Facts"),
    BRANCHING("Branching"),
}

data class ContextPlan(
    val contextMessages: List<ChatMessage>,
)

interface ContextStrategy {
    val type: ContextStrategyType

    fun buildContext(conversation: Conversation): ContextPlan

    fun afterSuccessfulExchange(
        userMessage: ChatMessage,
        assistantMessage: ChatMessage,
    ) = Unit
}
