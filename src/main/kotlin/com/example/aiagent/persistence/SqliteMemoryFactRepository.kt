package com.example.aiagent.persistence

import com.example.aiagent.context.facts.FactsUpdate
import com.example.aiagent.context.facts.MemoryFact
import com.example.aiagent.context.facts.MemoryFactRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Repository
class SqliteMemoryFactRepository(
    private val jdbcTemplate: JdbcTemplate,
) : MemoryFactRepository {
    init {
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS conversation_fact (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS task_conversation_fact (
                task_id INTEGER NOT NULL,
                key TEXT NOT NULL,
                value TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                PRIMARY KEY(task_id, key)
            )
            """.trimIndent(),
        )
        jdbcTemplate.update(
            """
            INSERT OR IGNORE INTO task_conversation_fact(task_id, key, value, updated_at)
            SELECT 1, key, value, updated_at FROM conversation_fact
            """.trimIndent(),
        )
    }

    override fun findAll(taskId: Long): List<MemoryFact> = jdbcTemplate.query(
        "SELECT key, value FROM task_conversation_fact WHERE task_id = ? ORDER BY key",
        { resultSet, _ ->
            MemoryFact(
                key = resultSet.getString("key"),
                value = resultSet.getString("value"),
            )
        },
        taskId,
    )

    @Transactional
    override fun apply(taskId: Long, update: FactsUpdate) {
        update.deleteKeys.distinct().forEach { key ->
            jdbcTemplate.update(
                "DELETE FROM task_conversation_fact WHERE task_id = ? AND key = ?",
                taskId,
                key.trim(),
            )
        }
        val updatedAt = Instant.now().toString()
        update.upsert.forEach { fact ->
            jdbcTemplate.update(
                """
                INSERT INTO task_conversation_fact(task_id, key, value, updated_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(task_id, key) DO UPDATE SET
                    value = excluded.value,
                    updated_at = excluded.updated_at
                """.trimIndent(),
                taskId,
                fact.key.trim(),
                fact.value.trim(),
                updatedAt,
            )
        }
    }

    override fun clear(taskId: Long) {
        jdbcTemplate.update("DELETE FROM task_conversation_fact WHERE task_id = ?", taskId)
    }
}
