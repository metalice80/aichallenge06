package com.example.aiagent.llm.openrouter

import com.example.aiagent.config.LlmProperties
import com.example.aiagent.llm.InvalidLlmModelException
import com.example.aiagent.llm.LlmProvider
import com.example.aiagent.llm.LlmRequest
import com.example.aiagent.llm.compatible.OpenAiCompatibleLlmClient
import com.example.aiagent.llm.openrouter.dto.OpenRouterChatCompletionsMessage
import com.example.aiagent.llm.openrouter.dto.OpenRouterChatCompletionsRequest
import com.example.aiagent.llm.openrouter.dto.OpenRouterPlugin
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient

@Component
class OpenRouterLlmClient internal constructor(
    properties: LlmProperties,
    jsonMapper: ObjectMapper,
    httpClient: HttpClient,
) : OpenAiCompatibleLlmClient(
    provider = LlmProvider.OPENROUTER,
    defaultModel = properties.openrouter.defaultModel,
    apiKey = properties.openrouter.apiKey,
    chatCompletionsUri = URI.create(
        "${properties.openrouter.baseUrl.toString().trimEnd('/')}/chat/completions",
    ),
    requestTimeout = properties.requestTimeout,
    jsonMapper = jsonMapper,
    httpClient = httpClient,
) {
    private val configuredPlugins = properties.openrouter.plugins.map { plugin ->
        OpenRouterPlugin(
            id = plugin.id.trim(),
            enabled = plugin.enabled,
        )
    }

    @Autowired
    constructor(properties: LlmProperties, jsonMapper: ObjectMapper) : this(
        properties,
        jsonMapper,
        createHttpClient(properties.connectTimeout),
    )

    override fun createChatCompletionsRequest(request: LlmRequest): Any =
        OpenRouterChatCompletionsRequest(
            model = request.model,
            messages = request.messages.map { message ->
                OpenRouterChatCompletionsMessage(
                    role = message.role.name.lowercase(),
                    content = message.content,
                )
            },
            plugins = configuredPlugins.takeIf { it.isNotEmpty() },
        )

    override fun validateModel(model: String) {
        super.validateModel(model)
        val separator = model.indexOf('/')
        if (separator <= 0 || separator == model.lastIndex || model.any(Char::isWhitespace)) {
            throw InvalidLlmModelException(
                provider,
                "OpenRouter model id must use provider/model format",
            )
        }
    }
}
