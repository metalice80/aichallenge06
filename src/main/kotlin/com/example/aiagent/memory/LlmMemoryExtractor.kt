package com.example.aiagent.memory

import com.example.aiagent.agent.ChatMessage
import com.example.aiagent.agent.Role
import com.example.aiagent.config.MemoryProperties
import com.example.aiagent.llm.LlmClientResolver
import com.example.aiagent.llm.LlmRequest
import com.example.aiagent.llm.StructuredOutputParser
import com.example.aiagent.task.AgentTask
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.stereotype.Component

@Component
class LlmMemoryExtractor(
    private val llmClientResolver: LlmClientResolver,
    private val properties: MemoryProperties,
    private val structuredOutputParser: StructuredOutputParser,
) : MemoryExtractor {
    override fun extract(
        userMessage: ChatMessage,
        task: AgentTask,
        currentWorkingMemory: List<MemoryEntry>,
        currentLongTermMemory: List<MemoryEntry>,
    ): MemoryUpdate {
        require(userMessage.role == Role.USER) { "Memory can only be extracted from a user message" }
        val extractor = properties.extractor
        val response = llmClientResolver.resolve(extractor.provider).chat(
            LlmRequest(
                model = extractor.model.trim(),
                messages = listOf(
                    ChatMessage(Role.SYSTEM, extractor.systemPrompt.trim()),
                    ChatMessage(
                        Role.USER,
                        extractionRequest(task, currentWorkingMemory, currentLongTermMemory, userMessage),
                    ),
                ),
            ),
        )
        val parsed = structuredOutputParser.parse(
            response.content,
            MemoryUpdateResponse::class.java,
            extractor.provider,
        )
        return MemoryUpdate(
            working = parsed.working.toUpdate(),
            longTerm = parsed.longTerm.toUpdate(),
        )
    }

    private fun extractionRequest(
        task: AgentTask,
        working: List<MemoryEntry>,
        longTerm: List<MemoryEntry>,
        userMessage: ChatMessage,
    ): String = buildString {
        appendLine("Current Task: ${task.name} (id=${task.id})")
        appendLine()
        appendMemory("Existing WORKING memory", working)
        appendLine()
        appendMemory("Existing LONG_TERM memory", longTerm)
        appendLine()
        appendLine("New user message:")
        appendLine(userMessage.content)
        appendLine()
        append(
            "Return only JSON: " +
                "{\"working\":{\"upsert\":[{\"key\":\"...\",\"value\":\"...\"}],\"delete\":[\"key\"]}," +
                "\"longTerm\":{\"upsert\":[],\"delete\":[]}}.",
        )
    }

    private fun StringBuilder.appendMemory(title: String, entries: List<MemoryEntry>) {
        appendLine("$title:")
        if (entries.isEmpty()) {
            appendLine("(none)")
        } else {
            entries.forEach { entry -> appendLine("${entry.key} = ${entry.value}") }
        }
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class MemoryUpdateResponse(
    val working: MemoryLayerUpdateResponse = MemoryLayerUpdateResponse(),
    val longTerm: MemoryLayerUpdateResponse = MemoryLayerUpdateResponse(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class MemoryLayerUpdateResponse(
    val upsert: List<ExtractedMemoryEntry> = emptyList(),
    @field:JsonProperty("delete")
    val deleteKeys: List<String> = emptyList(),
) {
    fun toUpdate() = MemoryLayerUpdate(
        upsert = upsert.map { entry -> MemoryEntry(entry.key.trim(), entry.value.trim()) },
        delete = deleteKeys.map(String::trim).filter(String::isNotEmpty),
    )
}

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class ExtractedMemoryEntry(
    val key: String,
    val value: String,
)
