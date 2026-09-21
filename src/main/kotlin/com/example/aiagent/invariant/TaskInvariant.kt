package com.example.aiagent.invariant

import com.example.aiagent.llm.LlmProvider
import com.example.aiagent.llm.TokenUsage
import java.time.Instant

enum class InvariantType {
    ARCHITECTURE,
    TECHNICAL_DECISION,
    STACK_CONSTRAINT,
    BUSINESS_RULE,
    OTHER,
}

data class TaskInvariant(
    val id: Long,
    val taskId: Long,
    val type: InvariantType,
    val key: String,
    val value: String,
    val description: String?,
    val enabled: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class TaskInvariantSnapshot(
    val id: Long,
    val taskId: Long,
    val taskName: String,
    val type: InvariantType,
    val key: String,
    val value: String,
    val description: String?,
)

data class CreateTaskInvariant(
    val type: InvariantType,
    val key: String,
    val value: String,
    val description: String?,
    val enabled: Boolean,
)

data class UpdateTaskInvariant(
    val type: InvariantType,
    val key: String,
    val value: String,
    val description: String?,
    val enabled: Boolean,
)

data class TaskInvariantSet(
    val taskId: Long,
    val taskName: String,
    val invariants: List<TaskInvariantSnapshot>,
) {
    init {
        require(invariants.all { it.taskId == taskId }) { "Invariant snapshot contains a different Task" }
    }

    companion object {
        fun empty(taskId: Long, taskName: String) = TaskInvariantSet(taskId, taskName, emptyList())
    }
}

enum class InvariantCheckDecision {
    ALLOWED,
    BLOCKED,
}

enum class InvariantCheckDirection {
    INPUT,
    OUTPUT,
}

data class InvariantViolation(
    val invariantId: Long,
    val reason: String,
)

data class InvariantCheckResult(
    val decision: InvariantCheckDecision,
    val direction: InvariantCheckDirection,
    val violations: List<InvariantViolation>,
)

data class InvariantAnalysis(
    val result: InvariantCheckResult,
    val provider: LlmProvider,
    val model: String,
    val usage: TokenUsage,
    val responseTimeMs: Long,
)

data class InvariantViolationDetail(
    val invariant: TaskInvariantSnapshot,
    val reason: String,
)

enum class InvariantCheckOutcome {
    DELIVERED,
    REFUSED,
    NOT_SENT,
}

enum class InvariantCheckStatus {
    OK,
    NO_ACTIVE_INVARIANTS,
    GUARD_DISABLED_BY_CONFIGURATION,
    SEMANTIC_CONFLICT,
    OUTPUT_CORRECTED,
    CHECK_FAILED,
}

data class LastInvariantCheck(
    val checkId: String,
    val taskId: Long,
    val taskName: String,
    val direction: InvariantCheckDirection,
    val result: InvariantCheckDecision,
    val requestExcerpt: String,
    val invariantSnapshot: List<TaskInvariantSnapshot>,
    val violations: List<InvariantViolationDetail>,
    val correctiveRetries: Int,
    val outcome: InvariantCheckOutcome,
    val status: InvariantCheckStatus,
    val errorCode: String?,
    val provider: LlmProvider?,
    val model: String?,
    val usage: TokenUsage?,
    val responseTimeMs: Long?,
    val checkedAt: Instant,
)

open class InvalidTaskInvariantException(message: String) : IllegalArgumentException(message)

class TaskInvariantNotFoundException(message: String) : RuntimeException(message)

class TaskInvariantConflictException(message: String) : RuntimeException(message)

class InvariantGuardException(
    val code: String,
    cause: Throwable? = null,
    val provider: LlmProvider? = null,
    val model: String? = null,
    val usage: TokenUsage? = null,
    val responseTimeMs: Long? = null,
) : RuntimeException(code, cause)
