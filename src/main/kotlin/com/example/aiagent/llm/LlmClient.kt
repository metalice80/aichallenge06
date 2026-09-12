package com.example.aiagent.llm

interface LlmClient {
    fun chat(request: LlmRequest): LlmResponse
}
