package com.example.aiagent.agent

data class ConversationSummary(
    val content: String,
    val summarizedMessageCount: Int,
) {
    init {
        require(content.isNotBlank()) { "Summary content must not be blank" }
        require(summarizedMessageCount > 0) { "Summarized message count must be positive" }
    }
}
