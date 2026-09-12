package com.example.aiagent.llm.openai

import com.example.aiagent.agent.Role
import com.example.aiagent.config.OpenAiProperties
import com.example.aiagent.llm.InvalidLlmResponseException
import com.example.aiagent.llm.LlmAuthenticationException
import com.example.aiagent.llm.LlmClient
import com.example.aiagent.llm.LlmNetworkException
import com.example.aiagent.llm.LlmRateLimitException
import com.example.aiagent.llm.LlmRequest
import com.example.aiagent.llm.LlmRequestException
import com.example.aiagent.llm.LlmResponse
import com.example.aiagent.llm.LlmServerException
import com.example.aiagent.llm.LlmTimeoutException
import com.example.aiagent.llm.MissingApiKeyException
import com.example.aiagent.llm.openai.dto.OpenAiChatRequest
import com.example.aiagent.llm.openai.dto.OpenAiChatResponse
import com.example.aiagent.llm.openai.dto.OpenAiMessage
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException

@Component
class OpenAiClient internal constructor(
    private val properties: OpenAiProperties,
    private val jsonMapper: ObjectMapper,
    private val httpClient: HttpClient,
) : LlmClient {

    @Autowired
    constructor(properties: OpenAiProperties, jsonMapper: ObjectMapper) : this(
        properties,
        jsonMapper,
        HttpClient.newBuilder()
            .connectTimeout(properties.connectTimeout)
            .build(),
    )

    override fun chat(request: LlmRequest): LlmResponse {
        val apiKey = properties.apiKey.trim()
        if (apiKey.isEmpty()) {
            throw MissingApiKeyException()
        }

        val body = try {
            jsonMapper.writeValueAsString(
                OpenAiChatRequest(
                    model = properties.model,
                    messages = request.messages.map { message ->
                        OpenAiMessage(
                            role = message.role.toOpenAiRole(),
                            content = message.content,
                        )
                    },
                ),
            )
        } catch (exception: RuntimeException) {
            throw LlmRequestException(cause = exception)
        }

        val httpRequest = HttpRequest.newBuilder()
            .uri(chatCompletionsUri())
            .timeout(properties.requestTimeout)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response = try {
            httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
        } catch (exception: HttpTimeoutException) {
            throw LlmTimeoutException(exception)
        } catch (exception: IOException) {
            throw LlmNetworkException(exception)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw LlmNetworkException(exception)
        }

        when (response.statusCode()) {
            401 -> throw LlmAuthenticationException()
            429 -> throw LlmRateLimitException()
            in 500..599 -> throw LlmServerException(response.statusCode())
            !in 200..299 -> throw LlmRequestException(response.statusCode())
        }

        return parseResponse(response.body())
    }

    private fun parseResponse(body: String): LlmResponse {
        val response = try {
            jsonMapper.readValue(body, OpenAiChatResponse::class.java)
        } catch (exception: RuntimeException) {
            throw InvalidLlmResponseException(exception)
        }

        val content = response.choices.firstOrNull()?.message?.content?.trim()
        val model = response.model?.trim()
        if (content.isNullOrEmpty() || model.isNullOrEmpty()) {
            throw InvalidLlmResponseException()
        }

        return LlmResponse(
            content = content,
            model = model,
            inputTokens = response.usage?.promptTokens,
            outputTokens = response.usage?.completionTokens,
            totalTokens = response.usage?.totalTokens,
        )
    }

    private fun chatCompletionsUri(): URI =
        URI.create("${properties.baseUrl.toString().trimEnd('/')}/v1/chat/completions")

    private fun Role.toOpenAiRole(): String = name.lowercase()
}
