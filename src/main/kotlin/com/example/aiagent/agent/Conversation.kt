package com.example.aiagent.agent

import com.example.aiagent.llm.TokenUsage

class Conversation {
    private val messages = mutableListOf<ChatMessage>()
    private var tokenUsage = ConversationTokenUsage.ZERO

    @Synchronized
    fun add(message: ChatMessage) {
        messages += message
    }

    @Synchronized
    fun addAll(newMessages: Collection<ChatMessage>) {
        messages += newMessages
    }

    @Synchronized
    fun messages(): List<ChatMessage> = messages.toList()

    @Synchronized
    fun tokenUsage(): ConversationTokenUsage = tokenUsage

    @Synchronized
    fun addUsage(usage: TokenUsage) {
        tokenUsage = tokenUsage.plus(usage)
    }

    @Synchronized
    fun restore(
        restoredMessages: Collection<ChatMessage>,
        restoredTokenUsage: ConversationTokenUsage,
    ) {
        messages.clear()
        messages.addAll(restoredMessages)
        tokenUsage = restoredTokenUsage
    }

    @Synchronized
    fun clear() {
        messages.clear()
        tokenUsage = ConversationTokenUsage.ZERO
    }
}
