package com.example.aiagent.web.dto

import com.example.aiagent.invariant.CreateTaskInvariant
import com.example.aiagent.invariant.InvariantType
import com.example.aiagent.invariant.InvariantViolationDetail
import com.example.aiagent.invariant.LastInvariantCheck
import com.example.aiagent.invariant.TaskInvariant
import com.example.aiagent.invariant.TaskInvariantService
import com.example.aiagent.invariant.TaskInvariantSnapshot
import com.example.aiagent.invariant.UpdateTaskInvariant
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant

data class CreateTaskInvariantRequest(
    val type: InvariantType,
    @field:NotBlank
    @field:Size(max = TaskInvariantService.MAX_KEY_LENGTH)
    val key: String,
    @field:NotBlank
    @field:Size(max = TaskInvariantService.MAX_VALUE_LENGTH)
    val value: String,
    @field:Size(max = TaskInvariantService.MAX_DESCRIPTION_LENGTH)
    val description: String? = null,
    val enabled: Boolean = true,
) {
    fun command() = CreateTaskInvariant(type, key, value, description, enabled)
}

data class UpdateTaskInvariantRequest(
    val type: InvariantType,
    @field:NotBlank
    @field:Size(max = TaskInvariantService.MAX_KEY_LENGTH)
    val key: String,
    @field:NotBlank
    @field:Size(max = TaskInvariantService.MAX_VALUE_LENGTH)
    val value: String,
    @field:Size(max = TaskInvariantService.MAX_DESCRIPTION_LENGTH)
    val description: String? = null,
    val enabled: Boolean,
) {
    fun command() = UpdateTaskInvariant(type, key, value, description, enabled)
}

data class SetTaskInvariantEnabledRequest(
    val enabled: Boolean,
)

data class TaskInvariantResponse(
    val id: Long,
    val taskId: Long,
    val type: InvariantType,
    val key: String,
    val value: String,
    val description: String?,
    val enabled: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun from(invariant: TaskInvariant) = TaskInvariantResponse(
            id = invariant.id,
            taskId = invariant.taskId,
            type = invariant.type,
            key = invariant.key,
            value = invariant.value,
            description = invariant.description,
            enabled = invariant.enabled,
            createdAt = invariant.createdAt,
            updatedAt = invariant.updatedAt,
        )
    }
}

data class InvariantViolationResponse(
    val invariant: TaskInvariantSnapshotResponse,
    val reason: String,
) {
    companion object {
        fun from(violation: InvariantViolationDetail) = InvariantViolationResponse(
            TaskInvariantSnapshotResponse.from(violation.invariant),
            violation.reason,
        )
    }
}

data class LastInvariantCheckResponse(
    val checkId: String,
    val taskId: Long,
    val taskName: String,
    val direction: String,
    val result: String,
    val requestExcerpt: String,
    val invariantSnapshot: List<TaskInvariantSnapshotResponse>,
    val violations: List<InvariantViolationResponse>,
    val correctiveRetries: Int,
    val outcome: String,
    val status: String,
    val errorCode: String?,
    val provider: String?,
    val model: String?,
    val usage: TokenUsageResponse?,
    val responseTimeMs: Long?,
    val checkedAt: Instant,
) {
    companion object {
        fun from(check: LastInvariantCheck) = LastInvariantCheckResponse(
            checkId = check.checkId,
            taskId = check.taskId,
            taskName = check.taskName,
            direction = check.direction.name,
            result = check.result.name,
            requestExcerpt = check.requestExcerpt,
            invariantSnapshot = check.invariantSnapshot.map(TaskInvariantSnapshotResponse::from),
            violations = check.violations.map(InvariantViolationResponse::from),
            correctiveRetries = check.correctiveRetries,
            outcome = check.outcome.name,
            status = check.status.name,
            errorCode = check.errorCode,
            provider = check.provider?.name,
            model = check.model,
            usage = check.usage?.let(TokenUsageResponse::from),
            responseTimeMs = check.responseTimeMs,
            checkedAt = check.checkedAt,
        )
    }
}
