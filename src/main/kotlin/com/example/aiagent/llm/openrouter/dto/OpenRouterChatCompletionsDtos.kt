package com.example.aiagent.llm.openrouter.dto

import com.fasterxml.jackson.annotation.JsonInclude

internal data class OpenRouterChatCompletionsRequest(
    val model: String,
    val messages: List<OpenRouterChatCompletionsMessage>,
    @field:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val plugins: List<OpenRouterPlugin>? = null,
)

internal data class OpenRouterChatCompletionsMessage(
    val role: String,
    val content: String,
)

internal data class OpenRouterPlugin(
    val id: String,
    val enabled: Boolean,
)
