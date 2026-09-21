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

    fun <T> parseExactObject(
        content: String,
        type: Class<T>,
        provider: LlmProvider,
        fields: Set<String>,
        arrayObjectFields: Map<String, Set<String>> = emptyMap(),
    ): T = try {
        val root = jsonMapper.readValue(content, Map::class.java)
        val actualFields = root.keys.map { it.toString() }.toSet()
        require(actualFields == fields) {
            "Expected fields $fields but received $actualFields"
        }
        arrayObjectFields.forEach { (arrayField, objectFields) ->
            val items = root[arrayField] as? List<*>
                ?: throw IllegalArgumentException("$arrayField must be an array")
            items.forEach { item ->
                val objectValue = item as? Map<*, *>
                    ?: throw IllegalArgumentException("$arrayField entries must be objects")
                val actualObjectFields = objectValue.keys.map { it.toString() }.toSet()
                require(actualObjectFields == objectFields) {
                    "Expected $arrayField fields $objectFields but received $actualObjectFields"
                }
            }
        }
        jsonMapper.readValue(content, type)
    } catch (exception: RuntimeException) {
        throw InvalidLlmResponseException(provider, exception)
    }
}
