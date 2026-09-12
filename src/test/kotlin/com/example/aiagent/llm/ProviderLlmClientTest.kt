package com.example.aiagent.llm

import com.example.aiagent.agent.ChatMessage
import com.example.aiagent.agent.Role
import com.example.aiagent.config.LlmProperties
import com.example.aiagent.config.ProviderProperties
import com.example.aiagent.llm.openai.OpenAiLlmClient
import com.example.aiagent.llm.openrouter.OpenRouterLlmClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Flow

class ProviderLlmClientTest {
    private val httpClient = mockk<HttpClient>()
    private val jsonMapper = jacksonObjectMapper()
    private val properties = LlmProperties(
        openai = ProviderProperties(
            apiKey = "openai-test-key",
            baseUrl = URI.create("https://openai.example"),
            defaultModel = "gpt-default",
        ),
        openrouter = ProviderProperties(
            apiKey = "openrouter-test-key",
            baseUrl = URI.create("https://openrouter.example/api/v1"),
            defaultModel = "openai/router-default",
        ),
    )
    private val request = LlmRequest(
        messages = listOf(
            ChatMessage(Role.SYSTEM, "System"),
            ChatMessage(Role.USER, "Привет"),
        ),
        model = "chosen-model",
    )

    @Test
    fun `OpenAI sends its key endpoint model and messages and maps response`() {
        val httpRequest = slot<HttpRequest>()
        respond(httpRequest, successResponse("actual-openai-model"))
        val client = OpenAiLlmClient(properties, jsonMapper, httpClient)

        val result = client.chat(request)

        assertEquals(URI.create("https://openai.example/v1/chat/completions"), httpRequest.captured.uri())
        assertEquals("Bearer openai-test-key", httpRequest.captured.headers().firstValue("Authorization").orElseThrow())
        assertRequestBody(httpRequest.captured, "chosen-model")
        assertEquals("Ответ", result.content)
        assertEquals("actual-openai-model", result.model)
        assertEquals(12, result.inputTokens)
        assertEquals(7, result.outputTokens)
        assertEquals(19, result.totalTokens)
    }

    @Test
    fun `OpenRouter sends its own key endpoint model and messages and maps response`() {
        val httpRequest = slot<HttpRequest>()
        respond(httpRequest, successResponse("anthropic/actual-model"))
        val client = OpenRouterLlmClient(properties, jsonMapper, httpClient)
        val routerRequest = request.copy(model = "anthropic/claude-test")

        val result = client.chat(routerRequest)

        assertEquals(
            URI.create("https://openrouter.example/api/v1/chat/completions"),
            httpRequest.captured.uri(),
        )
        assertEquals(
            "Bearer openrouter-test-key",
            httpRequest.captured.headers().firstValue("Authorization").orElseThrow(),
        )
        assertRequestBody(httpRequest.captured, "anthropic/claude-test")
        assertEquals("anthropic/actual-model", result.model)
        assertEquals(19, result.totalTokens)
    }

    @Test
    fun `missing OpenRouter key is reported with provider and skips network`() {
        val client = OpenRouterLlmClient(
            properties.copy(openrouter = properties.openrouter.copy(apiKey = "  ")),
            jsonMapper,
            httpClient,
        )

        val error = assertThrows(MissingApiKeyException::class.java) {
            client.chat(request.copy(model = "openai/gpt-test"))
        }

        assertEquals(LlmProvider.OPENROUTER, error.provider)
        verify(exactly = 0) {
            httpClient.send(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>())
        }
    }

    @Test
    fun `invalid OpenRouter model id is rejected before network`() {
        val client = OpenRouterLlmClient(properties, jsonMapper, httpClient)

        val error = assertThrows(InvalidLlmModelException::class.java) {
            client.chat(request.copy(model = "model-without-provider"))
        }

        assertEquals(LlmProvider.OPENROUTER, error.provider)
        verify(exactly = 0) {
            httpClient.send(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>())
        }
    }

    @Test
    fun `missing usage is accepted`() {
        val response = """{"model":"actual-model","choices":[{"message":{"role":"assistant","content":"Ответ"}}]}"""
        respond(slot(), response)
        val client = OpenAiLlmClient(properties, jsonMapper, httpClient)

        val result = client.chat(request)

        assertNull(result.inputTokens)
        assertNull(result.outputTokens)
        assertNull(result.totalTokens)
    }

    @Test
    fun `authentication error retains selected provider`() {
        respond(slot(), "{}", status = 401)
        val client = OpenRouterLlmClient(properties, jsonMapper, httpClient)

        val error = assertThrows(LlmAuthenticationException::class.java) {
            client.chat(request.copy(model = "openai/gpt-test"))
        }

        assertEquals(LlmProvider.OPENROUTER, error.provider)
    }

    @Test
    fun `rate limit response is mapped`() {
        respond(slot(), "{}", status = 429)
        val client = OpenAiLlmClient(properties, jsonMapper, httpClient)

        assertThrows(LlmRateLimitException::class.java) {
            client.chat(request)
        }
    }

    @Test
    fun `provider server response is mapped`() {
        respond(slot(), "{}", status = 503)
        val client = OpenAiLlmClient(properties, jsonMapper, httpClient)

        val error = assertThrows(LlmServerException::class.java) {
            client.chat(request)
        }

        assertEquals(503, error.statusCode)
        assertEquals(LlmProvider.OPENAI, error.provider)
    }

    @Test
    fun `timeout is mapped with provider`() {
        every {
            httpClient.send(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>())
        } throws HttpTimeoutException("timeout")
        val client = OpenAiLlmClient(properties, jsonMapper, httpClient)

        val error = assertThrows(LlmTimeoutException::class.java) {
            client.chat(request)
        }

        assertEquals(LlmProvider.OPENAI, error.provider)
    }

    @Test
    fun `network failure is mapped with provider`() {
        every {
            httpClient.send(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>())
        } throws IOException("offline")
        val client = OpenAiLlmClient(properties, jsonMapper, httpClient)

        val error = assertThrows(LlmNetworkException::class.java) {
            client.chat(request)
        }

        assertEquals(LlmProvider.OPENAI, error.provider)
    }

    @Test
    fun `malformed successful response is rejected`() {
        respond(slot(), "not-json")
        val client = OpenAiLlmClient(properties, jsonMapper, httpClient)

        assertThrows(InvalidLlmResponseException::class.java) {
            client.chat(request)
        }
    }

    private fun respond(
        requestSlot: io.mockk.CapturingSlot<HttpRequest>,
        body: String,
        status: Int = 200,
    ) {
        val response = mockk<HttpResponse<String>>()
        every { response.statusCode() } returns status
        every { response.body() } returns body
        every {
            httpClient.send(capture(requestSlot), any<HttpResponse.BodyHandler<String>>())
        } returns response
    }

    private fun assertRequestBody(httpRequest: HttpRequest, expectedModel: String) {
        val body = readBody(httpRequest)
        val json = jsonMapper.readTree(body)

        assertEquals(expectedModel, json["model"].stringValue())
        assertEquals("system", json["messages"][0]["role"].stringValue())
        assertEquals("System", json["messages"][0]["content"].stringValue())
        assertEquals("user", json["messages"][1]["role"].stringValue())
        assertEquals("Привет", json["messages"][1]["content"].stringValue())
    }

    private fun readBody(httpRequest: HttpRequest): String {
        val output = ByteArrayOutputStream()
        val completed = CompletableFuture<Unit>()
        httpRequest.bodyPublisher().orElseThrow().subscribe(object : Flow.Subscriber<ByteBuffer> {
            override fun onSubscribe(subscription: Flow.Subscription) {
                subscription.request(Long.MAX_VALUE)
            }

            override fun onNext(item: ByteBuffer) {
                val bytes = ByteArray(item.remaining())
                item.get(bytes)
                output.write(bytes)
            }

            override fun onError(throwable: Throwable) {
                completed.completeExceptionally(throwable)
            }

            override fun onComplete() {
                completed.complete(Unit)
            }
        })
        completed.join()
        return output.toString(StandardCharsets.UTF_8)
    }

    private fun successResponse(model: String): String =
        """
        {
          "model": "$model",
          "choices": [{"message": {"role": "assistant", "content": "  Ответ  "}}],
          "usage": {"prompt_tokens": 12, "completion_tokens": 7, "total_tokens": 19}
        }
        """.trimIndent()
}
