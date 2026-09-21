package com.example.aiagent.persistence

import com.example.aiagent.invariant.InvariantCheckDecision
import com.example.aiagent.invariant.InvariantCheckDirection
import com.example.aiagent.invariant.InvariantCheckOutcome
import com.example.aiagent.invariant.InvariantCheckStatus
import com.example.aiagent.invariant.InvariantType
import com.example.aiagent.invariant.InvariantViolationDetail
import com.example.aiagent.invariant.LastInvariantCheck
import com.example.aiagent.invariant.NewTaskInvariant
import com.example.aiagent.invariant.TaskInvariant
import com.example.aiagent.invariant.TaskInvariantRepository
import com.example.aiagent.invariant.TaskInvariantSnapshot
import com.example.aiagent.llm.LlmProvider
import com.example.aiagent.llm.TokenUsage
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.sql.Statement
import java.time.Instant

@Repository
class SqliteTaskInvariantRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val jsonMapper: ObjectMapper,
) : TaskInvariantRepository {
    init {
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS task_invariant (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                task_id INTEGER NOT NULL,
                type TEXT NOT NULL,
                key TEXT NOT NULL,
                value TEXT NOT NULL,
                description TEXT,
                enabled INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                FOREIGN KEY(task_id) REFERENCES agent_task(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            "CREATE INDEX IF NOT EXISTS idx_task_invariant_task ON task_invariant(task_id, type, key, id)",
        )
        jdbcTemplate.execute(
            "CREATE INDEX IF NOT EXISTS idx_task_invariant_task_enabled ON task_invariant(task_id, enabled)",
        )
        jdbcTemplate.execute(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS uq_task_invariant_active_key
            ON task_invariant(task_id, key)
            WHERE enabled = 1
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS last_invariant_check (
                task_id INTEGER PRIMARY KEY,
                check_id TEXT NOT NULL,
                task_name TEXT NOT NULL,
                direction TEXT NOT NULL,
                result TEXT NOT NULL,
                request_excerpt TEXT NOT NULL,
                invariant_snapshot TEXT NOT NULL,
                violations TEXT NOT NULL,
                corrective_retries INTEGER NOT NULL,
                outcome TEXT NOT NULL,
                status TEXT NOT NULL,
                error_code TEXT,
                provider TEXT,
                model TEXT,
                input_tokens INTEGER,
                output_tokens INTEGER,
                total_tokens INTEGER,
                response_time_ms INTEGER,
                checked_at TEXT NOT NULL,
                FOREIGN KEY(task_id) REFERENCES agent_task(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        addColumnIfMissing("last_invariant_check", "error_code", "TEXT")
    }

    override fun findByTaskId(taskId: Long): List<TaskInvariant> = query(
        """
        SELECT id, task_id, type, key, value, description, enabled, created_at, updated_at
        FROM task_invariant
        WHERE task_id = ?
        ORDER BY type, key, id
        """.trimIndent(),
        taskId,
    )

    override fun findEnabledByTaskId(taskId: Long): List<TaskInvariant> = query(
        """
        SELECT id, task_id, type, key, value, description, enabled, created_at, updated_at
        FROM task_invariant
        WHERE task_id = ? AND enabled = 1
        ORDER BY type, key, id
        """.trimIndent(),
        taskId,
    )

    override fun findByTaskAndId(taskId: Long, invariantId: Long): TaskInvariant? = jdbcTemplate.query(
        """
        SELECT id, task_id, type, key, value, description, enabled, created_at, updated_at
        FROM task_invariant
        WHERE task_id = ? AND id = ?
        """.trimIndent(),
        ::mapInvariant,
        taskId,
        invariantId,
    ).firstOrNull()

    override fun create(command: NewTaskInvariant): TaskInvariant {
        val now = Instant.now()
        val keyHolder = GeneratedKeyHolder()
        jdbcTemplate.update(
            { connection ->
                connection.prepareStatement(
                    """
                    INSERT INTO task_invariant(
                        task_id, type, key, value, description, enabled, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).apply {
                    setLong(1, command.taskId)
                    setString(2, command.type.name)
                    setString(3, command.key)
                    setString(4, command.value)
                    setString(5, command.description)
                    setInt(6, if (command.enabled) 1 else 0)
                    setString(7, now.toString())
                    setString(8, now.toString())
                }
            },
            keyHolder,
        )
        val id = checkNotNull(keyHolder.key).toLong()
        return checkNotNull(findByTaskAndId(command.taskId, id))
    }

    override fun update(invariant: TaskInvariant): TaskInvariant {
        val updatedAt = Instant.now()
        val updated = jdbcTemplate.update(
            """
            UPDATE task_invariant
            SET type = ?, key = ?, value = ?, description = ?, enabled = ?, updated_at = ?
            WHERE task_id = ? AND id = ?
            """.trimIndent(),
            invariant.type.name,
            invariant.key,
            invariant.value,
            invariant.description,
            if (invariant.enabled) 1 else 0,
            updatedAt.toString(),
            invariant.taskId,
            invariant.id,
        )
        check(updated == 1) { "Invariant ${invariant.id} does not exist for Task ${invariant.taskId}" }
        return checkNotNull(findByTaskAndId(invariant.taskId, invariant.id))
    }

    override fun delete(taskId: Long, invariantId: Long): Boolean =
        jdbcTemplate.update("DELETE FROM task_invariant WHERE task_id = ? AND id = ?", taskId, invariantId) == 1

    override fun saveLastCheck(check: LastInvariantCheck) {
        jdbcTemplate.update(
            """
            INSERT INTO last_invariant_check(
                task_id, check_id, task_name, direction, result, request_excerpt,
                invariant_snapshot, violations, corrective_retries, outcome, status, error_code,
                provider, model, input_tokens, output_tokens, total_tokens,
                response_time_ms, checked_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(task_id) DO UPDATE SET
                check_id = excluded.check_id,
                task_name = excluded.task_name,
                direction = excluded.direction,
                result = excluded.result,
                request_excerpt = excluded.request_excerpt,
                invariant_snapshot = excluded.invariant_snapshot,
                violations = excluded.violations,
                corrective_retries = excluded.corrective_retries,
                outcome = excluded.outcome,
                status = excluded.status,
                error_code = excluded.error_code,
                provider = excluded.provider,
                model = excluded.model,
                input_tokens = excluded.input_tokens,
                output_tokens = excluded.output_tokens,
                total_tokens = excluded.total_tokens,
                response_time_ms = excluded.response_time_ms,
                checked_at = excluded.checked_at
            """.trimIndent(),
            check.taskId,
            check.checkId,
            check.taskName,
            check.direction.name,
            check.result.name,
            check.requestExcerpt,
            jsonMapper.writeValueAsString(check.invariantSnapshot),
            jsonMapper.writeValueAsString(check.violations),
            check.correctiveRetries,
            check.outcome.name,
            check.status.name,
            check.errorCode,
            check.provider?.name,
            check.model,
            check.usage?.inputTokens,
            check.usage?.outputTokens,
            check.usage?.totalTokens,
            check.responseTimeMs,
            check.checkedAt.toString(),
        )
    }

    override fun lastCheck(taskId: Long): LastInvariantCheck? = jdbcTemplate.query(
        "SELECT * FROM last_invariant_check WHERE task_id = ?",
        { resultSet, _ ->
            LastInvariantCheck(
                checkId = resultSet.getString("check_id"),
                taskId = taskId,
                taskName = resultSet.getString("task_name"),
                direction = InvariantCheckDirection.valueOf(resultSet.getString("direction")),
                result = InvariantCheckDecision.valueOf(resultSet.getString("result")),
                requestExcerpt = resultSet.getString("request_excerpt"),
                invariantSnapshot = jsonMapper.readValue(
                    resultSet.getString("invariant_snapshot"),
                    Array<TaskInvariantSnapshot>::class.java,
                ).toList(),
                violations = jsonMapper.readValue(
                    resultSet.getString("violations"),
                    Array<InvariantViolationDetail>::class.java,
                ).toList(),
                correctiveRetries = resultSet.getInt("corrective_retries"),
                outcome = InvariantCheckOutcome.valueOf(resultSet.getString("outcome")),
                status = InvariantCheckStatus.valueOf(resultSet.getString("status")),
                errorCode = resultSet.getString("error_code"),
                provider = resultSet.getString("provider")?.let(LlmProvider::valueOf),
                model = resultSet.getString("model"),
                usage = readUsage(resultSet.getObject("input_tokens"), resultSet.getObject("output_tokens"), resultSet.getObject("total_tokens")),
                responseTimeMs = resultSet.getObject("response_time_ms")?.let { (it as Number).toLong() },
                checkedAt = Instant.parse(resultSet.getString("checked_at")),
            )
        },
        taskId,
    ).firstOrNull()

    private fun query(sql: String, taskId: Long): List<TaskInvariant> =
        jdbcTemplate.query(sql, ::mapInvariant, taskId)

    private fun mapInvariant(resultSet: java.sql.ResultSet, ignored: Int) = TaskInvariant(
        id = resultSet.getLong("id"),
        taskId = resultSet.getLong("task_id"),
        type = InvariantType.valueOf(resultSet.getString("type")),
        key = resultSet.getString("key"),
        value = resultSet.getString("value"),
        description = resultSet.getString("description"),
        enabled = resultSet.getBoolean("enabled"),
        createdAt = Instant.parse(resultSet.getString("created_at")),
        updatedAt = Instant.parse(resultSet.getString("updated_at")),
    )

    private fun readUsage(input: Any?, output: Any?, total: Any?): TokenUsage? {
        if (input == null && output == null && total == null) return null
        return TokenUsage(
            inputTokens = (input as? Number)?.toLong(),
            outputTokens = (output as? Number)?.toLong(),
            totalTokens = (total as? Number)?.toLong(),
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
