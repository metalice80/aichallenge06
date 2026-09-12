package com.example.aiagent.persistence

import com.example.aiagent.agent.ChatMessage
import com.example.aiagent.agent.Conversation
import com.example.aiagent.agent.Role
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
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

        return Conversation().apply {
            addAll(messages)
        }
    }

    @Transactional
    override fun save(conversation: Conversation) {
        val messages = conversation.messages()
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

    @Transactional
    override fun clear() {
        jdbcTemplate.update("DELETE FROM chat_message")
    }
}
