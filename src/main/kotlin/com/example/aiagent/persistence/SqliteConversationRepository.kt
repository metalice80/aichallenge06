package com.example.aiagent.persistence

import com.example.aiagent.agent.ChatMessage
import com.example.aiagent.agent.Conversation
import com.example.aiagent.agent.ConversationSummary
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
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS conversation_summary (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                content TEXT NOT NULL,
                summarized_message_count INTEGER NOT NULL CHECK (summarized_message_count > 0),
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """.trimIndent(),
        )
        addTaskIdColumnIfMissing("chat_message")
        addTaskIdColumnIfMissing("llm_request_usage")
        jdbcTemplate.execute(
            "CREATE INDEX IF NOT EXISTS idx_chat_message_task_id ON chat_message(task_id, id)",
        )
        jdbcTemplate.execute(
            "CREATE INDEX IF NOT EXISTS idx_llm_request_usage_task_id ON llm_request_usage(task_id, id)",
        )
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS task_conversation_summary (
                task_id INTEGER PRIMARY KEY,
                content TEXT NOT NULL,
                summarized_message_count INTEGER NOT NULL CHECK (summarized_message_count > 0),
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """.trimIndent(),
        )
        jdbcTemplate.update(
            """
            INSERT OR IGNORE INTO task_conversation_summary(
                task_id, content, summarized_message_count, created_at, updated_at
            )
            SELECT 1, content, summarized_message_count, created_at, updated_at
            FROM conversation_summary
            WHERE id = 1
            """.trimIndent(),
        )
    }

    override fun load(taskId: Long): Conversation {
        val messages = jdbcTemplate.query(
            "SELECT role, content FROM chat_message WHERE task_id = ? ORDER BY id",
            { resultSet, _ ->
                ChatMessage(
                    role = Role.valueOf(resultSet.getString("role")),
                    content = resultSet.getString("content"),
                )
            },
            taskId,
        )
        val tokenUsage = jdbcTemplate.queryForObject(
            """
            SELECT
                COALESCE(SUM(input_tokens), 0) AS input_tokens,
                COALESCE(SUM(output_tokens), 0) AS output_tokens,
                COALESCE(SUM(COALESCE(total_tokens, COALESCE(input_tokens, 0) + COALESCE(output_tokens, 0))), 0) AS total_tokens
            FROM llm_request_usage
            WHERE task_id = ?
            """.trimIndent(),
            { resultSet, _ ->
                ConversationTokenUsage(
                    inputTokens = resultSet.getLong("input_tokens"),
                    outputTokens = resultSet.getLong("output_tokens"),
                    totalTokens = resultSet.getLong("total_tokens"),
                )
            },
            taskId,
        )
        val persistedSummary = jdbcTemplate.query(
            """
            SELECT content, summarized_message_count
            FROM task_conversation_summary
            WHERE task_id = ?
            """.trimIndent(),
            { resultSet, _ ->
                ConversationSummary(
                    content = resultSet.getString("content"),
                    summarizedMessageCount = resultSet.getInt("summarized_message_count"),
                )
            },
            taskId,
        ).firstOrNull()
        val summary = persistedSummary?.takeIf { it.summarizedMessageCount <= messages.size }
        if (persistedSummary != null && summary == null) {
            jdbcTemplate.update("DELETE FROM task_conversation_summary WHERE task_id = ?", taskId)
        }

        return Conversation(taskId).apply {
            restore(messages, tokenUsage, summary)
        }
    }

    @Transactional
    override fun save(conversation: Conversation) {
        replaceMessages(conversation.taskId, conversation.messages())
    }

    @Transactional
    override fun save(conversation: Conversation, requestUsage: LlmRequestUsage) {
        replaceMessages(conversation.taskId, conversation.messages())
        insertUsage(conversation.taskId, requestUsage)
    }

    @Transactional
    override fun saveSummary(taskId: Long, summary: ConversationSummary) {
        val now = Instant.now().toString()
        jdbcTemplate.update(
            """
            INSERT INTO task_conversation_summary(
                task_id, content, summarized_message_count, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(task_id) DO UPDATE SET
                content = excluded.content,
                summarized_message_count = excluded.summarized_message_count,
                updated_at = excluded.updated_at
            """.trimIndent(),
            taskId,
            summary.content,
            summary.summarizedMessageCount,
            now,
            now,
        )
    }

    @Transactional
    override fun clear(taskId: Long) {
        jdbcTemplate.update("DELETE FROM task_conversation_summary WHERE task_id = ?", taskId)
        jdbcTemplate.update("DELETE FROM llm_request_usage WHERE task_id = ?", taskId)
        jdbcTemplate.update("DELETE FROM chat_message WHERE task_id = ?", taskId)
    }

    private fun replaceMessages(taskId: Long, messages: List<ChatMessage>) {
        jdbcTemplate.update("DELETE FROM chat_message WHERE task_id = ?", taskId)
        if (messages.isEmpty()) {
            return
        }

        val createdAt = Instant.now()
        jdbcTemplate.execute(ConnectionCallback { connection ->
            connection.prepareStatement(
                "INSERT INTO chat_message(task_id, role, content, created_at) VALUES (?, ?, ?, ?)",
            ).use { statement ->
                messages.forEachIndexed { index, message ->
                    statement.setLong(1, taskId)
                    statement.setString(2, message.role.name)
                    statement.setString(3, message.content)
                    statement.setString(4, createdAt.plusNanos(index.toLong()).toString())
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        })
    }

    private fun insertUsage(taskId: Long, requestUsage: LlmRequestUsage) {
        jdbcTemplate.update(
            """
            INSERT INTO llm_request_usage(
                task_id, provider, model, input_tokens, output_tokens,
                total_tokens, response_time_ms, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            { statement ->
                statement.setLong(1, taskId)
                statement.setString(2, requestUsage.provider.name)
                statement.setString(3, requestUsage.model)
                statement.setNullableLong(4, requestUsage.tokenUsage.inputTokens)
                statement.setNullableLong(5, requestUsage.tokenUsage.outputTokens)
                statement.setNullableLong(6, requestUsage.tokenUsage.totalTokens)
                statement.setLong(7, requestUsage.responseTimeMs)
                statement.setString(8, Instant.now().toString())
            },
        )
    }

    private fun addTaskIdColumnIfMissing(table: String) {
        val columns = jdbcTemplate.queryForList("PRAGMA table_info($table)")
            .mapNotNull { row -> row["name"]?.toString() }
        if ("task_id" !in columns) {
            jdbcTemplate.execute("ALTER TABLE $table ADD COLUMN task_id INTEGER NOT NULL DEFAULT 1")
        }
    }

    private fun java.sql.PreparedStatement.setNullableLong(index: Int, value: Long?) {
        if (value == null) {
            setNull(index, Types.BIGINT)
        } else {
            setLong(index, value)
        }
    }
}
