package com.example.aiagent.persistence

import com.example.aiagent.agent.ChatMessage
import com.example.aiagent.agent.Conversation
import com.example.aiagent.agent.ConversationTokenUsage
import com.example.aiagent.agent.LlmRequestUsage
import com.example.aiagent.agent.Role
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.Types
import java.time.Instant

@Repository
class SqliteConversationRepository(
    private val jdbcTemplate: JdbcTemplate,
) : ConversationRepository {

    init {
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS chat_message (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                created_at TEXT NOT NULL
            )
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS llm_request_usage (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                provider TEXT NOT NULL,
                model TEXT NOT NULL,
                input_tokens INTEGER,
                output_tokens INTEGER,
                total_tokens INTEGER,
                response_time_ms INTEGER NOT NULL,
                created_at TEXT NOT NULL
            )
            """.trimIndent(),
        )
    }

    override fun load(): Conversation {
        val messages = jdbcTemplate.query(
            "SELECT role, content FROM chat_message ORDER BY id",
        ) { resultSet, _ ->
            ChatMessage(
                role = Role.valueOf(resultSet.getString("role")),
                content = resultSet.getString("content"),
            )
        }
        val tokenUsage = jdbcTemplate.queryForObject(
            """
            SELECT
                COALESCE(SUM(input_tokens), 0) AS input_tokens,
                COALESCE(SUM(output_tokens), 0) AS output_tokens,
                COALESCE(SUM(COALESCE(total_tokens, COALESCE(input_tokens, 0) + COALESCE(output_tokens, 0))), 0) AS total_tokens
            FROM llm_request_usage
            """.trimIndent(),
        ) { resultSet, _ ->
            ConversationTokenUsage(
                inputTokens = resultSet.getLong("input_tokens"),
                outputTokens = resultSet.getLong("output_tokens"),
                totalTokens = resultSet.getLong("total_tokens"),
            )
        }

        return Conversation().apply {
            restore(messages, tokenUsage)
        }
    }

    @Transactional
    override fun save(conversation: Conversation) {
        replaceMessages(conversation.messages())
    }

    @Transactional
    override fun save(conversation: Conversation, requestUsage: LlmRequestUsage) {
        replaceMessages(conversation.messages())
        insertUsage(requestUsage)
    }

    @Transactional
    override fun clear() {
        jdbcTemplate.update("DELETE FROM llm_request_usage")
        jdbcTemplate.update("DELETE FROM chat_message")
    }

    private fun replaceMessages(messages: List<ChatMessage>) {
        jdbcTemplate.update("DELETE FROM chat_message")
        if (messages.isEmpty()) {
            return
        }

        val createdAt = Instant.now()
        jdbcTemplate.execute(ConnectionCallback { connection ->
            connection.prepareStatement(
                "INSERT INTO chat_message(role, content, created_at) VALUES (?, ?, ?)",
            ).use { statement ->
                messages.forEachIndexed { index, message ->
                    statement.setString(1, message.role.name)
                    statement.setString(2, message.content)
                    statement.setString(3, createdAt.plusNanos(index.toLong()).toString())
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        })
    }

    private fun insertUsage(requestUsage: LlmRequestUsage) {
        jdbcTemplate.update(
            """
            INSERT INTO llm_request_usage(
                provider,
                model,
                input_tokens,
                output_tokens,
                total_tokens,
                response_time_ms,
                created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            { statement ->
                statement.setString(1, requestUsage.provider.name)
                statement.setString(2, requestUsage.model)
                statement.setNullableLong(3, requestUsage.tokenUsage.inputTokens)
                statement.setNullableLong(4, requestUsage.tokenUsage.outputTokens)
                statement.setNullableLong(5, requestUsage.tokenUsage.totalTokens)
                statement.setLong(6, requestUsage.responseTimeMs)
                statement.setString(7, Instant.now().toString())
            },
        )
    }

    private fun java.sql.PreparedStatement.setNullableLong(index: Int, value: Long?) {
        if (value == null) {
            setNull(index, Types.BIGINT)
        } else {
            setLong(index, value)
        }
    }
}
