package com.example.aiagent.agent

import com.example.aiagent.llm.TokenUsage

class Conversation(
    val taskId: Long = 1,
) {
    private val messages = mutableListOf<ChatMessage>()
    private var tokenUsage = ConversationTokenUsage.ZERO
    private var summary: ConversationSummary? = null

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
    fun summary(): ConversationSummary? = summary

    @Synchronized
    fun addUsage(usage: TokenUsage) {
        tokenUsage = tokenUsage.plus(usage)
    }

    @Synchronized
    fun updateSummary(newSummary: ConversationSummary) {
        require(newSummary.summarizedMessageCount <= messages.size) {
            "Summary cursor must not exceed the conversation size"
        }
        summary = newSummary
    }

    @Synchronized
    fun restore(
        restoredMessages: Collection<ChatMessage>,
        restoredTokenUsage: ConversationTokenUsage,
        restoredSummary: ConversationSummary?,
    ) {
        require(
            restoredSummary == null ||
                restoredSummary.summarizedMessageCount <= restoredMessages.size,
        ) {
            "Summary cursor must not exceed the conversation size"
        }
        messages.clear()
        messages.addAll(restoredMessages)
        tokenUsage = restoredTokenUsage
        summary = restoredSummary
    }

    @Synchronized
    fun clear() {
        messages.clear()
        tokenUsage = ConversationTokenUsage.ZERO
        summary = null
    }
}
