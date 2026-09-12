package com.example.aiagent.web

import com.example.aiagent.agent.Agent
import com.example.aiagent.agent.Role
import com.example.aiagent.web.dto.ChatRequest
import com.example.aiagent.web.dto.ChatHistoryResponse
import com.example.aiagent.web.dto.ChatResponse
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
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

    @PostMapping
    fun chat(@Valid @RequestBody request: ChatRequest): ChatResponse =
        ChatResponse.from(agent.sendMessage(request.message))

    @PostMapping("/reset")
    fun reset(): ResponseEntity<Void> {
        agent.reset()
        return ResponseEntity.noContent().build()
    }
}
