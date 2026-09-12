package com.example.aiagent.config

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.net.URI
import java.time.Duration

@Validated
@ConfigurationProperties("llm")
data class LlmProperties(
    val openai: ProviderProperties = ProviderProperties(
        baseUrl = URI.create("https://api.openai.com"),
        defaultModel = "gpt-4.1-mini",
    ),
    @field:Valid
    val openrouter: OpenRouterProperties = OpenRouterProperties(
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

data class OpenRouterProperties(
    val apiKey: String = "",
    val baseUrl: URI,
    val defaultModel: String,
    @field:Valid
    val plugins: List<OpenRouterPluginProperties> = emptyList(),
)

data class OpenRouterPluginProperties(
    @field:NotBlank
    val id: String = "",
    val enabled: Boolean = true,
)
