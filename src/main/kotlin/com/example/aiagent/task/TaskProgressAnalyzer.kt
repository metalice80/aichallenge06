package com.example.aiagent.task

import com.example.aiagent.agent.ChatMessage
import com.example.aiagent.agent.LlmRequestPurpose
import com.example.aiagent.agent.LlmRequestUsage
import com.example.aiagent.agent.Role
import com.example.aiagent.config.TaskStateProperties
import com.example.aiagent.llm.LlmClientResolver
import com.example.aiagent.llm.LlmProvider
import com.example.aiagent.llm.LlmRequest
import com.example.aiagent.llm.StructuredOutputParser
import com.example.aiagent.llm.TokenUsage
import com.example.aiagent.persistence.ConversationRepository
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

interface TaskProgressAnalyzer {
    fun analyze(
        task: AgentTask,
        userMessage: ChatMessage,
    ): TaskProgressAnalysis
}

data class TaskProgressAnalysis(
    val proposal: TaskProgressProposal,
    val provider: LlmProvider,
    val model: String,
    val usage: TokenUsage,
    val responseTimeMs: Long,
)

class TaskProgressAnalyzerException(
    cause: RuntimeException,
    val provider: LlmProvider,
    val model: String,
    val usage: TokenUsage,
    val responseTimeMs: Long,
) : RuntimeException("Task progress analyzer response could not be parsed", cause)

private fun TaskProgressProposal.normalizePlanningSemantics(task: AgentTask): TaskProgressProposal {
    if (task.stage != TaskStage.PLANNING || requestedAction != TaskActionType.PLAN) {
        return this
    }
    return copy(
        expectedActionType = ExpectedActionType.AGENT_ACTION,
        expectedActionDescription = "Сформировать или обновить план реализации",
        suggestedEvent = null,
    )
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
    ): TaskProgressAnalysis {
        require(userMessage.role == Role.USER) { "Progress analyzer requires a USER message" }
        val analyzer = properties.analyzer
        val startedAt = System.nanoTime()
        val response = llmClientResolver.resolve(analyzer.provider).chat(
            LlmRequest(
                model = analyzer.model.trim(),
                messages = listOf(
                    ChatMessage(Role.SYSTEM, analyzer.systemPrompt.trim()),
                    ChatMessage(Role.USER, prompt(task, userMessage)),
                ),
            ),
        )
        val responseTimeMs = (System.nanoTime() - startedAt) / NANOS_PER_MILLISECOND
        val parsed = try {
            structuredOutputParser.parse(
                response.content,
                TaskProgressProposalResponse::class.java,
                analyzer.provider,
            )
        } catch (exception: RuntimeException) {
            throw TaskProgressAnalyzerException(
                exception,
                analyzer.provider,
                response.model,
                response.usage,
                responseTimeMs,
            )
        }
        val proposal = TaskProgressProposal(
            currentStep = parsed.currentStep?.trim()?.takeIf(String::isNotEmpty),
            expectedActionType = parsed.expectedActionType,
            expectedActionDescription = parsed.expectedActionDescription?.trim()?.takeIf(String::isNotEmpty),
            suggestedEvent = parsed.suggestedEvent,
            requestedAction = parsed.requestedAction,
            reason = parsed.reason?.trim()?.takeIf(String::isNotEmpty),
        ).normalizePlanningSemantics(task)
        return TaskProgressAnalysis(
            proposal = proposal,
            provider = analyzer.provider,
            model = response.model,
            usage = response.usage,
            responseTimeMs = responseTimeMs,
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
        appendLine("Version: ${task.version}")
        appendLine()
        appendLine("New user message:")
        appendLine(userMessage.content)
        appendLine()
        appendLine("Reference transition table (suggestions only; never apply events):")
        appendLine("PLANNING + PLAN_APPROVED -> EXECUTION")
        appendLine("EXECUTION + EXECUTION_COMPLETED -> VALIDATION")
        appendLine("VALIDATION + VALIDATION_PASSED -> DONE")
        appendLine("VALIDATION + VALIDATION_FAILED -> EXECUTION")
        appendLine()
        appendLine("Rules:")
        appendLine("- TaskStage.PLANNING, TaskActionType.PLAN, and TaskEvent.PLAN_APPROVED are different concepts.")
        appendLine("- Classify creating or changing a plan as PLAN. For PLAN in PLANNING, suggestedEvent must be null.")
        appendLine("- For PLAN, use expectedActionType AGENT_ACTION because the main assistant must produce the plan now.")
        appendLine("- PLAN_APPROVED may be suggested only when the user explicitly approves an already prepared plan.")
        appendLine("- suggestedEvent is a nonbinding UI hint: never assume that it changes Task State.")
        appendLine("- Classify requestedAction using the current persisted stage; do not use a suggested stage.")
        appendLine("- Propose at most one suggestion and only when the message clearly confirms that fact.")
        appendLine("- Classify requestedAction as PLAN, IMPLEMENT, VALIDATE, FINALIZE, STATUS, or NONE.")
        appendLine("- Requests to write or change production artifacts are IMPLEMENT, not PLAN.")
        appendLine("- Requests to declare the Task complete are FINALIZE.")
        appendLine("- A concrete allowed work item may update currentStep and expected action.")
        appendLine("- Keep currentStep concrete; use null for every progress field that should remain unchanged.")
        appendLine("- Never claim that suggestedEvent was applied; only explicit UI/API events change stage.")
        append(
            "Return only JSON: {\"currentStep\":string|null," +
                "\"expectedActionType\":\"USER_INPUT|USER_CONFIRMATION|AGENT_ACTION|VALIDATION|NONE\"|null," +
                "\"expectedActionDescription\":string|null," +
                "\"suggestedEvent\":\"PLAN_APPROVED|EXECUTION_COMPLETED|VALIDATION_PASSED|VALIDATION_FAILED\"|null," +
                "\"requestedAction\":\"PLAN|IMPLEMENT|VALIDATE|FINALIZE|STATUS|NONE\"," +
                "\"reason\":string|null}.",
        )
    }

    companion object {
        private const val NANOS_PER_MILLISECOND = 1_000_000
    }
}

@Component
class TaskStateCoordinator(
    private val properties: TaskStateProperties,
    private val analyzer: TaskProgressAnalyzer,
    private val stateService: TaskStateService,
    private val lifecycleGuard: TaskLifecycleGuard,
    private val conversationRepository: ConversationRepository,
) {
    private val logger = LoggerFactory.getLogger(TaskStateCoordinator::class.java)

    fun analyzeBeforeMainRequest(
        task: AgentTask,
        userMessage: ChatMessage,
    ): TaskCoordinationResult {
        if (task.paused) {
            return TaskCoordinationResult.Blocked(task, lifecycleGuard.pausedMessage(task))
        }
        if (!properties.analyzer.enabled) {
            return TaskCoordinationResult.Blocked(task, lifecycleGuard.analyzerFailureMessage(task))
        }

        val analysis = try {
            analyzer.analyze(task, userMessage).also { recordUsage(task.id, it) }
        } catch (exception: TaskProgressAnalyzerException) {
            recordFailureUsage(task.id, exception)
            logger.warn("Task progress analyzer failed for Task {}", task.id, exception)
            return TaskCoordinationResult.Blocked(task, lifecycleGuard.analyzerFailureMessage(task))
        } catch (exception: RuntimeException) {
            logger.warn("Task progress analyzer failed for Task {}", task.id, exception)
            return TaskCoordinationResult.Blocked(task, lifecycleGuard.analyzerFailureMessage(task))
        }

        val proposal = analysis.proposal.normalizePlanningSemantics(task)
        val requestedAction = proposal.requestedAction ?: TaskActionType.NONE
        when (val decision = lifecycleGuard.evaluate(task.stage, requestedAction)) {
            is LifecycleGuardDecision.Blocked ->
                return TaskCoordinationResult.Blocked(task, decision.message)
            LifecycleGuardDecision.Allowed -> Unit
        }

        val update = TaskProgressUpdate(
            currentStep = proposal.currentStep,
            expectedActionType = proposal.expectedActionType,
            expectedActionDescription = proposal.expectedActionDescription,
        )
        val hasProgressMutation = update.currentStep != null ||
            update.expectedActionType != null ||
            update.expectedActionDescription != null
        if (!hasProgressMutation || task.stage == TaskStage.DONE) {
            return TaskCoordinationResult.Ready(task, requestedAction, proposal.suggestedEvent)
        }
        return try {
            val updated = stateService.updateProgress(
                task.id,
                update,
                TaskEventSource.CHAT_ANALYZER,
                task.version,
            )
            TaskCoordinationResult.Ready(updated, requestedAction, proposal.suggestedEvent)
        } catch (exception: TaskStateConflictException) {
            val actual = stateService.state(task.id)
            TaskCoordinationResult.Blocked(
                actual,
                "Task State изменился конкурентно. Текущий этап: ${actual.stage}, version: ${actual.version}. " +
                    "Повторите запрос с актуальным состоянием.",
            )
        } catch (exception: InvalidTaskStateException) {
            val actual = stateService.state(task.id)
            TaskCoordinationResult.Blocked(
                actual,
                "${exception.message}. Текущий этап: ${actual.stage}. Состояние Task не изменено.",
            )
        }
    }

    fun afterSuccessfulMainResponse(coordination: TaskCoordinationResult.Ready) {
        if (
            coordination.task.stage != TaskStage.PLANNING ||
            coordination.requestedAction != TaskActionType.PLAN
        ) {
            return
        }
        stateService.updateProgress(
            taskId = coordination.task.id,
            update = TaskProgressUpdate(
                currentStep = "Согласовать подготовленный план",
                expectedActionType = ExpectedActionType.USER_CONFIRMATION,
                expectedActionDescription = "Подтвердить план или запросить изменения",
            ),
            source = TaskEventSource.CHAT_ANALYZER,
            expectedVersion = coordination.task.version,
        )
    }

    private fun recordUsage(taskId: Long, analysis: TaskProgressAnalysis) {
        conversationRepository.recordUsage(
            taskId,
            LlmRequestUsage(
                provider = analysis.provider,
                purpose = LlmRequestPurpose.TASK_PROGRESS_ANALYZER,
                model = analysis.model,
                tokenUsage = analysis.usage,
                responseTimeMs = analysis.responseTimeMs,
            ),
        )
    }

    private fun recordFailureUsage(taskId: Long, exception: TaskProgressAnalyzerException) {
        try {
            conversationRepository.recordUsage(
                taskId,
                LlmRequestUsage(
                    provider = exception.provider,
                    purpose = LlmRequestPurpose.TASK_PROGRESS_ANALYZER,
                    model = exception.model,
                    tokenUsage = exception.usage,
                    responseTimeMs = exception.responseTimeMs,
                ),
            )
        } catch (persistenceException: RuntimeException) {
            logger.warn("Could not persist failed Task analyzer usage for Task {}", taskId, persistenceException)
        }
    }
}

sealed interface TaskCoordinationResult {
    val task: AgentTask

    data class Ready(
        override val task: AgentTask,
        val requestedAction: TaskActionType,
        val suggestedEvent: TaskEvent?,
    ) : TaskCoordinationResult

    data class Blocked(
        override val task: AgentTask,
        val message: String,
    ) : TaskCoordinationResult
}

@JsonIgnoreProperties(ignoreUnknown = false)
internal data class TaskProgressProposalResponse(
    val currentStep: String? = null,
    val expectedActionType: ExpectedActionType? = null,
    val expectedActionDescription: String? = null,
    val suggestedEvent: TaskEvent? = null,
    val requestedAction: TaskActionType? = null,
    val reason: String? = null,
)
