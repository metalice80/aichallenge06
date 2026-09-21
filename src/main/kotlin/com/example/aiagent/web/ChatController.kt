package com.example.aiagent.web

import com.example.aiagent.agent.Agent
import com.example.aiagent.agent.AgentRequest
import com.example.aiagent.agent.Role
import com.example.aiagent.context.strategy.ContextStrategyType
import com.example.aiagent.web.dto.ChatHistoryResponse
import com.example.aiagent.web.dto.ChatRequest
import com.example.aiagent.web.dto.ChatResponse
import com.example.aiagent.web.dto.ChatStateResponse
import com.example.aiagent.web.dto.ContextStrategyOptionResponse
import com.example.aiagent.web.dto.ConversationBranchResponse
import com.example.aiagent.web.dto.CreateTaskRequest
import com.example.aiagent.web.dto.LlmProviderOptionResponse
import com.example.aiagent.web.dto.MemoryInspectorResponse
import com.example.aiagent.web.dto.TaskResponse
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/chat")
class ChatController(
    private val agent: Agent,
) {
    @GetMapping("/history")
    fun history(): List<ChatHistoryResponse> =
        agent.history()
            .filterNot { it.role == Role.SYSTEM }
            .map(ChatHistoryResponse::from)

    @GetMapping("/state")
    fun state(): ChatStateResponse = ChatStateResponse.from(agent.state())

    @GetMapping("/providers")
    fun providers(): List<LlmProviderOptionResponse> =
        agent.providers().map(LlmProviderOptionResponse::from)

    @GetMapping("/context-strategies")
    fun contextStrategies(): List<ContextStrategyOptionResponse> =
        agent.contextStrategies().map(ContextStrategyOptionResponse::from)

    @GetMapping("/tasks")
    fun tasks(): List<TaskResponse> = agent.tasks().map(TaskResponse::from)

    @PostMapping("/tasks")
    fun createTask(@Valid @RequestBody request: CreateTaskRequest): ChatStateResponse =
        ChatStateResponse.from(agent.createTask(request.name))

    @PostMapping("/tasks/{taskId}/activate")
    fun activateTask(@PathVariable taskId: Long): ChatStateResponse =
        ChatStateResponse.from(agent.activateTask(taskId))


    @GetMapping("/branches")
    fun branches(): List<ConversationBranchResponse> =
        agent.branches().map(ConversationBranchResponse::from)

    @PostMapping("/branches")
    fun createBranch(): ConversationBranchResponse =
        ConversationBranchResponse.from(agent.createBranch())

    @PostMapping("/branches/{branchId}/activate")
    fun activateBranch(@PathVariable branchId: Long): ChatStateResponse =
        ChatStateResponse.from(agent.activateBranch(branchId))

    @GetMapping("/memory")
    fun memory(
        @RequestParam(defaultValue = "SLIDING_WINDOW") contextStrategy: ContextStrategyType,
    ): MemoryInspectorResponse = MemoryInspectorResponse.from(
        agent.memory(contextStrategy),
        contextStrategy,
    )

    @PostMapping("/memory/working/clear")
    fun clearWorkingMemory(): ResponseEntity<Void> {
        agent.clearWorkingMemory()
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/memory/long-term/clear")
    fun clearLongTermMemory(): ResponseEntity<Void> {
        agent.clearLongTermMemory()
        return ResponseEntity.noContent().build()
    }

    @PostMapping
    fun chat(@Valid @RequestBody request: ChatRequest): ChatResponse =
        ChatResponse.from(
            agent.sendMessage(
                AgentRequest(
                    message = request.message,
                    provider = request.provider,
                    model = request.model,
                    contextStrategy = request.contextStrategy,
                ),
            ),
        )

    @PostMapping("/reset")
    fun reset(): ResponseEntity<Void> {
        agent.reset()
        return ResponseEntity.noContent().build()
    }
}
