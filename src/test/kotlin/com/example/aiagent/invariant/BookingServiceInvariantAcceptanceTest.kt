package com.example.aiagent.invariant

import com.example.aiagent.Application
import com.example.aiagent.agent.Agent
import com.example.aiagent.agent.AgentRequest
import com.example.aiagent.agent.ChatMessage
import com.example.aiagent.agent.Role
import com.example.aiagent.context.strategy.ContextStrategyType
import com.example.aiagent.llm.LlmClient
import com.example.aiagent.llm.LlmClientResolver
import com.example.aiagent.llm.LlmProvider
import com.example.aiagent.llm.LlmRequest
import com.example.aiagent.llm.LlmResponse
import com.example.aiagent.llm.TokenUsage
import com.example.aiagent.profile.ExpertiseLevel
import com.example.aiagent.profile.ResponseFormat
import com.example.aiagent.profile.ResponseLanguage
import com.example.aiagent.profile.ResponseStyle
import com.example.aiagent.profile.UserProfileInput
import com.example.aiagent.profile.UserProfileService
import com.example.aiagent.task.TaskService
import com.example.aiagent.task.TaskStateService
import com.example.aiagent.task.InvalidTaskStateException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@SpringBootTest(
    classes = [Application::class, BookingServiceInvariantAcceptanceTest.FakeLlmConfiguration::class],
)
class BookingServiceInvariantAcceptanceTest {
    @Autowired
    private lateinit var agent: Agent

    @Autowired
    private lateinit var taskService: TaskService

    @Autowired
    private lateinit var taskStateService: TaskStateService

    @Autowired
    private lateinit var invariantService: TaskInvariantService

    @Autowired
    private lateinit var profileService: UserProfileService

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var fakeLlm: AcceptanceLlmResolver

    @Test
    fun `Booking Service invariants guard allowed blocked correction management and lifecycle flows`() {
        val booking = taskService.create("Booking Service")
        agent.activateTask(booking.id)
        val architecture = create(booking.id, InvariantType.ARCHITECTURE, "architecture", "hexagonal")
        val backend = create(booking.id, InvariantType.STACK_CONSTRAINT, "backend_language", "Kotlin")
        val database = create(booking.id, InvariantType.TECHNICAL_DECISION, "database", "PostgreSQL")
        val duration = create(booking.id, InvariantType.BUSINESS_RULE, "maximum_booking_duration", "4 hours")
        val overlap = create(booking.id, InvariantType.BUSINESS_RULE, "overlapping_bookings", "forbidden")
        val bookingSnapshot = listOf(architecture, backend, database, duration, overlap)

        assertEquals(5, invariantService.list(booking.id).size)
        assertEquals(5, invariantService.snapshot(taskService.activeTask()).invariants.size)

        val allowed = send("Спроектируй REST endpoint создания бронирования с учётом текущих ограничений.")
        assertTrue(allowed.content.contains("Kotlin"))
        assertTrue(allowed.content.contains("PostgreSQL"))
        assertTrue(allowed.content.contains("hexagonal"))
        assertTrue(allowed.content.contains("4 hours"))
        assertTrue(allowed.content.contains("overlapping"))
        assertEquals(InvariantCheckDecision.ALLOWED, invariantService.lastCheck(booking.id)?.result)
        assertEquals(InvariantCheckOutcome.DELIVERED, invariantService.lastCheck(booking.id)?.outcome)
        assertEquals(5, fakeLlm.lastMainRequest.get().messages.single { it.content.startsWith("TASK INVARIANTS") }
            .content.lines().count { it.startsWith("[") })
        assertTrue(agent.memory(ContextStrategyType.SLIDING_WINDOW).working.isNotEmpty())
        assertTrue(fakeLlm.taskAnalyzerCalls.get() > 0)
        assertTrue(fakeLlm.memoryExtractorCalls.get() > 0)

        listOf(
            "Объясни различия Kotlin и Java без изменения текущего проекта.",
            "Чем MongoDB отличается от PostgreSQL в общем случае?",
        ).forEach { educational ->
            val response = send(educational)
            assertFalse(response.content.contains("конфликтует с обязательным ограничением"))
            assertEquals(InvariantCheckDecision.ALLOWED, invariantService.lastCheck(booking.id)?.result)
        }

        val blockedRequests = listOf(
            "Перепиши backend текущей Task на Java." to backend,
            "Замени PostgreSQL на MongoDB." to database,
            "Перейдём с hexagonal architecture на layered architecture." to architecture,
            "Разреши бронирования длительностью 8 часов." to duration,
            "Разреши пересекающиеся бронирования для VIP-клиентов." to overlap,
        )
        blockedRequests.forEach { (request, invariant) ->
            val mainCallsBefore = fakeLlm.mainCalls.get()
            val analyzerCallsBefore = fakeLlm.taskAnalyzerCalls.get()
            val memoryCallsBefore = fakeLlm.memoryExtractorCalls.get()
            val taskBefore = taskService.activeTask()
            val workingBefore = agent.memory(ContextStrategyType.SLIDING_WINDOW).working

            val response = send(request)

            assertTrue(response.content.contains("${invariant.key} = ${invariant.value}"))
            assertEquals(mainCallsBefore, fakeLlm.mainCalls.get())
            assertEquals(analyzerCallsBefore, fakeLlm.taskAnalyzerCalls.get())
            assertEquals(memoryCallsBefore, fakeLlm.memoryExtractorCalls.get())
            assertEquals(taskBefore, taskService.activeTask())
            assertEquals(workingBefore, agent.memory(ContextStrategyType.SLIDING_WINDOW).working)
            val check = invariantService.lastCheck(booking.id)!!
            assertEquals(InvariantCheckDecision.BLOCKED, check.result)
            assertEquals(InvariantCheckDirection.INPUT, check.direction)
            assertEquals(invariant.id, check.violations.single().invariant.id)
            assertEquals(InvariantCheckOutcome.REFUSED, check.outcome)
        }

        invariantService.setEnabled(booking.id, backend.id, false)
        val javaAllowedMainCalls = fakeLlm.mainCalls.get()
        send("Перепиши backend текущей Task на Java.")
        assertEquals(javaAllowedMainCalls + 1, fakeLlm.mainCalls.get())
        invariantService.setEnabled(booking.id, backend.id, true)
        val javaBlockedMainCalls = fakeLlm.mainCalls.get()
        send("Перепиши backend текущей Task на Java.")
        assertEquals(javaBlockedMainCalls, fakeLlm.mainCalls.get())

        assertThrows(TaskInvariantConflictException::class.java) {
            create(booking.id, InvariantType.STACK_CONSTRAINT, "backend_language", "Java")
        }
        invariantService.setEnabled(booking.id, backend.id, false)
        val explicitJava = create(booking.id, InvariantType.STACK_CONSTRAINT, "backend_language", "Java")
        assertTrue(explicitJava.enabled)
        invariantService.delete(booking.id, explicitJava.id)
        invariantService.setEnabled(booking.id, backend.id, true)

        fakeLlm.mainMode.set(MainMode.VIOLATE_THEN_CORRECT)
        val correctionCallsBefore = fakeLlm.mainCalls.get()
        val corrected = send("Спроектируй repository для бронирований.")
        assertEquals(correctionCallsBefore + 2, fakeLlm.mainCalls.get())
        assertTrue(corrected.content.contains("PostgreSQL"))
        assertFalse(corrected.content.contains("MongoDB"))
        assertEquals(1, invariantService.lastCheck(booking.id)?.correctiveRetries)
        assertEquals(InvariantCheckStatus.OUTPUT_CORRECTED, invariantService.lastCheck(booking.id)?.status)
        assertTrue(agent.history().none { it.content == AcceptanceLlmResolver.VIOLATING_CANDIDATE })

        fakeLlm.mainMode.set(MainMode.ALWAYS_VIOLATE)
        val repeated = send("Снова спроектируй repository для бронирований.")
        assertTrue(repeated.content.contains("не удалось сформировать ответ", ignoreCase = true))
        assertEquals(InvariantCheckOutcome.REFUSED, invariantService.lastCheck(booking.id)?.outcome)
        assertEquals(1, invariantService.lastCheck(booking.id)?.correctiveRetries)
        assertTrue(agent.history().none { it.content == AcceptanceLlmResolver.VIOLATING_CANDIDATE })

        fakeLlm.mainMode.set(MainMode.NORMAL)
        fakeLlm.failNextGuard.set(true)
        val failedMainCalls = fakeLlm.mainCalls.get()
        val failed = send("Продолжи проектирование endpoint.")
        assertTrue(failed.content.contains("не удалось безопасно проверить", ignoreCase = true))
        assertEquals(failedMainCalls, fakeLlm.mainCalls.get())
        assertEquals(InvariantCheckStatus.CHECK_FAILED, invariantService.lastCheck(booking.id)?.status)
        assertEquals("INVALID_STRUCTURED_RESULT", invariantService.lastCheck(booking.id)?.errorCode)

        val purposes = jdbcTemplate.queryForList(
            "SELECT purpose FROM llm_request_usage WHERE task_id = ? ORDER BY id",
            String::class.java,
            booking.id,
        )
        assertTrue("MAIN_REQUEST" in purposes)
        assertTrue("INVARIANT_INPUT_GUARD" in purposes)
        assertTrue("INVARIANT_OUTPUT_GUARD" in purposes)
        assertTrue("INVARIANT_CORRECTIVE_RETRY" in purposes)
        assertTrue("TASK_PROGRESS_ANALYZER" in purposes)
        val persistedMainUsage = jdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(total_tokens), 0) FROM llm_request_usage WHERE task_id = ? AND purpose = 'MAIN_REQUEST'",
            Long::class.java,
            booking.id,
        )
        assertEquals(agent.state().conversationUsage.totalTokens, persistedMainUsage)
        agent.reset()
        assertEquals(bookingSnapshot.map { it.id }.toSet(), invariantService.list(booking.id).map { it.id }.toSet())
        agent.clearWorkingMemory()
        assertEquals(5, invariantService.list(booking.id).size)
        val profile = profileService.create(
            UserProfileInput(
                name = "Booking Developer",
                responseLanguage = ResponseLanguage.RUSSIAN,
                expertiseLevel = ExpertiseLevel.ADVANCED,
                responseStyle = ResponseStyle.CONCISE,
                responseFormat = ResponseFormat.STRUCTURED,
                customInstructions = "Prefer examples.",
            ),
        )
        profileService.activate(profile.id)
        assertEquals(5, invariantService.list(booking.id).size)

        taskStateService.pause(booking.id)
        assertThrows(InvalidTaskStateException::class.java) {
            create(booking.id, InvariantType.OTHER, "paused_change", "forbidden")
        }
        assertEquals(5, invariantService.list(booking.id).size)
        taskStateService.resume(booking.id)
        assertEquals(5, invariantService.list(booking.id).size)

        val other = taskService.create("Payments Service")
        create(other.id, InvariantType.TECHNICAL_DECISION, "database", "SQLite")
        agent.activateTask(other.id)
        assertEquals(listOf("SQLite"), invariantService.findEnabled(other.id).map { it.value })
        agent.activateTask(booking.id)
        assertEquals(5, invariantService.findEnabled(booking.id).size)

    }

    private fun create(taskId: Long, type: InvariantType, key: String, value: String) =
        invariantService.create(
            taskId,
            CreateTaskInvariant(
                type = type,
                key = key,
                value = value,
                description = "Mandatory Booking Service constraint",
                enabled = true,
            ),
        )

    private fun send(message: String) = agent.sendMessage(
        AgentRequest(
            message = message,
            provider = LlmProvider.OPENAI,
            model = "main-test",
            contextStrategy = ContextStrategyType.SLIDING_WINDOW,
        ),
    )

    @TestConfiguration
    class FakeLlmConfiguration {
        @Bean
        @Primary
        fun acceptanceLlmResolver(): AcceptanceLlmResolver = AcceptanceLlmResolver()
    }

    enum class MainMode {
        NORMAL,
        VIOLATE_THEN_CORRECT,
        ALWAYS_VIOLATE,
    }

    class AcceptanceLlmResolver : LlmClientResolver {
        val mainMode = AtomicReference(MainMode.NORMAL)
        val failNextGuard = AtomicBoolean(false)
        val mainCalls = AtomicInteger()
        val taskAnalyzerCalls = AtomicInteger()
        val memoryExtractorCalls = AtomicInteger()
        val lastMainRequest = AtomicReference<LlmRequest>()

        private val clients = LlmProvider.entries.associateWith { provider ->
            object : LlmClient {
                override val provider = provider
                override val defaultModel = "main-test"

                override fun chat(request: LlmRequest): LlmResponse = respond(request)
            }
        }

        override fun resolve(provider: LlmProvider): LlmClient = checkNotNull(clients[provider])

        override fun availableClients(): List<LlmClient> = LlmProvider.entries.map(::resolve)

        private fun respond(request: LlmRequest): LlmResponse = when (request.model) {
            "guard-test" -> guardResponse(request)
            "state-test" -> {
                taskAnalyzerCalls.incrementAndGet()
                response(
                    request,
                    """{"currentStep":"Design booking endpoint","expectedActionType":"AGENT_ACTION","expectedActionDescription":"Implement endpoint","proposedEvent":null}""",
                )
            }
            "memory-test" -> {
                memoryExtractorCalls.incrementAndGet()
                response(
                    request,
                    """{"working":{"upsert":[{"key":"last_request","value":"booking design"}],"delete":[]},"longTerm":{"upsert":[],"delete":[]}}""",
                )
            }
            "main-test" -> mainResponse(request)
            else -> error("Unexpected fake model ${request.model}")
        }

        private fun mainResponse(request: LlmRequest): LlmResponse {
            mainCalls.incrementAndGet()
            lastMainRequest.set(request)
            val correction = request.messages.any {
                it.role == Role.SYSTEM && it.content.contains("previous candidate response violated")
            }
            val content = when (mainMode.get()) {
                MainMode.NORMAL -> COMPLIANT_RESPONSE
                MainMode.VIOLATE_THEN_CORRECT -> if (correction) COMPLIANT_RESPONSE else VIOLATING_CANDIDATE
                MainMode.ALWAYS_VIOLATE -> VIOLATING_CANDIDATE
            }
            return response(request, content)
        }

        private fun guardResponse(request: LlmRequest): LlmResponse {
            if (failNextGuard.compareAndSet(true, false)) {
                return response(request, "not-json")
            }
            val prompt = request.messages.last().content
            val direction = if (prompt.contains("Direction: INPUT")) "INPUT" else "OUTPUT"
            val key = if (direction == "INPUT") blockedInputKey(prompt) else blockedOutputKey(prompt)
            val invariantId = key?.let { invariantId(prompt, it) }
            val json = if (invariantId == null) {
                """{"decision":"ALLOWED","direction":"$direction","violations":[]}"""
            } else {
                """{"decision":"BLOCKED","direction":"$direction","violations":[{"invariantId":$invariantId,"reason":"The request or candidate conflicts with $key for the active Task."}]}"""
            }
            return response(request, json)
        }

        private fun blockedInputKey(prompt: String): String? = when {
            prompt.contains("Перепиши backend текущей Task на Java") -> "backend_language"
            prompt.contains("Замени PostgreSQL на MongoDB") -> "database"
            prompt.contains("Перейдём с hexagonal architecture на layered architecture") -> "architecture"
            prompt.contains("Разреши бронирования длительностью 8 часов") -> "maximum_booking_duration"
            prompt.contains("Разреши пересекающиеся бронирования для VIP-клиентов") -> "overlapping_bookings"
            else -> null
        }

        private fun blockedOutputKey(prompt: String): String? =
            if (prompt.contains(VIOLATING_CANDIDATE)) "database" else null

        private fun invariantId(prompt: String, key: String): Long? =
            Regex("""- id=(\d+);[^\n]*key=\"${Regex.escape(key)}\"""")
                .find(prompt)
                ?.groupValues
                ?.get(1)
                ?.toLong()

        private fun response(request: LlmRequest, content: String) = LlmResponse(
            content = content,
            model = request.model,
            usage = TokenUsage(10, 5, 15),
        )

        companion object {
            const val VIOLATING_CANDIDATE = "Use MongoDB as primary database."
            const val COMPLIANT_RESPONSE =
                "Use Kotlin with hexagonal boundaries and PostgreSQL. Validate maximum duration of 4 hours and reject overlapping bookings."
        }
    }

    companion object {
        private val database = Files.createTempFile("booking-invariant-acceptance-", ".db")

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("storage.database-path") { database.toString() }
            registry.add("invariants.guard.provider") { "OPENAI" }
            registry.add("invariants.guard.model") { "guard-test" }
            registry.add("invariants.guard.max-corrective-retries") { "1" }
            registry.add("task.state.analyzer.enabled") { "true" }
            registry.add("task.state.analyzer.provider") { "OPENAI" }
            registry.add("task.state.analyzer.model") { "state-test" }
            registry.add("memory.enabled") { "true" }
            registry.add("memory.extractor.provider") { "OPENAI" }
            registry.add("memory.extractor.model") { "memory-test" }
            registry.add("context.compression.enabled") { "false" }
        }
    }
}
