package com.example.aiagent.context.facts

import com.example.aiagent.agent.ChatMessage
import com.example.aiagent.agent.Role
import com.example.aiagent.config.ContextStrategiesProperties
import com.example.aiagent.llm.InvalidLlmResponseException
import com.example.aiagent.llm.LlmClientResolver
import com.example.aiagent.llm.LlmRequest
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
class LlmFactsExtractor(
    private val llmClientResolver: LlmClientResolver,
    private val properties: ContextStrategiesProperties,
    private val jsonMapper: ObjectMapper,
) : FactsExtractor {
    override fun extract(
        existingFacts: List<MemoryFact>,
        userMessage: ChatMessage,
    ): FactsUpdate {
        require(userMessage.role == Role.USER) { "Facts can only be extracted from a user message" }
        val extractorProperties = properties.stickyFacts.extractor
        val response = llmClientResolver.resolve(extractorProperties.provider).chat(
            LlmRequest(
                model = extractorProperties.model.trim(),
                messages = listOf(
                    ChatMessage(Role.SYSTEM, extractorProperties.systemPrompt.trim()),
                    ChatMessage(Role.USER, buildExtractionRequest(existingFacts, userMessage)),
                ),
            ),
        )
        val parsed = try {
            jsonMapper.readValue(response.content, FactsUpdateResponse::class.java)
        } catch (exception: RuntimeException) {
            throw InvalidLlmResponseException(extractorProperties.provider, exception)
        }
        return FactsUpdate(
            upsert = parsed.upsert.map { fact -> MemoryFact(fact.key.trim(), fact.value.trim()) },
            deleteKeys = parsed.deleteKeys.map(String::trim).filter(String::isNotEmpty),
        )
    }

    private fun buildExtractionRequest(
        existingFacts: List<MemoryFact>,
        userMessage: ChatMessage,
    ): String = buildString {
        appendLine("Existing persistent facts:")
        if (existingFacts.isEmpty()) {
            appendLine("(none)")
        } else {
            existingFacts.forEach { fact -> appendLine("${fact.key} = ${fact.value}") }
        }
        appendLine()
        appendLine("New user message:")
        appendLine(userMessage.content)
        appendLine()
        append("Return only JSON: {\"upsert\":[{\"key\":\"...\",\"value\":\"...\"}],\"delete\":[\"key\"]}.")
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class FactsUpdateResponse(
    val upsert: List<ExtractedFact> = emptyList(),
    @field:JsonProperty("delete")
    val deleteKeys: List<String> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class ExtractedFact(
    val key: String,
    val value: String,
)
