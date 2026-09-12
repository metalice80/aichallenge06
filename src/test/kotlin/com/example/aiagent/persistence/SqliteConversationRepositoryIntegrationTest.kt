package com.example.aiagent.persistence

import com.example.aiagent.agent.ChatMessage
import com.example.aiagent.agent.Conversation
import com.example.aiagent.agent.Role
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.sqlite.SQLiteDataSource
import org.springframework.jdbc.core.JdbcTemplate
import java.nio.file.Path

class SqliteConversationRepositoryIntegrationTest {
    @TempDir
    lateinit var tempDirectory: Path

    @Test
    fun `conversation survives repository recreation`() {
        val databasePath = tempDirectory.resolve("restart.db")
        val firstRepository = repository(databasePath)
        val conversation = Conversation().apply {
            addAll(
                listOf(
                    ChatMessage(Role.USER, "Меня зовут Алексей"),
                    ChatMessage(Role.ASSISTANT, "Приятно познакомиться, Алексей"),
                    ChatMessage(Role.USER, "Я изучаю Kotlin"),
                    ChatMessage(Role.ASSISTANT, "Отличный выбор"),
                ),
            )
        }

        firstRepository.save(conversation)
        val restoredConversation = repository(databasePath).load()

        assertEquals(conversation.messages(), restoredConversation.messages())
    }

    @Test
    fun `clear removes persisted history across repository recreation`() {
        val databasePath = tempDirectory.resolve("reset.db")
        val firstRepository = repository(databasePath)
        firstRepository.save(
            Conversation().apply {
                addAll(
                    listOf(
                        ChatMessage(Role.USER, "Старый вопрос"),
                        ChatMessage(Role.ASSISTANT, "Старый ответ"),
                    ),
                )
            },
        )

        firstRepository.clear()
        val restoredConversation = repository(databasePath).load()

        assertTrue(restoredConversation.messages().isEmpty())
    }

    private fun repository(databasePath: Path): SqliteConversationRepository {
        val dataSource = SQLiteDataSource().apply {
            url = "jdbc:sqlite:${databasePath.toAbsolutePath()}"
        }
        return SqliteConversationRepository(JdbcTemplate(dataSource))
    }
}
