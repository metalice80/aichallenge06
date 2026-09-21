package com.example.aiagent.config

import com.example.aiagent.llm.LlmProvider
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@Validated
@ConfigurationProperties("task.state")
data class TaskStateProperties(
    @field:Valid
    val analyzer: TaskProgressAnalyzerProperties = TaskProgressAnalyzerProperties(),
)

data class TaskProgressAnalyzerProperties(
    val enabled: Boolean = true,
    val provider: LlmProvider = LlmProvider.OPENAI,
    @field:NotBlank
    val model: String = "gpt-4o-mini",
    @field:NotBlank
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
) {
    companion object {
        const val DEFAULT_SYSTEM_PROMPT =
            "Analyze the active Task State and the new user message before the main assistant response. " +
                "Return a structured proposal only. Explicit approval of a plan must propose PLAN_APPROVED " +
                "while PLANNING. Concrete work requests may update currentStep and expectedAction without " +
                "changing stage. Never invent completion and never propose an invalid transition."
    }
}
