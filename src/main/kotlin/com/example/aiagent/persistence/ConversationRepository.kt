package com.example.aiagent.persistence

import com.example.aiagent.agent.Conversation
import com.example.aiagent.agent.ConversationSummary
import com.example.aiagent.agent.LlmRequestUsage

interface ConversationRepository {
    fun load(taskId: Long): Conversation

    fun save(conversation: Conversation)
    fun save(conversation: Conversation, requestUsage: LlmRequestUsage)
    fun saveSummary(taskId: Long, summary: ConversationSummary)

    fun clear(taskId: Long)
}
