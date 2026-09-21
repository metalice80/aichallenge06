package com.example.aiagent.web

import com.example.aiagent.invariant.TaskInvariantService
import com.example.aiagent.web.dto.CreateTaskInvariantRequest
import com.example.aiagent.web.dto.LastInvariantCheckResponse
import com.example.aiagent.web.dto.SetTaskInvariantEnabledRequest
import com.example.aiagent.web.dto.TaskInvariantResponse
import com.example.aiagent.web.dto.UpdateTaskInvariantRequest
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/tasks/{taskId}")
class TaskInvariantController(
    private val service: TaskInvariantService,
) {
    @GetMapping("/invariants")
    fun list(@PathVariable taskId: Long): List<TaskInvariantResponse> =
        service.list(taskId).map(TaskInvariantResponse::from)

    @GetMapping("/invariants/{invariantId}")
    fun get(
        @PathVariable taskId: Long,
        @PathVariable invariantId: Long,
    ): TaskInvariantResponse = TaskInvariantResponse.from(service.get(taskId, invariantId))

    @PostMapping("/invariants")
    fun create(
        @PathVariable taskId: Long,
        @Valid @RequestBody request: CreateTaskInvariantRequest,
    ): TaskInvariantResponse = TaskInvariantResponse.from(service.create(taskId, request.command()))

    @PutMapping("/invariants/{invariantId}")
    fun update(
        @PathVariable taskId: Long,
        @PathVariable invariantId: Long,
        @Valid @RequestBody request: UpdateTaskInvariantRequest,
    ): TaskInvariantResponse = TaskInvariantResponse.from(
        service.update(taskId, invariantId, request.command()),
    )

    @PatchMapping("/invariants/{invariantId}/enabled")
    fun setEnabled(
        @PathVariable taskId: Long,
        @PathVariable invariantId: Long,
        @Valid @RequestBody request: SetTaskInvariantEnabledRequest,
    ): TaskInvariantResponse = TaskInvariantResponse.from(
        service.setEnabled(taskId, invariantId, request.enabled),
    )

    @DeleteMapping("/invariants/{invariantId}")
    fun delete(
        @PathVariable taskId: Long,
        @PathVariable invariantId: Long,
    ): ResponseEntity<Void> {
        service.delete(taskId, invariantId)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/invariant-checks/last")
    fun lastCheck(@PathVariable taskId: Long): ResponseEntity<LastInvariantCheckResponse> =
        service.lastCheck(taskId)?.let { ResponseEntity.ok(LastInvariantCheckResponse.from(it)) }
            ?: ResponseEntity.noContent().build()
}
