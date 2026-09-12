package com.example.aiagent.agent

class Conversation {
    private val messages = mutableListOf<ChatMessage>()

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
    fun clear() {
        messages.clear()
    }
}
