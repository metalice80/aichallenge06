package com.example.aiagent.config

import com.example.aiagent.llm.LlmProvider
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@Validated
@ConfigurationProperties("context.compression")
data class ContextCompressionProperties(
    val enabled: Boolean = true,
    @field:Min(1)
    val summarizeAfterMessages: Int = 20,
    @field:Min(1)
    val summarizeEveryMessages: Int = 10,
    val provider: LlmProvider = LlmProvider.OPENAI,
    @field:NotBlank
    val model: String = "gpt-5-nano",
    @field:NotBlank
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
) {
    @AssertTrue(message = "summarize-after-messages must be greater than summarize-every-messages")
    fun hasValidThresholdOrder(): Boolean = summarizeAfterMessages > summarizeEveryMessages

    companion object {
        const val DEFAULT_SYSTEM_PROMPT =
            "Сожми предыдущий диалог в компактное самостоятельное summary. " +
                "Сохрани важные факты, решения, предпочтения пользователя, " +
                "технический контекст, ограничения и открытые вопросы. " +
                "Не добавляй информацию, которой не было в диалоге."
    }
}
