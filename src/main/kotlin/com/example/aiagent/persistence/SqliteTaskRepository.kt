package com.example.aiagent.persistence

import com.example.aiagent.task.AgentTask
import com.example.aiagent.task.ExpectedActionType
import com.example.aiagent.task.NewTaskStateHistoryEntry
import com.example.aiagent.task.PersistedTaskState
import com.example.aiagent.task.TaskRepository
import com.example.aiagent.task.TaskStage
import com.example.aiagent.task.TaskStateHistoryEntry
import com.example.aiagent.task.TaskStateHistoryEvent
import com.example.aiagent.task.TaskStatus
import com.example.aiagent.task.TaskStateConflictException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Repository
class SqliteTaskRepository(
    private val jdbcTemplate: JdbcTemplate,
) : TaskRepository {
    init {
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS agent_task (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                status TEXT NOT NULL,
                created_at TEXT NOT NULL,
                completed_at TEXT,
                stage TEXT NOT NULL DEFAULT 'PLANNING',
                current_step TEXT NOT NULL DEFAULT 'Define goals, requirements, and execution plan',
                expected_action_type TEXT NOT NULL DEFAULT 'USER_INPUT',
                expected_action_description TEXT,
                paused INTEGER NOT NULL DEFAULT 0,
                version INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        addColumnIfMissing("agent_task", "stage", "TEXT NOT NULL DEFAULT 'PLANNING'")
        addColumnIfMissing(
            "agent_task",
            "current_step",
            "TEXT NOT NULL DEFAULT 'Define goals, requirements, and execution plan'",
        )
        addColumnIfMissing("agent_task", "expected_action_type", "TEXT NOT NULL DEFAULT 'USER_INPUT'")
        addColumnIfMissing("agent_task", "expected_action_description", "TEXT")
        addColumnIfMissing("agent_task", "paused", "INTEGER NOT NULL DEFAULT 0")
        addColumnIfMissing("agent_task", "version", "INTEGER NOT NULL DEFAULT 0")
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS active_task_state (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                task_id INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS task_state_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                task_id INTEGER NOT NULL,
                event TEXT NOT NULL,
                source TEXT,
                from_stage TEXT,
                to_stage TEXT NOT NULL,
                paused INTEGER NOT NULL,
                current_step TEXT NOT NULL,
                expected_action_type TEXT,
                expected_action_description TEXT,
                description TEXT,
                version INTEGER NOT NULL DEFAULT 0,
                created_at TEXT NOT NULL,
                FOREIGN KEY(task_id) REFERENCES agent_task(id)
            )
            """.trimIndent(),
        )
        addColumnIfMissing("task_state_history", "source", "TEXT")
        addColumnIfMissing("task_state_history", "expected_action_type", "TEXT")
        addColumnIfMissing("task_state_history", "expected_action_description", "TEXT")
        addColumnIfMissing("task_state_history", "version", "INTEGER NOT NULL DEFAULT 0")
        jdbcTemplate.execute(
            "CREATE INDEX IF NOT EXISTS idx_task_state_history_task ON task_state_history(task_id, id)",
        )
        val now = Instant.now().toString()
        jdbcTemplate.update(
            """
            INSERT OR IGNORE INTO agent_task(
                id, name, status, created_at, completed_at, stage, current_step,
                expected_action_type, expected_action_description, paused, version
            ) VALUES (
                1, 'Основная задача', 'ACTIVE', ?, NULL, 'PLANNING',
                'Define goals, requirements, and execution plan',
                'USER_INPUT', 'Provide goals, requirements, and constraints', 0, 0
            )
            """.trimIndent(),
            now,
        )
        jdbcTemplate.update(
            "INSERT OR IGNORE INTO active_task_state(id, task_id) VALUES (1, 1)",
        )
        migrateTaskState()
        jdbcTemplate.update(
            """
            INSERT INTO task_state_history(
                task_id, event, source, from_stage, to_stage, paused, current_step,
                expected_action_type, expected_action_description, description, version, created_at
            )
            SELECT
                t.id, 'TASK_CREATED', NULL, NULL, t.stage, t.paused, t.current_step,
                t.expected_action_type, t.expected_action_description,
                t.expected_action_description, t.version, t.created_at
            FROM agent_task t
            WHERE NOT EXISTS (
                SELECT 1
                FROM task_state_history h
                WHERE h.task_id = t.id AND h.event = 'TASK_CREATED'
            )
            """.trimIndent(),
        )
    }

    override fun findAll(): List<AgentTask> = jdbcTemplate.query(
        """
        SELECT
            t.id, t.name, t.status, t.created_at, t.completed_at, t.stage,
            t.current_step, t.expected_action_type, t.expected_action_description, t.paused, t.version,
            CASE WHEN s.task_id = t.id THEN 1 ELSE 0 END AS selected
        FROM agent_task t
        CROSS JOIN active_task_state s
        WHERE s.id = 1
        ORDER BY t.created_at, t.id
        """.trimIndent(),
    ) { resultSet, _ ->
        AgentTask(
            id = resultSet.getLong("id"),
            name = resultSet.getString("name"),
            status = TaskStatus.valueOf(resultSet.getString("status")),
            createdAt = Instant.parse(resultSet.getString("created_at")),
            completedAt = resultSet.getString("completed_at")?.let(Instant::parse),
            selected = resultSet.getBoolean("selected"),
            stage = TaskStage.valueOf(resultSet.getString("stage")),
            currentStep = resultSet.getString("current_step"),
            expectedActionType = ExpectedActionType.valueOf(resultSet.getString("expected_action_type")),
            expectedActionDescription = resultSet.getString("expected_action_description"),
            paused = resultSet.getBoolean("paused"),
            version = resultSet.getLong("version"),
        )
    }

    override fun active(): AgentTask =
        findAll().singleOrNull(AgentTask::selected)
            ?: error("Exactly one selected Task is required")

    override fun findById(taskId: Long): AgentTask? = findAll().firstOrNull { it.id == taskId }

    @Transactional
    override fun create(name: String): AgentTask {
        val now = Instant.now()
        val currentStep = "Define goals, requirements, and execution plan"
        val description = "Provide goals, requirements, and constraints"
        jdbcTemplate.update(
            """
            INSERT INTO agent_task(
                name, status, created_at, completed_at, stage, current_step,
                expected_action_type, expected_action_description, paused, version
            ) VALUES (?, 'ACTIVE', ?, NULL, 'PLANNING', ?, 'USER_INPUT', ?, 0, 0)
            """.trimIndent(),
            name,
            now.toString(),
            currentStep,
            description,
        )
        val id = checkNotNull(
            jdbcTemplate.queryForObject("SELECT MAX(id) FROM agent_task", Long::class.java),
        )
        jdbcTemplate.update("UPDATE active_task_state SET task_id = ? WHERE id = 1", id)
        insertHistory(
            NewTaskStateHistoryEntry(
                taskId = id,
                event = TaskStateHistoryEvent.TASK_CREATED,
                source = null,
                fromStage = null,
                toStage = TaskStage.PLANNING,
                paused = false,
                currentStep = currentStep,
                expectedActionType = ExpectedActionType.USER_INPUT,
                expectedActionDescription = description,
                version = 0,
                createdAt = now,
            ),
        )
        return checkNotNull(findById(id))
    }

    @Transactional
    override fun activate(taskId: Long): AgentTask {
        require(findById(taskId) != null) { "Task $taskId does not exist" }
        jdbcTemplate.update("UPDATE active_task_state SET task_id = ? WHERE id = 1", taskId)
        return checkNotNull(findById(taskId))
    }

    @Transactional
    override fun updateState(
        taskId: Long,
        state: PersistedTaskState,
        history: NewTaskStateHistoryEntry,
    ): AgentTask {
        require(history.taskId == taskId) { "History belongs to a different Task" }
        val updated = jdbcTemplate.update(
            """
            UPDATE agent_task
            SET
                status = ?,
                completed_at = ?,
                stage = ?,
                current_step = ?,
                expected_action_type = ?,
                expected_action_description = ?,
                paused = ?,
                version = version + 1
            WHERE id = ? AND version = ?
            """.trimIndent(),
            state.status.name,
            state.completedAt?.toString(),
            state.stage.name,
            state.currentStep,
            state.expectedActionType.name,
            state.expectedActionDescription,
            if (state.paused) 1 else 0,
            taskId,
            state.expectedVersion,
        )
        if (updated != 1) {
            throw TaskStateConflictException(taskId, state.expectedVersion, findById(taskId)?.version)
        }
        insertHistory(history)
        return checkNotNull(findById(taskId))
    }

    override fun stateHistory(taskId: Long): List<TaskStateHistoryEntry> = jdbcTemplate.query(
        """
        SELECT id, task_id, event, source, from_stage, to_stage, paused, current_step,
               expected_action_type, expected_action_description, description, version, created_at
        FROM task_state_history
        WHERE task_id = ?
        ORDER BY id
        """.trimIndent(),
        { resultSet, _ ->
            val expectedActionType = resultSet.getString("expected_action_type")
                ?.let(ExpectedActionType::valueOf)
                ?: if (TaskStage.valueOf(resultSet.getString("to_stage")) == TaskStage.DONE) {
                    ExpectedActionType.NONE
                } else {
                    ExpectedActionType.USER_INPUT
                }
            TaskStateHistoryEntry(
                id = resultSet.getLong("id"),
                taskId = resultSet.getLong("task_id"),
                event = TaskStateHistoryEvent.valueOf(resultSet.getString("event")),
                source = resultSet.getString("source")?.let(com.example.aiagent.task.TaskEventSource::valueOf),
                fromStage = resultSet.getString("from_stage")?.let(TaskStage::valueOf),
                toStage = TaskStage.valueOf(resultSet.getString("to_stage")),
                paused = resultSet.getBoolean("paused"),
                currentStep = resultSet.getString("current_step"),
                expectedActionType = expectedActionType,
                expectedActionDescription = resultSet.getString("expected_action_description")
                    ?: resultSet.getString("description"),
                version = resultSet.getLong("version"),
                createdAt = Instant.parse(resultSet.getString("created_at")),
            )
        },
        taskId,
    )

    private fun insertHistory(entry: NewTaskStateHistoryEntry) {
        jdbcTemplate.update(
            """
            INSERT INTO task_state_history(
                task_id, event, source, from_stage, to_stage, paused, current_step,
                expected_action_type, expected_action_description, description, version, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            entry.taskId,
            entry.event.name,
            entry.source?.name,
            entry.fromStage?.name,
            entry.toStage.name,
            if (entry.paused) 1 else 0,
            entry.currentStep,
            entry.expectedActionType.name,
            entry.expectedActionDescription,
            entry.expectedActionDescription,
            entry.version,
            entry.createdAt.toString(),
        )
    }

    private fun migrateTaskState() {
        jdbcTemplate.update(
            """
            UPDATE agent_task
            SET
                stage = 'DONE',
                current_step = 'Task completed',
                expected_action_type = 'NONE',
                expected_action_description = NULL,
                paused = 0
            WHERE status = 'COMPLETED'
            """.trimIndent(),
        )
        jdbcTemplate.update(
            """
            UPDATE agent_task
            SET
                status = 'COMPLETED',
                completed_at = COALESCE(completed_at, created_at),
                expected_action_type = 'NONE',
                expected_action_description = NULL,
                paused = 0
            WHERE stage = 'DONE'
            """.trimIndent(),
        )
        jdbcTemplate.update(
            """
            UPDATE agent_task
            SET
                status = 'ACTIVE',
                completed_at = NULL,
                expected_action_description = COALESCE(
                    expected_action_description,
                    'Provide goals, requirements, and constraints'
                )
            WHERE stage <> 'DONE'
            """.trimIndent(),
        )
    }

    private fun addColumnIfMissing(table: String, column: String, definition: String) {
        val columns = jdbcTemplate.queryForList("PRAGMA table_info($table)")
            .mapNotNull { row -> row["name"]?.toString() }
        if (column !in columns) {
            jdbcTemplate.execute("ALTER TABLE $table ADD COLUMN $column $definition")
        }
    }
}
