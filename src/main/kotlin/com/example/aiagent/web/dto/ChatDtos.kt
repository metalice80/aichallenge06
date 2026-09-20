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
import com.example.aiagent.llm.LlmProvider
import com.example.aiagent.llm.TokenUsage
import com.example.aiagent.memory.EffectiveContext
import com.example.aiagent.memory.LastMemoryUpdate
import com.example.aiagent.memory.MemoryChange
import com.example.aiagent.memory.MemoryEntry
import com.example.aiagent.memory.MemoryInspector
import com.example.aiagent.task.AgentTask
import com.example.aiagent.task.TaskService
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
) {
    companion object {
        fun from(task: AgentTask) = TaskResponse(
            id = task.id,
            name = task.name,
            status = task.status,
            createdAt = task.createdAt,
            completedAt = task.completedAt,
            selected = task.selected,
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

data class EffectiveContextResponse(
    val strategy: ContextStrategyType,
    val systemPrompt: String,
    val longTermMemory: List<MemoryEntryResponse>,
    val workingMemory: List<MemoryEntryResponse>,
    val shortTerm: List<ChatHistoryResponse>,
    val currentUserMessage: ChatHistoryResponse,
    val preparedAt: Instant,
) {
    companion object {
        fun from(context: EffectiveContext) = EffectiveContextResponse(
            strategy = context.strategy,
            systemPrompt = context.systemPrompt,
            longTermMemory = context.longTermMemory.map(MemoryEntryResponse::from),
            workingMemory = context.workingMemory.map(MemoryEntryResponse::from),
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
