package com.example.aiagent.context

import com.example.aiagent.agent.ChatMessage

interface ConversationSummarizer {
    fun summarize(
        currentSummary: String?,
        messages: List<ChatMessage>,
    ): String
}
