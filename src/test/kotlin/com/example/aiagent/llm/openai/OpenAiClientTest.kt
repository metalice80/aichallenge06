package com.example.aiagent.llm.openai

import com.example.aiagent.agent.ChatMessage
import com.example.aiagent.agent.Role
import com.example.aiagent.config.OpenAiProperties
import com.example.aiagent.llm.InvalidLlmResponseException
import com.example.aiagent.llm.LlmAuthenticationException
import com.example.aiagent.llm.LlmNetworkException
import com.example.aiagent.llm.LlmRateLimitException
import com.example.aiagent.llm.LlmRequest
import com.example.aiagent.llm.LlmServerException
import com.example.aiagent.llm.LlmTimeoutException
import com.example.aiagent.llm.MissingApiKeyException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException

class OpenAiClientTest {
    private val httpClient = mockk<HttpClient>()
    private val properties = OpenAiProperties(
        apiKey = "secret-test-key",
        model = "configured-model",
        baseUrl = URI.create("https://example.test"),
    )
    private val client = OpenAiClient(properties, jacksonObjectMapper(), httpClient)
    private val request = LlmRequest(listOf(ChatMessage(Role.USER, "Привет")))

    @Test
    fun `successful response is converted to internal model`() {
        respond(
            200,
            """
                {
                  "model": "actual-model",
                  "choices": [{"message": {"role": "assistant", "content": "  Ответ  "}}],
                  "usage": {"prompt_tokens": 12, "completion_tokens": 7, "total_tokens": 19}
                }
            """.trimIndent(),
        )

        val result = client.chat(request)

        assertEquals("Ответ", result.content)
        assertEquals("actual-model", result.model)
        assertEquals(12, result.inputTokens)
        assertEquals(7, result.outputTokens)
        assertEquals(19, result.totalTokens)
    }

    @Test
    fun `missing usage is accepted`() {
        respond(
            200,
            """{"model":"actual-model","choices":[{"message":{"role":"assistant","content":"Ответ"}}]}""",
        )

        val result = client.chat(request)

        assertNull(result.inputTokens)
        assertNull(result.outputTokens)
        assertNull(result.totalTokens)
    }

    @Test
    fun `missing API key fails before network call`() {
        val unconfigured = OpenAiClient(
            properties.copy(apiKey = "  "),
            jacksonObjectMapper(),
            httpClient,
        )

        assertThrows(MissingApiKeyException::class.java) {
            unconfigured.chat(request)
        }
        verify(exactly = 0) {
            httpClient.send(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>())
        }
    }

    @Test
    fun `unauthorized response is mapped to authentication error`() {
        respond(401, "{}")

        assertThrows(LlmAuthenticationException::class.java) {
            client.chat(request)
        }
    }

    @Test
    fun `rate limit response is mapped to rate limit error`() {
        respond(429, "{}")

        assertThrows(LlmRateLimitException::class.java) {
            client.chat(request)
        }
    }

    @Test
    fun `server response is mapped to server error`() {
        respond(503, "{}")

        val error = assertThrows(LlmServerException::class.java) {
            client.chat(request)
        }
        assertEquals(503, error.statusCode)
    }

    @Test
    fun `timeout is mapped to timeout error`() {
        every {
            httpClient.send(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>())
        } throws HttpTimeoutException("timeout")

        assertThrows(LlmTimeoutException::class.java) {
            client.chat(request)
        }
    }

    @Test
    fun `network failure is mapped to network error`() {
        every {
            httpClient.send(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>())
        } throws IOException("offline")

        assertThrows(LlmNetworkException::class.java) {
            client.chat(request)
        }
    }

    @Test
    fun `malformed successful response is rejected`() {
        respond(200, "not-json")

        assertThrows(InvalidLlmResponseException::class.java) {
            client.chat(request)
        }
    }

    private fun respond(status: Int, body: String) {
        val response = mockk<HttpResponse<String>>()
        every { response.statusCode() } returns status
        every { response.body() } returns body
        every {
            httpClient.send(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>())
        } returns response
    }
}
