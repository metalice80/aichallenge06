package com.example.aiagent.invariant

import com.example.aiagent.agent.LlmRequestPurpose
import com.example.aiagent.agent.LlmRequestUsage
import com.example.aiagent.config.InvariantProperties
import com.example.aiagent.memory.SecretRedactor
import com.example.aiagent.persistence.ConversationRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

@Service
class InvariantGuardService(
    private val properties: InvariantProperties,
    private val analyzer: InvariantConflictAnalyzer,
    private val invariantService: TaskInvariantService,
    private val conversationRepository: ConversationRepository,
    private val secretRedactor: SecretRedactor,
) {
    fun evaluateInput(request: String, invariantSet: TaskInvariantSet): InvariantGuardEvaluation =
        evaluate(InvariantCheckDirection.INPUT, request, null, invariantSet)

    fun evaluateOutput(
        request: String,
        candidateResponse: String,
        invariantSet: TaskInvariantSet,
    ): InvariantGuardEvaluation =
        evaluate(InvariantCheckDirection.OUTPUT, request, candidateResponse, invariantSet)

    fun record(
        evaluation: InvariantGuardEvaluation,
        request: String,
        correctiveRetries: Int,
        outcome: InvariantCheckOutcome,
        status: InvariantCheckStatus = evaluation.status,
    ): LastInvariantCheck {
        val invariantsById = evaluation.invariantSet.invariants.associateBy(TaskInvariantSnapshot::id)
        val violationDetails = evaluation.result.violations.map { violation ->
            val invariant = invariantsById[violation.invariantId]
                ?: throw InvariantGuardException("UNKNOWN_INVARIANT_ID")
            InvariantViolationDetail(invariant, violation.reason)
        }
        val analysis = evaluation.analysis
        val check = LastInvariantCheck(
            checkId = UUID.randomUUID().toString(),
            taskId = evaluation.invariantSet.taskId,
            taskName = evaluation.invariantSet.taskName,
            direction = evaluation.result.direction,
            result = evaluation.result.decision,
            requestExcerpt = excerpt(request),
            invariantSnapshot = evaluation.invariantSet.invariants,
            violations = violationDetails,
            correctiveRetries = correctiveRetries,
            outcome = outcome,
            status = status,
            errorCode = null,
            provider = analysis?.provider ?: properties.guard.provider,
            model = analysis?.model ?: properties.guard.model.trim(),
            usage = analysis?.usage,
            responseTimeMs = analysis?.responseTimeMs,
            checkedAt = Instant.now(),
        )
        try {
            invariantService.recordCheck(check)
        } catch (exception: RuntimeException) {
            throw InvariantGuardException("CHECK_DIAGNOSTIC_PERSISTENCE_FAILED", exception)
        }
        logCheck(check)
        return check
    }

    fun recordFailure(
        invariantSet: TaskInvariantSet,
        direction: InvariantCheckDirection,
        request: String,
        correctiveRetries: Int,
        outcome: InvariantCheckOutcome,
        errorCode: String = "CHECK_FAILED",
        failure: InvariantGuardException? = null,
    ): LastInvariantCheck {
        val check = LastInvariantCheck(
            checkId = UUID.randomUUID().toString(),
            taskId = invariantSet.taskId,
            taskName = invariantSet.taskName,
            direction = direction,
            result = InvariantCheckDecision.BLOCKED,
            requestExcerpt = excerpt(request),
            invariantSnapshot = invariantSet.invariants,
            violations = emptyList(),
            correctiveRetries = correctiveRetries,
            outcome = outcome,
            status = InvariantCheckStatus.CHECK_FAILED,
            errorCode = failure?.code ?: errorCode,
            provider = failure?.provider ?: properties.guard.provider,
            model = failure?.model ?: properties.guard.model.trim(),
            usage = failure?.usage,
            responseTimeMs = failure?.responseTimeMs,
            checkedAt = Instant.now(),
        )
        try {
            invariantService.recordCheck(check)
        } catch (_: RuntimeException) {
            // The guard remains fail-closed even when diagnostic persistence is unavailable.
        }
        logCheck(check)
        return check
    }

    fun semanticRefusal(evaluation: InvariantGuardEvaluation): String = buildString {
        appendLine("Этот запрос конфликтует с обязательным ограничением текущей задачи.")
        evaluation.result.violations.forEach { violation ->
            val invariant = evaluation.invariantSet.invariants.single { it.id == violation.invariantId }
            appendLine()
            appendLine("Инвариант:")
            appendLine("${invariant.key} = ${invariant.value}")
            appendLine()
            appendLine("Причина:")
            appendLine(violation.reason)
        }
        appendLine()
        appendLine("Я не буду выполнять или предлагать это изменение, пока invariant активен.")
        append("Измените, отключите или удалите invariant через панель Invariants либо dedicated API.")
    }

    fun technicalRefusal(): String =
        "Не удалось безопасно проверить запрос на соответствие обязательным инвариантам текущей задачи. " +
            "Поэтому запрос не был выполнен и состояние задачи не изменилось. Повторите попытку позже."

    fun outputRefusal(evaluation: InvariantGuardEvaluation?): String = buildString {
        appendLine("Не удалось сформировать ответ, который можно гарантированно проверить на соответствие " +
            "обязательным ограничениям текущей задачи.")
        evaluation?.result?.violations?.forEach { violation ->
            val invariant = evaluation.invariantSet.invariants.single { it.id == violation.invariantId }
            appendLine()
            appendLine("Затронутый invariant: ${invariant.key} = ${invariant.value}")
            appendLine("Причина: ${violation.reason}")
        }
        appendLine()
        append("Уточните запрос либо явно измените invariant через панель управления.")
    }

    fun correctiveInstruction(evaluation: InvariantGuardEvaluation): String = buildString {
        appendLine("Your previous candidate response violated mandatory invariants of the active Task.")
        appendLine("Do not expose or repeat the violating candidate.")
        appendLine("Violated invariants:")
        evaluation.result.violations.forEach { violation ->
            val invariant = evaluation.invariantSet.invariants.single { it.id == violation.invariantId }
            appendLine("- [#${invariant.id} ${invariant.type}] ${invariant.key} = ${invariant.value}")
            appendLine("  Reason: ${violation.reason}")
        }
        appendLine("Generate one corrected answer to the original user request.")
        appendLine("The corrected answer must satisfy every invariant in the immutable snapshot.")
        append("Do not claim that an invariant was changed, disabled, or overridden.")
    }

    val maxCorrectiveRetries: Int
        get() = properties.guard.maxCorrectiveRetries

    private fun evaluate(
        direction: InvariantCheckDirection,
        request: String,
        candidateResponse: String?,
        invariantSet: TaskInvariantSet,
    ): InvariantGuardEvaluation {
        if (invariantSet.invariants.isEmpty()) {
            return allowedWithoutAnalysis(direction, invariantSet, InvariantCheckStatus.NO_ACTIVE_INVARIANTS)
        }
        if (!properties.guard.enabled) {
            return allowedWithoutAnalysis(
                direction,
                invariantSet,
                InvariantCheckStatus.GUARD_DISABLED_BY_CONFIGURATION,
            )
        }
        return try {
            val analysis = analyzer.analyze(direction, request, candidateResponse, invariantSet)
            val purpose = purpose(direction)
            conversationRepository.recordUsage(
                invariantSet.taskId,
                LlmRequestUsage(
                    provider = analysis.provider,
                    purpose = purpose,
                    model = analysis.model,
                    tokenUsage = analysis.usage,
                    responseTimeMs = analysis.responseTimeMs,
                ),
            )
            InvariantGuardEvaluation(
                invariantSet = invariantSet,
                result = analysis.result,
                analysis = analysis,
                status = if (analysis.result.decision == InvariantCheckDecision.BLOCKED) {
                    InvariantCheckStatus.SEMANTIC_CONFLICT
                } else {
                    InvariantCheckStatus.OK
                },
            )
        } catch (exception: InvariantGuardException) {
            try {
                recordFailedAnalysisUsage(direction, invariantSet, exception)
            } catch (persistenceException: RuntimeException) {
                throw InvariantGuardException(
                    code = "USAGE_PERSISTENCE_FAILED",
                    cause = persistenceException,
                    provider = exception.provider,
                    model = exception.model,
                    usage = exception.usage,
                    responseTimeMs = exception.responseTimeMs,
                )
            }
            throw exception
        } catch (exception: RuntimeException) {
            throw InvariantGuardException(exception.javaClass.simpleName, exception)
        }
    }

    private fun recordFailedAnalysisUsage(
        direction: InvariantCheckDirection,
        invariantSet: TaskInvariantSet,
        exception: InvariantGuardException,
    ) {
        val provider = exception.provider ?: return
        val model = exception.model ?: return
        val usage = exception.usage ?: return
        val responseTimeMs = exception.responseTimeMs ?: return
        conversationRepository.recordUsage(
            invariantSet.taskId,
            LlmRequestUsage(
                provider = provider,
                purpose = purpose(direction),
                model = model,
                tokenUsage = usage,
                responseTimeMs = responseTimeMs,
            ),
        )
    }

    private fun purpose(direction: InvariantCheckDirection) =
        if (direction == InvariantCheckDirection.INPUT) {
            LlmRequestPurpose.INVARIANT_INPUT_GUARD
        } else {
            LlmRequestPurpose.INVARIANT_OUTPUT_GUARD
        }

    private fun allowedWithoutAnalysis(
        direction: InvariantCheckDirection,
        invariantSet: TaskInvariantSet,
        status: InvariantCheckStatus,
    ) = InvariantGuardEvaluation(
        invariantSet = invariantSet,
        result = InvariantCheckResult(
            decision = InvariantCheckDecision.ALLOWED,
            direction = direction,
            violations = emptyList(),
        ),
        analysis = null,
        status = status,
    )

    private fun excerpt(value: String): String =
        secretRedactor.redact(value.trim()).take(MAX_EXCERPT_LENGTH)

    private fun logCheck(check: LastInvariantCheck) {
        logger.info(
            "Invariant check {} task={} direction={} result={} active={} violated={} provider={} model={} " +
                "latencyMs={} retries={} outcome={} status={} errorCode={}",
            check.checkId,
            check.taskId,
            check.direction,
            check.result,
            check.invariantSnapshot.size,
            check.violations.map { it.invariant.id },
            check.provider,
            check.model,
            check.responseTimeMs,
            check.correctiveRetries,
            check.outcome,
            check.status,
            check.errorCode,
        )
    }

    companion object {
        private const val MAX_EXCERPT_LENGTH = 500
        private val logger = LoggerFactory.getLogger(InvariantGuardService::class.java)
    }
}

data class InvariantGuardEvaluation(
    val invariantSet: TaskInvariantSet,
    val result: InvariantCheckResult,
    val analysis: InvariantAnalysis?,
    val status: InvariantCheckStatus,
)
