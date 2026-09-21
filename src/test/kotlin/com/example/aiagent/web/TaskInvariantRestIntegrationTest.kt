package com.example.aiagent.web

import com.example.aiagent.invariant.InvariantCheckDecision
import com.example.aiagent.invariant.InvariantCheckDirection
import com.example.aiagent.invariant.InvariantCheckOutcome
import com.example.aiagent.invariant.InvariantCheckStatus
import com.example.aiagent.invariant.InvariantType
import com.example.aiagent.invariant.LastInvariantCheck
import com.example.aiagent.invariant.TaskInvariantService
import com.example.aiagent.invariant.TaskInvariantSnapshot
import com.example.aiagent.llm.LlmProvider
import com.example.aiagent.llm.TokenUsage
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
import java.time.Instant

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TaskInvariantRestIntegrationTest {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var taskService: TaskService

    @Autowired
    private lateinit var invariantService: TaskInvariantService

    @Autowired
    private lateinit var jsonMapper: ObjectMapper

    private val client = HttpClient.newHttpClient()

    @Test
    fun `REST CRUD validation conflicts isolation and last check use the persistent service`() {
        val task = taskService.create("REST Invariants ${System.nanoTime()}")
        val other = taskService.create("Other REST Task ${System.nanoTime()}")
        val base = "/api/tasks/${task.id}/invariants"

        val createdResponse = request(
            "POST",
            base,
            """{"type":"STACK_CONSTRAINT","key":" Backend_Language ","value":" Kotlin ","description":"Required backend","enabled":true}""",
        )
        assertEquals(200, createdResponse.statusCode())
        val created = objectBody(createdResponse)
        val invariantId = (created["id"] as Number).toLong()
        assertEquals("backend_language", created["key"])
        assertEquals("Kotlin", created["value"])

        val listResponse = request("GET", base)
        assertEquals(200, listResponse.statusCode())
        assertTrue(listResponse.body().contains("backend_language"))

        val conflictResponse = request(
            "POST",
            base,
            """{"type":"STACK_CONSTRAINT","key":"backend_language","value":"Java","enabled":true}""",
        )
        assertEquals(409, conflictResponse.statusCode())
        assertTrue(objectBody(conflictResponse)["message"].toString().contains("already exists"))
        assertFalse(conflictResponse.body().contains("SQLITE"))

        val invalidResponse = request(
            "POST",
            base,
            """{"type":"OTHER","key":" ","value":"value","enabled":true}""",
        )
        assertEquals(400, invalidResponse.statusCode())

        val isolatedResponse = request("GET", "/api/tasks/${other.id}/invariants/$invariantId")
        assertEquals(404, isolatedResponse.statusCode())

        val updateResponse = request(
            "PUT",
            "$base/$invariantId",
            """{"type":"TECHNICAL_DECISION","key":"database","value":"PostgreSQL","description":null,"enabled":true}""",
        )
        assertEquals(200, updateResponse.statusCode())
        assertEquals("database", objectBody(updateResponse)["key"])

        val disableResponse = request(
            "PATCH",
            "$base/$invariantId/enabled",
            """{"enabled":false}""",
        )
        assertEquals(200, disableResponse.statusCode())
        assertEquals(false, objectBody(disableResponse)["enabled"])

        val snapshot = TaskInvariantSnapshot(
            id = invariantId,
            taskId = task.id,
            taskName = task.name,
            type = InvariantType.TECHNICAL_DECISION,
            key = "database",
            value = "PostgreSQL",
            description = null,
        )
        invariantService.recordCheck(
            LastInvariantCheck(
                checkId = "check-rest",
                taskId = task.id,
                taskName = task.name,
                direction = InvariantCheckDirection.INPUT,
                result = InvariantCheckDecision.ALLOWED,
                requestExcerpt = "Continue",
                invariantSnapshot = listOf(snapshot),
                violations = emptyList(),
                correctiveRetries = 0,
                outcome = InvariantCheckOutcome.NOT_SENT,
                status = InvariantCheckStatus.OK,
                errorCode = null,
                provider = LlmProvider.OPENAI,
                model = "guard-test",
                usage = TokenUsage(8, 2, 10),
                responseTimeMs = 12,
                checkedAt = Instant.parse("2026-01-01T00:00:00Z"),
            ),
        )
        val lastCheckResponse = request("GET", "/api/tasks/${task.id}/invariant-checks/last")
        assertEquals(200, lastCheckResponse.statusCode())
        val lastCheck = objectBody(lastCheckResponse)
        assertEquals("ALLOWED", lastCheck["result"])
        assertEquals("NOT_SENT", lastCheck["outcome"])
        assertTrue(lastCheckResponse.body().contains("PostgreSQL"))

        assertEquals(204, request("DELETE", "$base/$invariantId").statusCode())
        assertEquals(404, request("GET", "$base/$invariantId").statusCode())
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

    companion object {
        private val database = Files.createTempFile("task-invariant-rest-", ".db")

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
