package com.example.aiagent.web

import com.example.aiagent.agent.Agent
import com.example.aiagent.agent.AgentRequest
import com.example.aiagent.agent.Role
import com.example.aiagent.web.dto.ChatRequest
import com.example.aiagent.web.dto.ContextStrategyOptionResponse
import com.example.aiagent.web.dto.ConversationBranchResponse
import com.example.aiagent.web.dto.ChatHistoryResponse
import com.example.aiagent.web.dto.ChatStateResponse
import com.example.aiagent.web.dto.LlmProviderOptionResponse
import com.example.aiagent.web.dto.ChatResponse
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
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

    @GetMapping("/branches")
    fun branches(): List<ConversationBranchResponse> =
        agent.branches().map(ConversationBranchResponse::from)

    @PostMapping("/branches")
    fun createBranch(): ConversationBranchResponse =
        ConversationBranchResponse.from(agent.createBranch())

    @PostMapping("/branches/{branchId}/activate")
    fun activateBranch(@PathVariable branchId: Long): ChatStateResponse =
        ChatStateResponse.from(agent.activateBranch(branchId))

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
