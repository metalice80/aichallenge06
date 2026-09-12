package com.example.aiagent.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI
import java.time.Duration

@ConfigurationProperties("openai")
data class OpenAiProperties(
    val apiKey: String = "",
    val model: String = "gpt-4.1-mini",
    val baseUrl: URI = URI.create("https://api.openai.com"),
    val connectTimeout: Duration = Duration.ofSeconds(10),
    val requestTimeout: Duration = Duration.ofSeconds(60),
    val systemPrompt: String = """
        Ты полезный AI-ассистент.
        Отвечай понятно, точно и по существу.
        Учитывай предыдущие сообщения пользователя в текущем диалоге.
    """.trimIndent(),
)
