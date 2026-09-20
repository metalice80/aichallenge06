package com.example.aiagent.persistence

import com.example.aiagent.task.AgentTask
import com.example.aiagent.task.TaskRepository
import com.example.aiagent.task.TaskStatus
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
                completed_at TEXT
            )
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS active_task_state (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                task_id INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        val now = Instant.now().toString()
        jdbcTemplate.update(
            """
            INSERT OR IGNORE INTO agent_task(id, name, status, created_at, completed_at)
            VALUES (1, 'Основная задача', 'ACTIVE', ?, NULL)
            """.trimIndent(),
            now,
        )
        jdbcTemplate.update(
            "INSERT OR IGNORE INTO active_task_state(id, task_id) VALUES (1, 1)",
        )
    }

    override fun findAll(): List<AgentTask> = jdbcTemplate.query(
        """
        SELECT t.id, t.name, t.status, t.created_at, t.completed_at,
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
        )
    }

    override fun active(): AgentTask =
        findAll().singleOrNull(AgentTask::selected)
            ?: error("Exactly one selected Task is required")

    override fun findById(taskId: Long): AgentTask? = findAll().firstOrNull { it.id == taskId }

    @Transactional
    override fun create(name: String): AgentTask {
        jdbcTemplate.update(
            "INSERT INTO agent_task(name, status, created_at, completed_at) VALUES (?, 'ACTIVE', ?, NULL)",
            name,
            Instant.now().toString(),
        )
        val id = checkNotNull(
            jdbcTemplate.queryForObject("SELECT MAX(id) FROM agent_task", Long::class.java),
        )
        jdbcTemplate.update("UPDATE active_task_state SET task_id = ? WHERE id = 1", id)
        return checkNotNull(findById(id))
    }

    @Transactional
    override fun activate(taskId: Long): AgentTask {
        require(findById(taskId) != null) { "Task $taskId does not exist" }
        jdbcTemplate.update("UPDATE active_task_state SET task_id = ? WHERE id = 1", taskId)
        return checkNotNull(findById(taskId))
    }

    @Transactional
    override fun complete(taskId: Long): AgentTask {
        require(findById(taskId) != null) { "Task $taskId does not exist" }
        jdbcTemplate.update(
            """
            UPDATE agent_task
            SET status = 'COMPLETED', completed_at = COALESCE(completed_at, ?)
            WHERE id = ?
            """.trimIndent(),
            Instant.now().toString(),
            taskId,
        )
        return checkNotNull(findById(taskId))
    }
}
