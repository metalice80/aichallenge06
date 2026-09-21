package com.example.aiagent.persistence

import com.example.aiagent.agent.ChatMessage
import com.example.aiagent.agent.Conversation
import com.example.aiagent.agent.Role
import com.example.aiagent.context.ContextStateService
import com.example.aiagent.context.branch.ConversationBranchService
import com.example.aiagent.config.TaskProgressAnalyzerProperties
import com.example.aiagent.config.TaskStateProperties
import com.example.aiagent.memory.MemoryEntry
import com.example.aiagent.memory.MemoryLayerUpdate
import com.example.aiagent.memory.MemoryUpdate
import com.example.aiagent.task.DeterministicTaskStateMachine
import com.example.aiagent.task.ExpectedActionType
import com.example.aiagent.task.InvalidTaskStateException
import com.example.aiagent.task.InvalidTaskTransitionException
import com.example.aiagent.task.TaskEvent
import com.example.aiagent.task.TaskProgressProposal
import com.example.aiagent.task.TaskProgressAnalyzer
import com.example.aiagent.task.TaskStateCoordinator
import com.example.aiagent.task.TaskStateMachine
import com.example.aiagent.task.TaskStage
import com.example.aiagent.task.TaskStateHistoryEvent
import com.example.aiagent.task.TaskStateService
import com.example.aiagent.task.TaskStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.sqlite.SQLiteDataSource
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Path
import tools.jackson.module.kotlin.jacksonObjectMapper

class SqliteTaskStateIntegrationTest {
    @TempDir
    lateinit var tempDirectory: Path

    @Test
    fun `happy path and validation failure persist only valid transitions`() {
        val fixture = fixture("transitions.db")
        val task = fixture.repository.create("Booking")

        val invalidHistorySize = fixture.service.history(task.id).size
        assertThrows(InvalidTaskTransitionException::class.java) {
            fixture.service.applyEvent(task.id, TaskEvent.VALIDATION_PASSED)
        }
        assertEquals(TaskStage.PLANNING, fixture.repository.findById(task.id)?.stage)
        assertEquals(invalidHistorySize, fixture.service.history(task.id).size)

        assertEquals(TaskStage.EXECUTION, fixture.service.applyEvent(task.id, TaskEvent.PLAN_APPROVED).stage)
        assertEquals(TaskStage.VALIDATION, fixture.service.applyEvent(task.id, TaskEvent.EXECUTION_COMPLETED).stage)
        assertEquals(TaskStage.EXECUTION, fixture.service.applyEvent(task.id, TaskEvent.VALIDATION_FAILED).stage)
        assertEquals(TaskStage.VALIDATION, fixture.service.applyEvent(task.id, TaskEvent.EXECUTION_COMPLETED).stage)
        val done = fixture.service.applyEvent(task.id, TaskEvent.VALIDATION_PASSED)

        assertEquals(TaskStage.DONE, done.stage)
        assertEquals(TaskStatus.COMPLETED, done.status)
        assertEquals(ExpectedActionType.NONE, done.expectedActionType)
        assertThrows(InvalidTaskStateException::class.java) { fixture.service.pause(task.id) }
    }

    @Test
    fun `pause and resume preserve progress at planning execution and validation`() {
        val fixture = fixture("pause-stages.db")
        val planning = fixture.repository.create("Planning Task")
        assertPauseRoundTrip(fixture.service, planning.id, TaskStage.PLANNING)

        val execution = fixture.repository.create("Execution Task")
        fixture.service.applyEvent(
            execution.id,
            TaskEvent.PLAN_APPROVED,
            proposal("Implement persistence layer", ExpectedActionType.AGENT_ACTION, "Propose repository implementation"),
        )
        assertPauseRoundTrip(fixture.service, execution.id, TaskStage.EXECUTION)

        val validation = fixture.repository.create("Validation Task")
        fixture.service.applyEvent(validation.id, TaskEvent.PLAN_APPROVED)
        fixture.service.applyEvent(
            validation.id,
            TaskEvent.EXECUTION_COMPLETED,
            proposal("Validate payment error handling", ExpectedActionType.VALIDATION, "Run payment error scenarios"),
        )
        assertPauseRoundTrip(fixture.service, validation.id, TaskStage.VALIDATION)
    }

    @Test
    fun `Tasks keep independent progress while switching A B A`() {
        val fixture = fixture("task-isolation.db")
        val taskA = fixture.repository.create("Booking Service")
        fixture.service.applyEvent(
            taskA.id,
            TaskEvent.PLAN_APPROVED,
            proposal("Implement persistence layer", ExpectedActionType.AGENT_ACTION, "Propose repository implementation"),
        )
        val taskB = fixture.repository.create("Online Shop")
        fixture.service.applyEvent(taskB.id, TaskEvent.PLAN_APPROVED)
        fixture.service.applyEvent(
            taskB.id,
            TaskEvent.EXECUTION_COMPLETED,
            proposal("Validate payment error handling", ExpectedActionType.VALIDATION, "Run payment failure cases"),
        )

        fixture.repository.activate(taskA.id)
        assertEquals(TaskStage.EXECUTION, fixture.repository.active().stage)
        assertEquals("Implement persistence layer", fixture.repository.active().currentStep)
        fixture.repository.activate(taskB.id)
        assertEquals(TaskStage.VALIDATION, fixture.repository.active().stage)
        assertEquals("Validate payment error handling", fixture.repository.active().currentStep)
        fixture.repository.activate(taskA.id)
        assertEquals(TaskStage.EXECUTION, fixture.repository.active().stage)
        assertEquals("Implement persistence layer", fixture.repository.active().currentStep)
    }

    @Test
    fun `paused execution state and history survive repository restart`() {
        val databasePath = tempDirectory.resolve("restart.db")
        val first = fixture(databasePath)
        val task = first.repository.create("Booking Service")
        first.service.applyEvent(
            task.id,
            TaskEvent.PLAN_APPROVED,
            proposal("Implement persistence layer", ExpectedActionType.AGENT_ACTION, "Propose repository implementation"),
        )
        first.service.pause(task.id)

        val restored = fixture(databasePath)
        val restoredTask = restored.repository.findById(task.id)!!
        assertEquals(TaskStage.EXECUTION, restoredTask.stage)
        assertEquals("Implement persistence layer", restoredTask.currentStep)
        assertEquals(ExpectedActionType.AGENT_ACTION, restoredTask.expectedActionType)
        assertEquals("Propose repository implementation", restoredTask.expectedActionDescription)
        assertTrue(restoredTask.paused)

        val resumed = restored.service.resume(task.id)
        assertFalse(resumed.paused)
        assertEquals(TaskStage.EXECUTION, resumed.stage)
        assertEquals("Implement persistence layer", resumed.currentStep)
        assertEquals("Propose repository implementation", resumed.expectedActionDescription)
        assertEquals(
            listOf(
                TaskStateHistoryEvent.TASK_CREATED,
                TaskStateHistoryEvent.PLAN_APPROVED,
                TaskStateHistoryEvent.PAUSE,
                TaskStateHistoryEvent.RESUME,
            ),
            restored.service.history(task.id).map { it.event },
        )
    }

    @Test
    fun `chat and working memory reset leave FSM state unchanged`() {
        val fixture = fixture("reset.db")
        val task = fixture.repository.create("Booking")
        fixture.service.applyEvent(
            task.id,
            TaskEvent.PLAN_APPROVED,
            proposal("Implement persistence layer", ExpectedActionType.AGENT_ACTION, "Continue repository work"),
        )
        val memory = SqliteMemoryRepository(fixture.jdbc, jacksonObjectMapper())
        memory.apply(
            task.id,
            "Use SQLite",
            MemoryUpdate(
                working = MemoryLayerUpdate(
                    upsert = listOf(MemoryEntry("database", "SQLite")),
                ),
            ),
        )
        memory.clearWorking(task.id)
        val conversations = SqliteConversationRepository(fixture.jdbc)
        conversations.save(
            Conversation(task.id).apply {
                add(ChatMessage(Role.USER, "Previous request"))
            },
        )
        val contextStateService = ContextStateService(
            conversations,
            SqliteMemoryFactRepository(fixture.jdbc),
            ConversationBranchService(SqliteConversationBranchRepository(fixture.jdbc)),
        )

        contextStateService.reset(task.id)

        assertTrue(memory.findWorking(task.id).isEmpty())
        assertTrue(conversations.load(task.id).messages().isEmpty())
        val state = fixture.repository.findById(task.id)!!
        assertEquals(TaskStage.EXECUTION, state.stage)
        assertEquals("Implement persistence layer", state.currentStep)
        assertEquals(ExpectedActionType.AGENT_ACTION, state.expectedActionType)
    }

    @Test
    fun `chat proposals and manual events pass through the same TaskStateMachine`() {
        val jdbc = JdbcTemplate(dataSource(tempDirectory.resolve("shared-state-machine.db")))
        val repository = SqliteTaskRepository(jdbc)
        val stateMachine = RecordingTaskStateMachine()
        val service = TaskStateService(repository, stateMachine)
        val proposals = ArrayDeque(
            listOf(
                TaskProgressProposal(
                    currentStep = "Implement persistence layer",
                    expectedActionType = ExpectedActionType.AGENT_ACTION,
                    expectedActionDescription = "Implement Room and Booking repositories",
                    proposedEvent = TaskEvent.PLAN_APPROVED,
                ),
                TaskProgressProposal(
                    currentStep = "Implement persistence layer: Room and Booking",
                    expectedActionType = ExpectedActionType.AGENT_ACTION,
                    expectedActionDescription = "Create Room and Booking persistence components",
                ),
            ),
        )
        val analyzer = object : TaskProgressAnalyzer {
            override fun analyze(
                task: com.example.aiagent.task.AgentTask,
                userMessage: ChatMessage,
            ): TaskProgressProposal = proposals.removeFirst()
        }
        val coordinator = TaskStateCoordinator(
            TaskStateProperties(TaskProgressAnalyzerProperties(enabled = true)),
            analyzer,
            service,
        )
        val created = repository.create("Booking")

        val execution = coordinator.analyzeBeforeMainRequest(
            created,
            ChatMessage(Role.USER, "План подтверждаю. Начинай реализацию."),
        )
        val updatedExecution = coordinator.analyzeBeforeMainRequest(
            execution,
            ChatMessage(Role.USER, "Начни с persistence layer. Нужны Room и Booking."),
        )
        val validation = service.applyEvent(updatedExecution.id, TaskEvent.EXECUTION_COMPLETED)

        assertEquals(TaskStage.EXECUTION, execution.stage)
        assertEquals(TaskStage.EXECUTION, updatedExecution.stage)
        assertEquals("Implement persistence layer: Room and Booking", updatedExecution.currentStep)
        assertEquals(TaskStage.VALIDATION, validation.stage)
        assertEquals(
            listOf(
                TaskStage.PLANNING to TaskEvent.PLAN_APPROVED,
                TaskStage.EXECUTION to TaskEvent.EXECUTION_COMPLETED,
            ),
            stateMachine.transitions,
        )
        assertEquals(
            listOf(
                TaskStateHistoryEvent.TASK_CREATED,
                TaskStateHistoryEvent.PLAN_APPROVED,
                TaskStateHistoryEvent.PROGRESS_UPDATED,
                TaskStateHistoryEvent.EXECUTION_COMPLETED,
            ),
            service.history(created.id).map { it.event },
        )
    }

    @Test
    fun `state update rolls back when history insertion fails`() {
        val databasePath = tempDirectory.resolve("atomicity.db")
        val dataSource = dataSource(databasePath)
        val jdbc = JdbcTemplate(dataSource)
        val repository = SqliteTaskRepository(jdbc)
        val service = TaskStateService(repository, DeterministicTaskStateMachine())
        val task = repository.create("Atomic Task")
        jdbc.execute(
            """
            CREATE TRIGGER fail_plan_history
            BEFORE INSERT ON task_state_history
            WHEN NEW.event = 'PLAN_APPROVED'
            BEGIN
                SELECT RAISE(ABORT, 'history failure');
            END
            """.trimIndent(),
        )
        val transaction = TransactionTemplate(DataSourceTransactionManager(dataSource))

        assertThrows(DataAccessException::class.java) {
            transaction.executeWithoutResult {
                service.applyEvent(task.id, TaskEvent.PLAN_APPROVED)
            }
        }

        assertEquals(TaskStage.PLANNING, repository.findById(task.id)?.stage)
        assertEquals(listOf(TaskStateHistoryEvent.TASK_CREATED), service.history(task.id).map { it.event })
    }

    private fun assertPauseRoundTrip(service: TaskStateService, taskId: Long, stage: TaskStage) {
        val before = service.state(taskId)
        val paused = service.pause(taskId)
        assertEquals(stage, paused.stage)
        assertTrue(paused.paused)
        assertEquals(before.currentStep, paused.currentStep)
        assertEquals(before.expectedAction, paused.expectedAction)

        val resumed = service.resume(taskId)
        assertEquals(stage, resumed.stage)
        assertFalse(resumed.paused)
        assertEquals(before.currentStep, resumed.currentStep)
        assertEquals(before.expectedAction, resumed.expectedAction)
    }

    private fun proposal(
        step: String,
        type: ExpectedActionType,
        description: String,
    ) = TaskProgressProposal(
        currentStep = step,
        expectedActionType = type,
        expectedActionDescription = description,
    )

    private fun fixture(fileName: String): Fixture = fixture(tempDirectory.resolve(fileName))

    private fun fixture(databasePath: Path): Fixture {
        val jdbc = JdbcTemplate(dataSource(databasePath))
        val repository = SqliteTaskRepository(jdbc)
        return Fixture(
            jdbc,
            repository,
            TaskStateService(repository, DeterministicTaskStateMachine()),
        )
    }

    private class RecordingTaskStateMachine : TaskStateMachine {
        private val delegate = DeterministicTaskStateMachine()
        val transitions = mutableListOf<Pair<TaskStage, TaskEvent>>()

        override fun transition(currentStage: TaskStage, event: TaskEvent): TaskStage {
            transitions += currentStage to event
            return delegate.transition(currentStage, event)
        }
    }

    private fun dataSource(databasePath: Path) = SQLiteDataSource().apply {
        url = "jdbc:sqlite:${databasePath.toAbsolutePath()}"
    }

    private data class Fixture(
        val jdbc: JdbcTemplate,
        val repository: SqliteTaskRepository,
        val service: TaskStateService,
    )
}
