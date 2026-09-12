package com.example.aiagent.llm

import com.example.aiagent.agent.ChatMessage
import com.example.aiagent.agent.Role
import com.example.aiagent.config.LlmProperties
import com.example.aiagent.config.OpenRouterPluginProperties
import com.example.aiagent.config.OpenRouterProperties
import com.example.aiagent.llm.openrouter.OpenRouterLlmClient
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture

class OpenRouterLlmClientHttpIntegrationTest {
    @Test
    fun `configured plugins are present in the serialized OpenRouter HTTP request`() {
        val requestBody = CompletableFuture<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/chat/completions") { exchange ->
            requestBody.complete(String(exchange.requestBody.readAllBytes(), StandardCharsets.UTF_8))
            val response = """
                {
                  "model": "openai/gpt-test",
                  "choices": [{"message": {"role": "assistant", "content": "Ответ"}}],
                  "usage": {"prompt_tokens": 12, "completion_tokens": 7, "total_tokens": 19}
                }
            """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()

        try {
            val properties = LlmProperties(
                openrouter = OpenRouterProperties(
                    apiKey = "openrouter-test-key",
                    baseUrl = URI.create("http://127.0.0.1:${server.address.port}/api/v1"),
                    defaultModel = "openai/gpt-test",
                    plugins = listOf(
                        OpenRouterPluginProperties(id = "context-compression", enabled = false),
                        OpenRouterPluginProperties(id = "web", enabled = true),
                    ),
                ),
            )
            val client = OpenRouterLlmClient(
                properties,
                jacksonObjectMapper(),
                HttpClient.newHttpClient(),
            )

            val response = client.chat(
                LlmRequest(
                    model = "openai/gpt-test",
                    messages = listOf(
                        ChatMessage(Role.SYSTEM, "System"),
                        ChatMessage(Role.USER, "Question"),
                    ),
                ),
            )

            val json = jacksonObjectMapper().readTree(requestBody.join())
            assertEquals("openai/gpt-test", json["model"].stringValue())
            assertEquals(2, json["plugins"].size())
            assertEquals("context-compression", json["plugins"][0]["id"].stringValue())
            assertEquals(false, json["plugins"][0]["enabled"].booleanValue())
            assertEquals("web", json["plugins"][1]["id"].stringValue())
            assertEquals(true, json["plugins"][1]["enabled"].booleanValue())
            assertEquals(TokenUsage(12, 7, 19), response.usage)
        } finally {
            server.stop(0)
        }
    }
}
