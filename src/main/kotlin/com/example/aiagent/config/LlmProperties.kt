package com.example.aiagent.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI
import java.time.Duration

@ConfigurationProperties("llm")
data class LlmProperties(
    val openai: ProviderProperties = ProviderProperties(
        baseUrl = URI.create("https://api.openai.com"),
        defaultModel = "gpt-4.1-mini",
    ),
    val openrouter: ProviderProperties = ProviderProperties(
        baseUrl = URI.create("https://openrouter.ai/api/v1"),
        defaultModel = "openai/gpt-4o-mini",
    ),
    val connectTimeout: Duration = Duration.ofSeconds(10),
    val requestTimeout: Duration = Duration.ofSeconds(60),
    val systemPrompt: String = """
        Ты полезный AI-ассистент.
        Отвечай понятно, точно и по существу.
        Учитывай предыдущие сообщения пользователя в текущем диалоге.
    """.trimIndent(),
)

data class ProviderProperties(
    val apiKey: String = "",
    val baseUrl: URI,
    val defaultModel: String,
)
