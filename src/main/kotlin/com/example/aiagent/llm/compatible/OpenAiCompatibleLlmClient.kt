package com.example.aiagent.llm.compatible

import com.example.aiagent.agent.Role
import com.example.aiagent.llm.InvalidLlmModelException
import com.example.aiagent.llm.InvalidLlmResponseException
import com.example.aiagent.llm.LlmAuthenticationException
import com.example.aiagent.llm.LlmClient
import com.example.aiagent.llm.LlmNetworkException
import com.example.aiagent.llm.LlmProvider
import com.example.aiagent.llm.LlmRateLimitException
import com.example.aiagent.llm.LlmRequest
import com.example.aiagent.llm.LlmRequestException
import com.example.aiagent.llm.LlmResponse
import com.example.aiagent.llm.LlmServerException
import com.example.aiagent.llm.LlmTimeoutException
import com.example.aiagent.llm.MissingApiKeyException
import com.example.aiagent.llm.TokenUsage
import com.example.aiagent.llm.compatible.dto.ChatCompletionsRequest
import com.example.aiagent.llm.compatible.dto.ChatCompletionsResponse
import com.example.aiagent.llm.compatible.dto.ChatCompletionsMessage
import com.example.aiagent.llm.compatible.dto.Plugin
import tools.jackson.databind.ObjectMapper
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration

abstract class OpenAiCompatibleLlmClient(
    final override val provider: LlmProvider,
    final override val defaultModel: String,
    private val apiKey: String,
    private val chatCompletionsUri: URI,
    private val requestTimeout: Duration,
    private val jsonMapper: ObjectMapper,
    private val httpClient: HttpClient,
) : LlmClient {

    override fun chat(request: LlmRequest): LlmResponse {
        if (apiKey.isBlank()) {
            throw MissingApiKeyException(provider)
        }
        validateModel(request.model)

        val body = try {
            jsonMapper.writeValueAsString(
                ChatCompletionsRequest(
                    model = request.model,
                    messages = request.messages.map { message ->
                        ChatCompletionsMessage(
                            role = message.role.toApiRole(),
                            content = message.content,
                        )
                    },
                    plugins = listOf(Plugin("context-compression", false)) 
                ),
            )
        } catch (exception: RuntimeException) {
            throw LlmRequestException(provider, cause = exception)
        }

        val httpRequest = HttpRequest.newBuilder()
            .uri(chatCompletionsUri)
            .timeout(requestTimeout)
            .header("Authorization", "Bearer ${apiKey.trim()}")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response = try {
            httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
        } catch (exception: HttpTimeoutException) {
            throw LlmTimeoutException(provider, exception)
        } catch (exception: IOException) {
            throw LlmNetworkException(provider, exception)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw LlmNetworkException(provider, exception)
        }

        when (response.statusCode()) {
            400, 404 -> throw InvalidLlmModelException(provider)
            401, 403 -> throw LlmAuthenticationException(provider)
            429 -> throw LlmRateLimitException(provider)
            in 500..599 -> throw LlmServerException(provider, response.statusCode())
            !in 200..299 -> throw LlmRequestException(provider, response.statusCode())
        }

        return parseResponse(response.body())
    }

    protected open fun validateModel(model: String) {
        if (model.isBlank()) {
            throw InvalidLlmModelException(provider)
        }
    }

    private fun parseResponse(body: String): LlmResponse {
        val response = try {
            jsonMapper.readValue(body, ChatCompletionsResponse::class.java)
        } catch (exception: RuntimeException) {
            throw InvalidLlmResponseException(provider, exception)
        }

        val content = response.choices.firstOrNull()?.message?.content?.trim()
        val model = response.model?.trim()
        if (content.isNullOrEmpty() || model.isNullOrEmpty()) {
            throw InvalidLlmResponseException(provider)
        }

        return LlmResponse(
            content = content,
            model = model,
            usage = TokenUsage.fromProvider(
                inputTokens = response.usage?.promptTokens,
                outputTokens = response.usage?.completionTokens,
                totalTokens = response.usage?.totalTokens,
            ),
        )
    }

    private fun Role.toApiRole(): String = name.lowercase()

    companion object {
        fun createHttpClient(connectTimeout: Duration): HttpClient =
            HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build()
    }
}
