package com.example.aiagent.llm.openai

import com.example.aiagent.config.LlmProperties
import com.example.aiagent.llm.LlmProvider
import com.example.aiagent.llm.compatible.OpenAiCompatibleLlmClient
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient

@Component
class OpenAiLlmClient internal constructor(
    properties: LlmProperties,
    jsonMapper: ObjectMapper,
    httpClient: HttpClient,
) : OpenAiCompatibleLlmClient(
    provider = LlmProvider.OPENAI,
    defaultModel = properties.openai.defaultModel,
    apiKey = properties.openai.apiKey,
    chatCompletionsUri = URI.create(
        "${properties.openai.baseUrl.toString().trimEnd('/')}/v1/chat/completions",
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
}
