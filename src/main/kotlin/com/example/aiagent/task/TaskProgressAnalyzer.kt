package com.example.aiagent.task

import com.example.aiagent.agent.ChatMessage
import com.example.aiagent.agent.Role
import com.example.aiagent.config.TaskStateProperties
import com.example.aiagent.llm.LlmClientResolver
import com.example.aiagent.llm.LlmRequest
import com.example.aiagent.llm.StructuredOutputParser
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

interface TaskProgressAnalyzer {
    fun analyze(
        task: AgentTask,
        userMessage: ChatMessage,
    ): TaskProgressProposal
}

@Component
class LlmTaskProgressAnalyzer(
    private val llmClientResolver: LlmClientResolver,
    private val properties: TaskStateProperties,
    private val structuredOutputParser: StructuredOutputParser,
) : TaskProgressAnalyzer {
    override fun analyze(
        task: AgentTask,
        userMessage: ChatMessage,
    ): TaskProgressProposal {
        require(userMessage.role == Role.USER) { "Progress analyzer requires a USER message" }
        val analyzer = properties.analyzer
        val response = llmClientResolver.resolve(analyzer.provider).chat(
            LlmRequest(
                model = analyzer.model.trim(),
                messages = listOf(
                    ChatMessage(Role.SYSTEM, analyzer.systemPrompt.trim()),
                    ChatMessage(Role.USER, prompt(task, userMessage)),
                ),
            ),
        )
        val parsed = structuredOutputParser.parse(
            response.content,
            TaskProgressProposalResponse::class.java,
            analyzer.provider,
        )
        return TaskProgressProposal(
            currentStep = parsed.currentStep?.trim()?.takeIf(String::isNotEmpty),
            expectedActionType = parsed.expectedActionType,
            expectedActionDescription = parsed.expectedActionDescription?.trim()?.takeIf(String::isNotEmpty),
            proposedEvent = parsed.proposedEvent,
        )
    }

    private fun prompt(
        task: AgentTask,
        userMessage: ChatMessage,
    ): String = buildString {
        appendLine("Current Task State:")
        appendLine("Task: ${task.name}")
        appendLine("Stage: ${task.stage}")
        appendLine("Current step: ${task.currentStep}")
        appendLine("Expected action type: ${task.expectedActionType}")
        appendLine("Expected action description: ${task.expectedActionDescription ?: "(none)"}")
        appendLine("Paused: ${task.paused}")
        appendLine()
        appendLine("New user message:")
        appendLine(userMessage.content)
        appendLine()
        appendLine("Allowed transitions:")
        appendLine("PLANNING + PLAN_APPROVED -> EXECUTION")
        appendLine("EXECUTION + EXECUTION_COMPLETED -> VALIDATION")
        appendLine("VALIDATION + VALIDATION_PASSED -> DONE")
        appendLine("VALIDATION + VALIDATION_FAILED -> EXECUTION")
        appendLine()
        appendLine("Rules:")
        appendLine("- Propose an event only when the user message clearly implies that exact transition.")
        appendLine("- Explicit plan approval must propose PLAN_APPROVED while in PLANNING.")
        appendLine("- A concrete requested work item may update currentStep and expected action without changing stage.")
        appendLine("- Keep currentStep concrete and specific; never copy the stage name as the step.")
        appendLine("- Use null for every field that should remain unchanged.")
        appendLine("- Never propose an event that is invalid for the current stage.")
        append(
            "Return only JSON: {\"currentStep\":string|null," +
                "\"expectedActionType\":\"USER_INPUT|USER_CONFIRMATION|AGENT_ACTION|VALIDATION|NONE\"|null," +
                "\"expectedActionDescription\":string|null," +
                "\"proposedEvent\":\"PLAN_APPROVED|EXECUTION_COMPLETED|VALIDATION_PASSED|VALIDATION_FAILED\"|null}.",
        )
    }
}

@Component
class TaskStateCoordinator(
    private val properties: TaskStateProperties,
    private val analyzer: TaskProgressAnalyzer,
    private val stateService: TaskStateService,
) {
    private val logger = LoggerFactory.getLogger(TaskStateCoordinator::class.java)

    fun analyzeBeforeMainRequest(
        task: AgentTask,
        userMessage: ChatMessage,
    ): AgentTask {
        if (!properties.analyzer.enabled || task.paused || task.stage == TaskStage.DONE) return task
        return try {
            val proposal = analyzer.analyze(task, userMessage)
            stateService.applyProposal(task.id, proposal)
        } catch (exception: RuntimeException) {
            logger.warn("Task progress analyzer failed for Task {}", task.id, exception)
            task
        }
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class TaskProgressProposalResponse(
    val currentStep: String? = null,
    val expectedActionType: ExpectedActionType? = null,
    val expectedActionDescription: String? = null,
    val proposedEvent: TaskEvent? = null,
)
