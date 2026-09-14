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
    }

    override fun findAll(): List<MemoryFact> = jdbcTemplate.query(
        "SELECT key, value FROM conversation_fact ORDER BY key",
    ) { resultSet, _ ->
        MemoryFact(
            key = resultSet.getString("key"),
            value = resultSet.getString("value"),
        )
    }

    @Transactional
    override fun apply(update: FactsUpdate) {
        update.deleteKeys.distinct().forEach { key ->
            jdbcTemplate.update("DELETE FROM conversation_fact WHERE key = ?", key.trim())
        }
        val updatedAt = Instant.now().toString()
        update.upsert.forEach { fact ->
            jdbcTemplate.update(
                """
                INSERT INTO conversation_fact(key, value, updated_at)
                VALUES (?, ?, ?)
                ON CONFLICT(key) DO UPDATE SET
                    value = excluded.value,
                    updated_at = excluded.updated_at
                """.trimIndent(),
                fact.key.trim(),
                fact.value.trim(),
                updatedAt,
            )
        }
    }

    override fun clear() {
        jdbcTemplate.update("DELETE FROM conversation_fact")
    }
}
