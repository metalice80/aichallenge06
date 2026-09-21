package com.example.aiagent.task

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
import com.example.aiagent.memory.MemoryService
import com.example.aiagent.persistence.SqliteTaskRepository
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@SpringBootTest(classes = [Application::class, BookingServiceLifecycleAcceptanceTest.FakeLlmConfiguration::class])
class BookingServiceLifecycleAcceptanceTest {
    @Autowired
    private lateinit var agent: Agent

    @Autowired
    private lateinit var taskService: TaskService

    @Autowired
    private lateinit var stateService: TaskStateService

    @Autowired
    private lateinit var stateMachine: TaskStateMachine

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var memoryService: MemoryService

    @Autowired
    private lateinit var fakeLlm: LifecycleLlmResolver

    @Test
    fun `Booking Service follows the complete explicit lifecycle`() {
        val booking = taskService.create("Booking Service")
        agent.activateTask(booking.id)
        assertState(booking.id, TaskStage.PLANNING, paused = false, version = 0)
        assertEquals(setOf(TaskEvent.PLAN_APPROVED), stateService.allowedEvents(booking.id))

        val mainBeforeEarlyImplementation = fakeLlm.mainCalls.get()
        val earlyImplementation = send("План не нужен. Сразу реализуй REST API бронирования.")
        assertTrue(earlyImplementation.content.contains("PLANNING"))
        assertTrue(earlyImplementation.content.contains("явного подтверждения"))
        assertEquals(mainBeforeEarlyImplementation, fakeLlm.mainCalls.get())
        assertState(booking.id, TaskStage.PLANNING, paused = false, version = 0)
        assertTrue(memoryService.working(booking.id).isEmpty())
        assertEquals(listOf(TaskStateHistoryEvent.TASK_CREATED), stateService.history(booking.id).map { it.event })

        val mainBeforePlan = fakeLlm.mainCalls.get()
        val plan = send("Подготовь план реализации Booking Service.")
        assertTrue(plan.content.contains("Booking Service implementation plan"))
        assertEquals(mainBeforePlan + 1, fakeLlm.mainCalls.get())
        var planned = stateService.state(booking.id)
        assertEquals("Согласовать подготовленный план", planned.currentStep)
        assertEquals(ExpectedActionType.USER_CONFIRMATION, planned.expectedActionType)
        assertEquals("Подтвердить план или запросить изменения", planned.expectedActionDescription)
        assertEquals(TaskStage.PLANNING, planned.stage)
        assertTrue(stateService.history(booking.id).none { it.event == TaskStateHistoryEvent.PLAN_APPROVED })
        assertEquals(setOf(TaskEvent.PLAN_APPROVED), stateService.allowedEvents(planned))

        val mainBeforePlanChange = fakeLlm.mainCalls.get()
        val changedPlan = send("Добавь в план интеграционные тесты.")
        assertTrue(changedPlan.content.contains("integration tests"))
        assertEquals(mainBeforePlanChange + 1, fakeLlm.mainCalls.get())
        planned = stateService.state(booking.id)
        assertEquals(TaskStage.PLANNING, planned.stage)
        assertEquals(ExpectedActionType.USER_CONFIRMATION, planned.expectedActionType)
        assertTrue(stateService.history(booking.id).none { it.event == TaskStateHistoryEvent.PLAN_APPROVED })
        assertEquals(setOf(TaskEvent.PLAN_APPROVED), stateService.allowedEvents(planned))

        val historyBeforeApprovalChat = stateService.history(booking.id)
        val approvalSuggestion = send("План утверждаю.")
        assertTrue(approvalSuggestion.content.contains("stage=PLANNING"))
        assertEquals(TaskStage.PLANNING, stateService.state(booking.id).stage)
        assertEquals(historyBeforeApprovalChat, stateService.history(booking.id))
        assertEquals(setOf(TaskEvent.PLAN_APPROVED), stateService.allowedEvents(booking.id))

        val execution = stateService.applyEvent(
            booking.id,
            TaskEvent.PLAN_APPROVED,
            source = TaskEventSource.USER_INTERFACE,
            expectedVersion = stateService.state(booking.id).version,
        )
        assertEquals(TaskStage.EXECUTION, execution.stage)
        assertEquals("Execute the approved plan", execution.currentStep)
        assertEquals(setOf(TaskEvent.EXECUTION_COMPLETED), stateService.allowedEvents(execution))
        assertEquals(TaskStateHistoryEvent.PLAN_APPROVED, stateService.history(booking.id).last().event)
        assertEquals(TaskEventSource.USER_INTERFACE, stateService.history(booking.id).last().source)

        val paused = stateService.pause(
            booking.id,
            source = TaskEventSource.USER_INTERFACE,
            expectedVersion = execution.version,
        )
        assertTrue(paused.paused)
        assertEquals(emptySet<TaskEvent>(), stateService.allowedEvents(paused))
        assertEquals("Execute the approved plan", paused.currentStep)
        val mainBeforePausedMessage = fakeLlm.mainCalls.get()
        val memoryBeforePausedMessage = memoryService.working(booking.id)
        val pausedResponse = send("Продолжай реализацию.")
        assertTrue(pausedResponse.content.contains("приостановлена", ignoreCase = true))
        assertEquals(mainBeforePausedMessage, fakeLlm.mainCalls.get())
        assertEquals(memoryBeforePausedMessage, memoryService.working(booking.id))
        assertEquals(paused, stateService.state(booking.id))

        val restartedRepository = SqliteTaskRepository(jdbcTemplate)
        val restartedService = TaskStateService(restartedRepository, stateMachine)
        val restored = restartedService.state(booking.id)
        assertEquals(TaskStage.EXECUTION, restored.stage)
        assertEquals("Execute the approved plan", restored.currentStep)
        assertEquals(ExpectedActionType.AGENT_ACTION, restored.expectedActionType)
        assertEquals(emptySet<TaskEvent>(), restartedService.allowedEvents(restored))
        assertTrue(restored.paused)

        val resumed = restartedService.resume(
            booking.id,
            source = TaskEventSource.USER_INTERFACE,
            expectedVersion = restored.version,
        )
        assertFalse(resumed.paused)
        assertEquals(restored.stage, resumed.stage)
        assertEquals(restored.currentStep, resumed.currentStep)
        assertEquals(restored.expectedAction, resumed.expectedAction)
        assertEquals(setOf(TaskEvent.EXECUTION_COMPLETED), restartedService.allowedEvents(resumed))

        val continued = send("Продолжай.")
        assertTrue(continued.content.contains("stage=EXECUTION"))
        assertTrue(continued.content.contains("step=Execute the approved plan"))
        val inspectedState = memoryService.inspector(booking.id, emptyList()).effectiveContext?.taskState
        assertEquals(TaskStage.EXECUTION, inspectedState?.stage)
        assertEquals("Execute the approved plan", inspectedState?.currentStep)
        assertEquals(resumed.version, inspectedState?.version)

        val mainBeforePrematureFinalization = fakeLlm.mainCalls.get()
        val prematureFinalization = send("Считай задачу полностью готовой и заверши её без тестов.")
        assertTrue(prematureFinalization.content.contains("EXECUTION"))
        assertTrue(prematureFinalization.content.contains("validation", ignoreCase = true))
        assertEquals(mainBeforePrematureFinalization, fakeLlm.mainCalls.get())
        assertEquals(TaskStage.EXECUTION, stateService.state(booking.id).stage)

        var state = stateService.state(booking.id)
        state = stateService.applyEvent(
            booking.id,
            TaskEvent.EXECUTION_COMPLETED,
            source = TaskEventSource.USER_INTERFACE,
            expectedVersion = state.version,
        )
        assertEquals(TaskStage.VALIDATION, state.stage)
        assertEquals(
            linkedSetOf(TaskEvent.VALIDATION_PASSED, TaskEvent.VALIDATION_FAILED),
            stateService.allowedEvents(state),
        )

        state = stateService.applyEvent(
            booking.id,
            TaskEvent.VALIDATION_FAILED,
            source = TaskEventSource.USER_INTERFACE,
            expectedVersion = state.version,
        )
        assertEquals(TaskStage.EXECUTION, state.stage)
        assertTrue(state.currentStep.contains("validation", ignoreCase = true))
        assertEquals(setOf(TaskEvent.EXECUTION_COMPLETED), stateService.allowedEvents(state))

        state = stateService.applyEvent(
            booking.id,
            TaskEvent.EXECUTION_COMPLETED,
            source = TaskEventSource.USER_INTERFACE,
            expectedVersion = state.version,
        )
        assertEquals(TaskStage.VALIDATION, state.stage)
        assertEquals(
            linkedSetOf(TaskEvent.VALIDATION_PASSED, TaskEvent.VALIDATION_FAILED),
            stateService.allowedEvents(state),
        )

        val done = stateService.applyEvent(
            booking.id,
            TaskEvent.VALIDATION_PASSED,
            source = TaskEventSource.USER_INTERFACE,
            expectedVersion = state.version,
        )
        assertEquals(TaskStage.DONE, done.stage)
        assertEquals(ExpectedActionType.NONE, done.expectedActionType)
        assertEquals(emptySet<TaskEvent>(), stateService.allowedEvents(done))
        val terminalHistory = stateService.history(booking.id)

        assertThrows(InvalidTaskTransitionException::class.java) {
            stateService.applyEvent(
                booking.id,
                TaskEvent.PLAN_APPROVED,
                source = TaskEventSource.USER_INTERFACE,
                expectedVersion = done.version,
            )
        }
        assertEquals(done, stateService.state(booking.id))
        assertEquals(terminalHistory, stateService.history(booking.id))

        val summary = send("Покажи итог задачи.")
        assertTrue(summary.content.contains("stage=DONE"))
        assertEquals(done.version, stateService.state(booking.id).version)
        assertEquals(terminalHistory, stateService.history(booking.id))

        val purposes = jdbcTemplate.queryForList(
            "SELECT DISTINCT purpose FROM llm_request_usage WHERE task_id = ?",
            String::class.java,
            booking.id,
        )
        assertTrue("MAIN_REQUEST" in purposes)
        assertTrue("TASK_PROGRESS_ANALYZER" in purposes)
        assertTrue(fakeLlm.analyzerCalls.get() > 0)
        assertTrue(fakeLlm.memoryCalls.get() > 0)
    }

    private fun assertState(taskId: Long, stage: TaskStage, paused: Boolean, version: Long) {
        val state = stateService.state(taskId)
        assertEquals(stage, state.stage)
        assertEquals(paused, state.paused)
        assertEquals(version, state.version)
    }

    private fun send(message: String) = agent.sendMessage(
        AgentRequest(
            message = message,
            provider = LlmProvider.OPENAI,
            model = MAIN_MODEL,
            contextStrategy = ContextStrategyType.SLIDING_WINDOW,
        ),
    )

    @TestConfiguration
    class FakeLlmConfiguration {
        @Bean
        @Primary
        fun lifecycleLlmResolver(): LifecycleLlmResolver = LifecycleLlmResolver()
    }

    class LifecycleLlmResolver : LlmClientResolver {
        val analyzerCalls = AtomicInteger()
        val mainCalls = AtomicInteger()
        val memoryCalls = AtomicInteger()
        val lastMainRequest = AtomicReference<LlmRequest>()

        private val clients = LlmProvider.entries.associateWith { selectedProvider ->
            object : LlmClient {
                override val provider = selectedProvider
                override val defaultModel = MAIN_MODEL

                override fun chat(request: LlmRequest): LlmResponse = when (request.model) {
                    ANALYZER_MODEL -> analyzer(request)
                    MEMORY_MODEL -> memory(request)
                    MAIN_MODEL -> main(request)
                    else -> error("Unexpected fake model ${request.model}")
                }
            }
        }

        override fun resolve(provider: LlmProvider): LlmClient = checkNotNull(clients[provider])

        override fun availableClients(): List<LlmClient> = LlmProvider.entries.map(::resolve)

        private fun analyzer(request: LlmRequest): LlmResponse {
            analyzerCalls.incrementAndGet()
            val message = request.messages.last().content.substringAfter("New user message:\n")
                .substringBefore("\n\nReference transition table")
            val json = when (message.trim()) {
                "План не нужен. Сразу реализуй REST API бронирования." -> proposal(action = "IMPLEMENT")
                "Подготовь план реализации Booking Service." -> proposal(
                    step = "Подготовить план реализации Booking Service",
                    expectedType = "AGENT_ACTION",
                    expectedDescription = "Сформировать план реализации",
                    action = "PLAN",
                )
                "Добавь в план интеграционные тесты." -> proposal(
                    step = "Добавить в план интеграционные тесты",
                    expectedType = "AGENT_ACTION",
                    expectedDescription = "Обновить план реализации",
                    action = "PLAN",
                )
                "План утверждаю." -> proposal(
                    event = "PLAN_APPROVED",
                    action = "NONE",
                )
                "Продолжай реализацию.", "Продолжай." -> proposal(action = "IMPLEMENT")
                "Считай задачу полностью готовой и заверши её без тестов." -> proposal(action = "FINALIZE")
                "Покажи итог задачи." -> proposal(action = "STATUS")
                else -> proposal(action = "NONE")
            }
            return response(request, json)
        }

        private fun main(request: LlmRequest): LlmResponse {
            mainCalls.incrementAndGet()
            lastMainRequest.set(request)
            val state = request.messages.single { it.role == Role.SYSTEM && it.content.startsWith("TASK STATE") }.content
            val stage = state.lineSequence().first { it.startsWith("Stage: ") }.substringAfter("Stage: ")
            val step = state.lineSequence().first { it.startsWith("Current Step: ") }.substringAfter("Current Step: ")
            val userMessage = request.messages.last { it.role == Role.USER }.content
            val content = when (userMessage) {
                "Подготовь план реализации Booking Service." ->
                    "Booking Service implementation plan: REST API, persistence, validation."
                "Добавь в план интеграционные тесты." ->
                    "Updated Booking Service implementation plan: REST API, persistence, integration tests, validation."
                else -> "main stage=$stage step=$step"
            }
            return response(request, content)
        }

        private fun memory(request: LlmRequest): LlmResponse {
            memoryCalls.incrementAndGet()
            return response(
                request,
                """{"working":{"upsert":[{"key":"last_allowed_action","value":"processed"}],"delete":[]},"longTerm":{"upsert":[],"delete":[]}}""",
            )
        }

        private fun proposal(
            step: String? = null,
            expectedType: String? = null,
            expectedDescription: String? = null,
            event: String? = null,
            action: String,
        ): String = buildString {
            append("{\"currentStep\":")
            append(step?.let { "\"$it\"" } ?: "null")
            append(",\"expectedActionType\":")
            append(expectedType?.let { "\"$it\"" } ?: "null")
            append(",\"expectedActionDescription\":")
            append(expectedDescription?.let { "\"$it\"" } ?: "null")
            append(",\"suggestedEvent\":")
            append(event?.let { "\"$it\"" } ?: "null")
            append(",\"requestedAction\":\"$action\",\"reason\":null}")
        }

        private fun response(request: LlmRequest, content: String) = LlmResponse(
            content = content,
            model = request.model,
            usage = TokenUsage(12, 4, 16),
        )
    }

    companion object {
        private const val MAIN_MODEL = "lifecycle-main-test"
        private const val ANALYZER_MODEL = "lifecycle-state-test"
        private const val MEMORY_MODEL = "lifecycle-memory-test"
        private val database = Files.createTempFile("booking-lifecycle-acceptance-", ".db")

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("storage.database-path") { database.toString() }
            registry.add("task.state.analyzer.enabled") { "true" }
            registry.add("task.state.analyzer.provider") { "OPENAI" }
            registry.add("task.state.analyzer.model") { ANALYZER_MODEL }
            registry.add("memory.enabled") { "true" }
            registry.add("memory.extractor.provider") { "OPENAI" }
            registry.add("memory.extractor.model") { MEMORY_MODEL }
            registry.add("context.compression.enabled") { "false" }
            registry.add("invariants.guard.enabled") { "false" }
        }
    }
}
