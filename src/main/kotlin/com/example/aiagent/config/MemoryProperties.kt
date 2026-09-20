package com.example.aiagent.config

import com.example.aiagent.llm.LlmProvider
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@Validated
@ConfigurationProperties("memory")
data class MemoryProperties(
    val enabled: Boolean = true,
    @field:Valid
    val extractor: MemoryExtractorProperties = MemoryExtractorProperties(),
)

data class MemoryExtractorProperties(
    val provider: LlmProvider = LlmProvider.OPENAI,
    @field:NotBlank
    val model: String = "gpt-4o-mini",
    @field:NotBlank
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
) {
    companion object {
        const val DEFAULT_SYSTEM_PROMPT =
            "Проанализируй новое сообщение пользователя. " +
                "В WORKING сохраняй только цели, ограничения, параметры, решения и технологии текущей Task. " +
                "В LONG_TERM сохраняй только устойчивые предпочтения, профиль и знания, полезные между Tasks. " +
                "Не сохраняй приветствия, случайные фразы, секреты или временные детали. " +
                "Не придумывай информацию. Обновляй существующий key, если значение изменилось, " +
                "и удаляй key только при явной отмене пользователем. " +
                "Верни только JSON с working и longTerm, каждый с upsert и delete."
    }
}
