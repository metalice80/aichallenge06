package com.example.aiagent.web

import com.example.aiagent.task.AgentTask
import com.example.aiagent.task.TaskEventSource
import com.example.aiagent.task.TaskStateService
import com.example.aiagent.web.dto.TaskEventRequest
import com.example.aiagent.web.dto.TaskStateHistoryResponse
import com.example.aiagent.web.dto.TaskStateResponse
import com.example.aiagent.web.dto.TaskVersionRequest
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/tasks")
class TaskLifecycleController(
    private val taskStateService: TaskStateService,
) {
    @GetMapping("/{taskId}/state")
    fun state(@PathVariable taskId: Long): TaskStateResponse =
        response(taskStateService.state(taskId))

    @PostMapping("/{taskId}/events")
    fun applyEvent(
        @PathVariable taskId: Long,
        @Valid @RequestBody request: TaskEventRequest,
    ): TaskStateResponse = response(
        taskStateService.applyEvent(
            taskId = taskId,
            event = request.event,
            source = TaskEventSource.REST_API,
            expectedVersion = request.expectedVersion,
        ),
    )

    @PostMapping("/{taskId}/pause")
    fun pause(
        @PathVariable taskId: Long,
        @RequestBody(required = false) request: TaskVersionRequest?,
    ): TaskStateResponse = response(
        taskStateService.pause(
            taskId = taskId,
            source = TaskEventSource.REST_API,
            expectedVersion = request?.expectedVersion,
        ),
    )

    @PostMapping("/{taskId}/resume")
    fun resume(
        @PathVariable taskId: Long,
        @RequestBody(required = false) request: TaskVersionRequest?,
    ): TaskStateResponse = response(
        taskStateService.resume(
            taskId = taskId,
            source = TaskEventSource.REST_API,
            expectedVersion = request?.expectedVersion,
        ),
    )

    @GetMapping("/{taskId}/state-history")
    fun stateHistory(@PathVariable taskId: Long): List<TaskStateHistoryResponse> =
        taskStateService.history(taskId).map(TaskStateHistoryResponse::from)

    private fun response(task: AgentTask): TaskStateResponse =
        TaskStateResponse.from(task, taskStateService.allowedEvents(task))
}
