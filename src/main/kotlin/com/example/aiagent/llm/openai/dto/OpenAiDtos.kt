package com.example.aiagent.llm.openai.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

internal data class OpenAiChatRequest(
    val model: String,
    val messages: List<OpenAiMessage>,
)

internal data class OpenAiMessage(
    val role: String,
    val content: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class OpenAiChatResponse(
    val model: String? = null,
    val choices: List<OpenAiChoice> = emptyList(),
    val usage: OpenAiUsage? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class OpenAiChoice(
    val message: OpenAiMessage? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class OpenAiUsage(
    @field:JsonProperty("prompt_tokens")
    val promptTokens: Int? = null,
    @field:JsonProperty("completion_tokens")
    val completionTokens: Int? = null,
    @field:JsonProperty("total_tokens")
    val totalTokens: Int? = null,
)
