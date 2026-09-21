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
        return TaskProgressAnalysis(
            proposal = TaskProgressProposal(
                currentStep = parsed.currentStep?.trim()?.takeIf(String::isNotEmpty),
                expectedActionType = parsed.expectedActionType,
                expectedActionDescription = parsed.expectedActionDescription?.trim()?.takeIf(String::isNotEmpty),
                proposedEvent = parsed.proposedEvent,
                requestedAction = parsed.requestedAction,
                reason = parsed.reason?.trim()?.takeIf(String::isNotEmpty),
            ),
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
        appendLine("Allowed transitions:")
        appendLine("PLANNING + PLAN_APPROVED -> EXECUTION")
        appendLine("EXECUTION + EXECUTION_COMPLETED -> VALIDATION")
        appendLine("VALIDATION + VALIDATION_PASSED -> DONE")
        appendLine("VALIDATION + VALIDATION_FAILED -> EXECUTION")
        appendLine()
        appendLine("Rules:")
        appendLine("- Propose at most one event and only when the message clearly confirms that fact.")
        appendLine("- Classify requestedAction as PLAN, IMPLEMENT, VALIDATE, FINALIZE, STATUS, or NONE.")
        appendLine("- Requests to write or change production artifacts are IMPLEMENT, not PLAN.")
        appendLine("- Requests to declare the Task complete are FINALIZE.")
        appendLine("- Explicit plan approval must propose PLAN_APPROVED while in PLANNING.")
        appendLine("- A concrete allowed work item may update currentStep and expected action.")
        appendLine("- Keep currentStep concrete; use null for every state field that should remain unchanged.")
        appendLine("- Never propose an event that is invalid for the current stage.")
        append(
            "Return only JSON: {\"currentStep\":string|null," +
                "\"expectedActionType\":\"USER_INPUT|USER_CONFIRMATION|AGENT_ACTION|VALIDATION|NONE\"|null," +
                "\"expectedActionDescription\":string|null," +
                "\"proposedEvent\":\"PLAN_APPROVED|EXECUTION_COMPLETED|VALIDATION_PASSED|VALIDATION_FAILED\"|null," +
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
    private val stateMachine: TaskStateMachine,
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

        val proposal = analysis.proposal
        val requestedAction = proposal.requestedAction ?: TaskActionType.NONE
        val effectiveStage = try {
            proposal.proposedEvent?.let { stateMachine.transition(task.stage, it) } ?: task.stage
        } catch (_: InvalidTaskTransitionException) {
            return TaskCoordinationResult.Blocked(
                task,
                lifecycleGuard.invalidTransitionMessage(task, checkNotNull(proposal.proposedEvent)),
            )
        }
        when (
            val decision = lifecycleGuard.evaluate(
                currentStage = task.stage,
                effectiveStage = effectiveStage,
                requestedAction = requestedAction,
                acceptedEvent = proposal.proposedEvent,
            )
        ) {
            is LifecycleGuardDecision.Blocked ->
                return TaskCoordinationResult.Blocked(task, decision.message)
            LifecycleGuardDecision.Allowed -> Unit
        }

        val hasStateMutation = proposal.proposedEvent != null ||
            proposal.currentStep != null ||
            proposal.expectedActionType != null ||
            proposal.expectedActionDescription != null
        if (!hasStateMutation || task.stage == TaskStage.DONE) {
            return TaskCoordinationResult.Ready(task, requestedAction, proposal.proposedEvent)
        }

        return try {
            val updated = stateService.applyProposal(
                task.id,
                proposal,
                TaskEventSource.CHAT_ANALYZER,
                task.version,
            )
            TaskCoordinationResult.Ready(updated, requestedAction, proposal.proposedEvent)
        } catch (exception: InvalidTaskTransitionException) {
            val actual = stateService.state(task.id)
            TaskCoordinationResult.Blocked(
                actual,
                lifecycleGuard.invalidTransitionMessage(actual, exception.event),
            )
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
        val appliedEvent: TaskEvent?,
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
    val proposedEvent: TaskEvent? = null,
    val requestedAction: TaskActionType? = null,
    val reason: String? = null,
)
