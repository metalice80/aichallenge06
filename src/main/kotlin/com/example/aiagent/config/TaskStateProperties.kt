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
            "Analyze work requested inside the current persisted Task stage before the main assistant response. " +
                "Classify requestedAction as PLAN, IMPLEMENT, VALIDATE, FINALIZE, STATUS, or NONE. " +
                "You may return suggestedEvent as a nonbinding UI hint, but it never changes stage and must never " +
                "be treated as applied. Creating or changing a plan is PLAN. Explicit approval of an already " +
                "prepared plan may suggest PLAN_APPROVED, while requested work is still checked against the current " +
                "persisted stage. Never assign a target stage or claim that a lifecycle transition occurred."
    }
}
