package com.example.aiagent.llm

import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
class StructuredOutputParser(
    private val jsonMapper: ObjectMapper,
) {
    fun <T> parse(content: String, type: Class<T>, provider: LlmProvider): T = try {
        jsonMapper.readValue(content, type)
    } catch (exception: RuntimeException) {
        throw InvalidLlmResponseException(provider, exception)
    }
}
