package com.example.aiagent.web

import com.example.aiagent.task.TaskService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TaskLifecycleRestIntegrationTest {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var taskService: TaskService

    @Autowired
    private lateinit var jsonMapper: ObjectMapper

    private val client = HttpClient.newHttpClient()

    @Test
    fun `manual lifecycle API returns backend state conflicts and ordered history`() {
        val task = taskService.create("Lifecycle REST ${System.nanoTime()}")
        val base = "/api/tasks/${task.id}"

        val initial = objectBody(request("GET", "$base/state"))
        assertEquals("PLANNING", initial["stage"])
        assertEquals(0, (initial["version"] as Number).toInt())
        assertEquals("USER_INPUT", nested(initial, "expectedAction")["type"])
        assertEquals(listOf("PLAN_APPROVED"), initial["allowedEvents"])

        val targetStage = request(
            "POST",
            "$base/events",
            """{"event":"PLAN_APPROVED","targetStage":"DONE","expectedVersion":0}""",
        )
        assertEquals(400, targetStage.statusCode())
        assertEquals("PLANNING", objectBody(request("GET", "$base/state"))["stage"])

        val invalidEvent = request(
            "POST",
            "$base/events",
            """{"event":"VALIDATION_PASSED","expectedVersion":0}""",
        )
        assertEquals(409, invalidEvent.statusCode())
        val invalidError = objectBody(invalidEvent)
        assertEquals("INVALID_TASK_TRANSITION", invalidError["code"])
        assertEquals("PLANNING", invalidError["currentStage"])
        assertEquals("VALIDATION_PASSED", invalidError["event"])
        assertEquals(listOf("PLAN_APPROVED"), invalidError["allowedEvents"])
        val unchanged = objectBody(request("GET", "$base/state"))
        assertEquals("PLANNING", unchanged["stage"])
        assertEquals(initial["currentStep"], unchanged["currentStep"])
        assertEquals(initial["expectedAction"], unchanged["expectedAction"])

        val approved = objectBody(
            request("POST", "$base/events", """{"event":"PLAN_APPROVED","expectedVersion":0}"""),
        )
        assertEquals("EXECUTION", approved["stage"])
        assertEquals(1, (approved["version"] as Number).toInt())
        assertEquals("AGENT_ACTION", nested(approved, "expectedAction")["type"])
        assertEquals(listOf("EXECUTION_COMPLETED"), approved["allowedEvents"])

        val duplicate = request(
            "POST",
            "$base/events",
            """{"event":"PLAN_APPROVED","expectedVersion":1}""",
        )
        assertEquals(409, duplicate.statusCode())
        assertEquals("INVALID_TASK_TRANSITION", objectBody(duplicate)["code"])
        assertEquals(listOf("EXECUTION_COMPLETED"), objectBody(duplicate)["allowedEvents"])

        val stalePause = request("POST", "$base/pause", """{"expectedVersion":0}""")
        assertEquals(409, stalePause.statusCode())
        assertEquals("TASK_VERSION_CONFLICT", objectBody(stalePause)["code"])

        val paused = objectBody(request("POST", "$base/pause", """{"expectedVersion":1}"""))
        assertEquals(true, paused["paused"])
        assertEquals("EXECUTION", paused["stage"])
        assertEquals(2, (paused["version"] as Number).toInt())
        assertEquals(emptyList<String>(), paused["allowedEvents"])

        val pausedEvent = request(
            "POST",
            "$base/events",
            """{"event":"EXECUTION_COMPLETED","expectedVersion":2}""",
        )
        assertEquals(409, pausedEvent.statusCode())
        assertEquals("TASK_PAUSED", objectBody(pausedEvent)["code"])

        val resumed = objectBody(request("POST", "$base/resume", """{"expectedVersion":2}"""))
        assertEquals(false, resumed["paused"])
        assertEquals("EXECUTION", resumed["stage"])
        assertEquals(3, (resumed["version"] as Number).toInt())
        assertEquals(listOf("EXECUTION_COMPLETED"), resumed["allowedEvents"])

        val historyResponse = request("GET", "$base/state-history")
        assertEquals(200, historyResponse.statusCode())
        val history = arrayBody(historyResponse)
        assertEquals(listOf("TASK_CREATED", "PLAN_APPROVED", "PAUSE", "RESUME"), history.map { it["event"] })
        assertEquals(listOf(0, 1, 2, 3), history.map { (it["version"] as Number).toInt() })
        assertEquals("REST_API", history[1]["source"])
        assertTrue(history.zipWithNext().all { (left, right) ->
            (left["id"] as Number).toLong() < (right["id"] as Number).toLong()
        })
        assertFalse(historyResponse.body().contains("targetStage"))
    }

    private fun request(method: String, path: String, body: String? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
            .header("Accept", "application/json")
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody())
        } else {
            builder.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body))
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    @Suppress("UNCHECKED_CAST")
    private fun objectBody(response: HttpResponse<String>): Map<String, Any?> =
        jsonMapper.readValue(response.body(), Map::class.java) as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun arrayBody(response: HttpResponse<String>): List<Map<String, Any?>> =
        jsonMapper.readValue(response.body(), List::class.java) as List<Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    private fun nested(body: Map<String, Any?>, key: String): Map<String, Any?> =
        body[key] as Map<String, Any?>

    companion object {
        private val database = Files.createTempFile("task-lifecycle-rest-", ".db")

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("storage.database-path") { database.toString() }
            registry.add("task.state.analyzer.enabled") { "false" }
            registry.add("memory.enabled") { "false" }
            registry.add("context.compression.enabled") { "false" }
        }
    }
}
