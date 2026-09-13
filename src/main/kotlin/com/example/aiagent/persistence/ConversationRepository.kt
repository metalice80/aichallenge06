package com.example.aiagent.persistence

import com.example.aiagent.agent.Conversation
import com.example.aiagent.agent.ConversationSummary
import com.example.aiagent.agent.LlmRequestUsage

interface ConversationRepository {
    fun load(): Conversation

    fun save(conversation: Conversation)
    fun save(conversation: Conversation, requestUsage: LlmRequestUsage)
    fun saveSummary(summary: ConversationSummary)

    fun clear()
}
