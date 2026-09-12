package com.example.aiagent.llm.openrouter

import com.example.aiagent.config.LlmProperties
import com.example.aiagent.llm.InvalidLlmModelException
import com.example.aiagent.llm.LlmProvider
import com.example.aiagent.llm.compatible.OpenAiCompatibleLlmClient
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
    @Autowired
    constructor(properties: LlmProperties, jsonMapper: ObjectMapper) : this(
        properties,
        jsonMapper,
        createHttpClient(properties.connectTimeout),
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
