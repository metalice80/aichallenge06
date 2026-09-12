package com.example.aiagent.persistence

import com.example.aiagent.agent.Conversation

interface ConversationRepository {
    fun load(): Conversation

    fun save(conversation: Conversation)

    fun clear()
}
