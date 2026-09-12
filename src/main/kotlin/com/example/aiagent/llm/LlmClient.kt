package com.example.aiagent.llm

interface LlmClient {
    val provider: LlmProvider
    val defaultModel: String

    fun chat(request: LlmRequest): LlmResponse
}
