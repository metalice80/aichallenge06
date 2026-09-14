package com.example.aiagent.config

import com.example.aiagent.llm.LlmProvider
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@Validated
@ConfigurationProperties("context.strategies")
data class ContextStrategiesProperties(
    @field:Valid
    val slidingWindow: SlidingWindowStrategyProperties = SlidingWindowStrategyProperties(),
    @field:Valid
    val stickyFacts: StickyFactsStrategyProperties = StickyFactsStrategyProperties(),
)

data class SlidingWindowStrategyProperties(
    @field:Min(1)
    val size: Int = 10,
)

data class StickyFactsStrategyProperties(
    @field:Min(1)
    val windowSize: Int = 10,
    @field:Valid
    val extractor: FactsExtractorProperties = FactsExtractorProperties(),
)

data class FactsExtractorProperties(
    val provider: LlmProvider = LlmProvider.OPENAI,
    @field:NotBlank
    val model: String = "gpt-5-nano",
    @field:NotBlank
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
) {
    companion object {
        const val DEFAULT_SYSTEM_PROMPT =
            "Проанализируй новое сообщение пользователя. " +
                "Выделяй только долгоживущие факты: цели, ограничения, предпочтения, " +
                "решения, договорённости и важные параметры. Учитывай существующие facts. " +
                "Не добавляй факты, которых пользователь не сообщал. " +
                "Верни JSON с массивом upsert и массивом ключей delete."
    }
}
