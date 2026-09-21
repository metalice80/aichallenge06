package com.example.aiagent.web.dto

import com.example.aiagent.agent.AgentResponse
import com.example.aiagent.agent.AgentState
import com.example.aiagent.agent.ChatAgent
import com.example.aiagent.agent.ChatMessage
import com.example.aiagent.agent.ConversationTokenUsage
import com.example.aiagent.context.branch.ConversationBranch
import com.example.aiagent.context.strategy.ContextStrategyType
import com.example.aiagent.agent.LlmProviderOption
import com.example.aiagent.agent.Role
import com.example.aiagent.invariant.TaskInvariantSnapshot
import com.example.aiagent.llm.LlmProvider
import com.example.aiagent.llm.TokenUsage
import com.example.aiagent.memory.EffectiveContext
import com.example.aiagent.memory.LastMemoryUpdate
import com.example.aiagent.memory.MemoryChange
import com.example.aiagent.memory.MemoryEntry
import com.example.aiagent.memory.MemoryInspector
import com.example.aiagent.task.AgentTask
import com.example.aiagent.task.ExpectedActionType
import com.example.aiagent.task.TaskEvent
import com.example.aiagent.task.TaskProgressProposal
import com.example.aiagent.task.TaskService
import com.example.aiagent.task.TaskStage
import com.example.aiagent.task.TaskStateHistoryEntry
import com.example.aiagent.task.TaskStateService
import com.example.aiagent.task.TaskStateSnapshot
import com.example.aiagent.task.TaskStatus
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant

data class ChatRequest(
    @field:NotBlank(message = "Сообщение не должно быть пустым.")
    @field:Size(
        max = ChatAgent.MAX_MESSAGE_LENGTH,
        message = "Сообщение не должно превышать ${ChatAgent.MAX_MESSAGE_LENGTH} символов.",
    )
    val message: String,
    val provider: LlmProvider,
    @field:NotBlank(message = "Модель не должна быть пустой.")
    @field:Size(
        max = ChatAgent.MAX_MODEL_LENGTH,
        message = "Название модели не должно превышать ${ChatAgent.MAX_MODEL_LENGTH} символов.",
    )
    val model: String,
    val contextStrategy: ContextStrategyType = ContextStrategyType.SLIDING_WINDOW,
)

data class CreateTaskRequest(
    @field:NotBlank(message = "Название задачи не должно быть пустым.")
    @field:Size(
        max = TaskService.MAX_NAME_LENGTH,
        message = "Название задачи не должно превышать ${TaskService.MAX_NAME_LENGTH} символов.",
    )
    val name: String,
)

data class TaskEventRequest(
    val event: TaskEvent,
    @field:Size(max = TaskStateService.MAX_CURRENT_STEP_LENGTH)
    val currentStep: String? = null,
    val expectedActionType: ExpectedActionType? = null,
    @field:Size(max = TaskStateService.MAX_EXPECTED_ACTION_DESCRIPTION_LENGTH)
    val expectedActionDescription: String? = null,
) {
    fun proposal() = TaskProgressProposal(
        currentStep = currentStep,
        expectedActionType = expectedActionType,
        expectedActionDescription = expectedActionDescription,
    )
}

data class UpdateTaskProgressRequest(
    @field:NotBlank(message = "Current step must not be blank.")
    @field:Size(max = TaskStateService.MAX_CURRENT_STEP_LENGTH)
    val currentStep: String,
    val expectedActionType: ExpectedActionType,
    @field:Size(max = TaskStateService.MAX_EXPECTED_ACTION_DESCRIPTION_LENGTH)
    val expectedActionDescription: String? = null,
)

data class TokenUsageResponse(
    val inputTokens: Long?,
    val outputTokens: Long?,
    val totalTokens: Long?,
) {
    companion object {
        fun from(usage: TokenUsage): TokenUsageResponse = TokenUsageResponse(
            inputTokens = usage.inputTokens,
            outputTokens = usage.outputTokens,
            totalTokens = usage.totalTokens,
        )
    }
}

data class ConversationTokenUsageResponse(
    val inputTokens: Long,
    val outputTokens: Long,
    val totalTokens: Long,
) {
    companion object {
        fun from(usage: ConversationTokenUsage): ConversationTokenUsageResponse =
            ConversationTokenUsageResponse(
                inputTokens = usage.inputTokens,
                outputTokens = usage.outputTokens,
                totalTokens = usage.totalTokens,
            )
    }
}

data class ChatResponse(
    val provider: LlmProvider,
    val content: String,
    val model: String,
    val currentUsage: TokenUsageResponse,
    val conversationUsage: ConversationTokenUsageResponse,
    val responseTimeMs: Long,
) {
    companion object {
        fun from(response: AgentResponse): ChatResponse = ChatResponse(
            provider = response.provider,
            content = response.content,
            model = response.model,
            currentUsage = TokenUsageResponse.from(response.currentUsage),
            conversationUsage = ConversationTokenUsageResponse.from(response.conversationUsage),
            responseTimeMs = response.responseTimeMs,
        )
    }
}

data class LlmProviderOptionResponse(
    val provider: LlmProvider,
    val displayName: String,
    val defaultModel: String,
) {
    companion object {
        fun from(option: LlmProviderOption): LlmProviderOptionResponse = LlmProviderOptionResponse(
            provider = option.provider,
            displayName = option.displayName,
            defaultModel = option.defaultModel,
        )
    }
}

data class ContextStrategyOptionResponse(
    val type: ContextStrategyType,
    val displayName: String,
) {
    companion object {
        fun from(type: ContextStrategyType) =
            ContextStrategyOptionResponse(type, type.displayName)
    }
}

data class ConversationBranchResponse(
    val id: Long,
    val name: String,
    val parentBranchId: Long?,
    val checkpointMessageCount: Int,
    val active: Boolean,
) {
    companion object {
        fun from(branch: ConversationBranch) = ConversationBranchResponse(
            id = branch.id,
            name = branch.name,
            parentBranchId = branch.parentBranchId,
            checkpointMessageCount = branch.checkpointMessageCount,
            active = branch.active,
        )
    }
}

data class ChatHistoryResponse(
    val role: Role,
    val content: String,
) {
    companion object {
        fun from(message: ChatMessage): ChatHistoryResponse = ChatHistoryResponse(
            role = message.role,
            content = message.content,
        )
    }
}

data class ChatStateResponse(
    val messages: List<ChatHistoryResponse>,
    val conversationUsage: ConversationTokenUsageResponse,
) {
    companion object {
        fun from(state: AgentState): ChatStateResponse = ChatStateResponse(
            messages = state.messages
                .filterNot { it.role == Role.SYSTEM }
                .map(ChatHistoryResponse::from),
            conversationUsage = ConversationTokenUsageResponse.from(state.conversationUsage),
        )
    }
}

data class TaskResponse(
    val id: Long,
    val name: String,
    val status: TaskStatus,
    val createdAt: Instant,
    val completedAt: Instant?,
    val selected: Boolean,
    val stage: TaskStage,
    val currentStep: String,
    val expectedActionType: ExpectedActionType,
    val expectedActionDescription: String?,
    val paused: Boolean,
) {
    companion object {
        fun from(task: AgentTask) = TaskResponse(
            id = task.id,
            name = task.name,
            status = task.status,
            createdAt = task.createdAt,
            completedAt = task.completedAt,
            selected = task.selected,
            stage = task.stage,
            currentStep = task.currentStep,
            expectedActionType = task.expectedActionType,
            expectedActionDescription = task.expectedActionDescription,
            paused = task.paused,
        )
    }
}

data class TaskStateSnapshotResponse(
    val taskId: Long,
    val taskName: String,
    val stage: TaskStage,
    val currentStep: String,
    val expectedActionType: ExpectedActionType,
    val expectedActionDescription: String?,
    val paused: Boolean,
) {
    companion object {
        fun from(state: TaskStateSnapshot) = TaskStateSnapshotResponse(
            taskId = state.taskId,
            taskName = state.taskName,
            stage = state.stage,
            currentStep = state.currentStep,
            expectedActionType = state.expectedActionType,
            expectedActionDescription = state.expectedActionDescription,
            paused = state.paused,
        )
    }
}

data class TaskStateHistoryResponse(
    val id: Long,
    val taskId: Long,
    val event: String,
    val fromStage: TaskStage?,
    val toStage: TaskStage,
    val paused: Boolean,
    val currentStep: String,
    val description: String?,
    val createdAt: Instant,
) {
    companion object {
        fun from(entry: TaskStateHistoryEntry) = TaskStateHistoryResponse(
            id = entry.id,
            taskId = entry.taskId,
            event = entry.event.name,
            fromStage = entry.fromStage,
            toStage = entry.toStage,
            paused = entry.paused,
            currentStep = entry.currentStep,
            description = entry.description,
            createdAt = entry.createdAt,
        )
    }
}

data class MemoryEntryResponse(
    val key: String,
    val value: String,
) {
    companion object {
        fun from(entry: MemoryEntry) = MemoryEntryResponse(entry.key, entry.value)
    }
}

data class MemoryChangeResponse(
    val type: String,
    val key: String,
    val oldValue: String?,
    val newValue: String?,
) {
    companion object {
        fun from(change: MemoryChange) = MemoryChangeResponse(
            type = change.type.name,
            key = change.key,
            oldValue = change.oldValue,
            newValue = change.newValue,
        )
    }
}

data class LastMemoryUpdateResponse(
    val userMessage: String,
    val working: List<MemoryChangeResponse>,
    val longTerm: List<MemoryChangeResponse>,
    val error: String?,
    val updatedAt: Instant,
) {
    companion object {
        fun from(update: LastMemoryUpdate) = LastMemoryUpdateResponse(
            userMessage = update.userMessage,
            working = update.working.map(MemoryChangeResponse::from),
            longTerm = update.longTerm.map(MemoryChangeResponse::from),
            error = update.error,
            updatedAt = update.updatedAt,
        )
    }
}

data class TaskInvariantSnapshotResponse(
    val id: Long,
    val taskId: Long,
    val taskName: String,
    val type: String,
    val key: String,
    val value: String,
    val description: String?,
) {
    companion object {
        fun from(invariant: TaskInvariantSnapshot) = TaskInvariantSnapshotResponse(
            id = invariant.id,
            taskId = invariant.taskId,
            taskName = invariant.taskName,
            type = invariant.type.name,
            key = invariant.key,
            value = invariant.value,
            description = invariant.description,
        )
    }
}

data class EffectiveContextResponse(
    val strategy: ContextStrategyType,
    val systemPrompt: String,
    val longTermMemory: List<MemoryEntryResponse>,
    val userProfile: UserProfileResponse?,
    val workingMemory: List<MemoryEntryResponse>,
    val taskState: TaskStateSnapshotResponse?,
    val taskInvariants: List<TaskInvariantSnapshotResponse>,
    val shortTerm: List<ChatHistoryResponse>,
    val currentUserMessage: ChatHistoryResponse,
    val preparedAt: Instant,
) {
    companion object {
        fun from(context: EffectiveContext) = EffectiveContextResponse(
            strategy = context.strategy,
            systemPrompt = context.systemPrompt,
            longTermMemory = context.longTermMemory.map(MemoryEntryResponse::from),
            userProfile = context.userProfile?.let(UserProfileResponse::from),
            workingMemory = context.workingMemory.map(MemoryEntryResponse::from),
            taskState = context.taskState?.let(TaskStateSnapshotResponse::from),
            taskInvariants = context.taskInvariants.map(TaskInvariantSnapshotResponse::from),
            shortTerm = context.shortTerm.map(ChatHistoryResponse::from),
            currentUserMessage = ChatHistoryResponse.from(context.currentUserMessage),
            preparedAt = context.preparedAt,
        )
    }
}

data class MemoryInspectorResponse(
    val strategy: ContextStrategyType,
    val shortTerm: List<ChatHistoryResponse>,
    val working: List<MemoryEntryResponse>,
    val longTerm: List<MemoryEntryResponse>,
    val lastUpdate: LastMemoryUpdateResponse?,
    val effectiveContext: EffectiveContextResponse?,
) {
    companion object {
        fun from(
            inspector: MemoryInspector,
            strategy: ContextStrategyType,
        ) = MemoryInspectorResponse(
            strategy = strategy,
            shortTerm = inspector.shortTerm.map(ChatHistoryResponse::from),
            working = inspector.working.map(MemoryEntryResponse::from),
            longTerm = inspector.longTerm.map(MemoryEntryResponse::from),
            lastUpdate = inspector.lastUpdate?.let(LastMemoryUpdateResponse::from),
            effectiveContext = inspector.effectiveContext?.let(EffectiveContextResponse::from),
        )
    }
}

data class ApiError(
    val message: String,
)
