package com.example.aiagent.llm.compatible.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

internal data class ChatCompletionsRequest(
    val model: String,
    val messages: List<ChatCompletionsMessage>,
    val plugins: List<Plugin>
)

internal data class ChatCompletionsMessage(
    val role: String,
    val content: String,
)

internal data class Plugin(
    val id: String,
    val enabled: Boolean
) 

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class ChatCompletionsResponse(
    val model: String? = null,
    val choices: List<ChatCompletionsChoice> = emptyList(),
    val usage: ChatCompletionsUsage? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class ChatCompletionsChoice(
    val message: ChatCompletionsMessage? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class ChatCompletionsUsage(
    @field:JsonProperty("prompt_tokens")
    val promptTokens: Long? = null,
    @field:JsonProperty("completion_tokens")
    val completionTokens: Long? = null,
    @field:JsonProperty("total_tokens")
    val totalTokens: Long? = null,
)
