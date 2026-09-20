package com.example.aiagent.persistence

import com.example.aiagent.agent.ChatMessage
import com.example.aiagent.agent.Conversation
import com.example.aiagent.agent.ConversationTokenUsage
import com.example.aiagent.agent.ConversationSummary
import com.example.aiagent.agent.LlmRequestUsage
import com.example.aiagent.agent.Role
import com.example.aiagent.llm.LlmProvider
import com.example.aiagent.llm.TokenUsage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
        val restoredConversation = repository(databasePath).load(1)

        assertEquals(conversation.messages(), restoredConversation.messages())
        assertEquals(ConversationTokenUsage.ZERO, restoredConversation.tokenUsage())
    }

    @Test
    fun `per-request usage accumulates across providers models and repository recreation`() {
        val databasePath = tempDirectory.resolve("usage-restart.db")
        val firstRepository = repository(databasePath)
        val conversation = Conversation().apply {
            addAll(
                listOf(
                    ChatMessage(Role.USER, "Первый вопрос"),
                    ChatMessage(Role.ASSISTANT, "Первый ответ"),
                ),
            )
        }
        firstRepository.save(
            conversation,
            requestUsage(
                provider = LlmProvider.OPENAI,
                model = "gpt-test",
                usage = TokenUsage(100, 20, 120),
            ),
        )
        conversation.addAll(
            listOf(
                ChatMessage(Role.USER, "Второй вопрос"),
                ChatMessage(Role.ASSISTANT, "Второй ответ"),
            ),
        )
        firstRepository.save(
            conversation,
            requestUsage(
                provider = LlmProvider.OPENROUTER,
                model = "anthropic/claude-test",
                usage = TokenUsage(180, 40, 220),
            ),
        )

        val restoredConversation = repository(databasePath).load(1)

        assertEquals(conversation.messages(), restoredConversation.messages())
        assertEquals(ConversationTokenUsage(280, 60, 340), restoredConversation.tokenUsage())
        assertEquals(2, jdbcTemplate(databasePath).queryForObject("SELECT COUNT(*) FROM llm_request_usage", Int::class.java))
        assertEquals(
            listOf("OPENAI", "OPENROUTER"),
            jdbcTemplate(databasePath).queryForList(
                "SELECT provider FROM llm_request_usage ORDER BY id",
                String::class.java,
            ),
        )
    }

    @Test
    fun `latest rolling summary and cursor survive repository recreation`() {
        val databasePath = tempDirectory.resolve("summary-restart.db")
        val firstRepository = repository(databasePath)
        val conversation = Conversation().apply {
            addAll(
                (1..30).map { number ->
                    ChatMessage(
                        role = if (number % 2 == 1) Role.USER else Role.ASSISTANT,
                        content = "Message $number",
                    )
                },
            )
        }
        firstRepository.save(conversation)
        firstRepository.saveSummary(1, ConversationSummary("Summary v1", 10))

        val firstReload = repository(databasePath).load(1)

        assertEquals(30, firstReload.messages().size)
        assertEquals(ConversationSummary("Summary v1", 10), firstReload.summary())

        firstRepository.saveSummary(1, ConversationSummary("Summary v2", 20))
        val secondReload = repository(databasePath).load(1)

        assertEquals(30, secondReload.messages().size)
        assertEquals(ConversationSummary("Summary v2", 20), secondReload.summary())
        assertEquals(
            1,
            jdbcTemplate(databasePath).queryForObject(
                "SELECT COUNT(*) FROM task_conversation_summary WHERE task_id = 1",
                Int::class.java,
            ),
        )
    }

    @Test
    fun `clear removes persisted history and usage across repository recreation`() {
        val databasePath = tempDirectory.resolve("reset.db")
        val firstRepository = repository(databasePath)
        val conversation = Conversation().apply {
            addAll(
                listOf(
                    ChatMessage(Role.USER, "Старый вопрос"),
                    ChatMessage(Role.ASSISTANT, "Старый ответ"),
                ),
            )
        }
        firstRepository.save(
            conversation,
            requestUsage(LlmProvider.OPENAI, "gpt-test", TokenUsage(10, 5, 15)),
        )
        firstRepository.saveSummary(1, ConversationSummary("Old summary", 1))

        firstRepository.clear(1)
        val restoredConversation = repository(databasePath).load(1)

        assertTrue(restoredConversation.messages().isEmpty())
        assertEquals(ConversationTokenUsage.ZERO, restoredConversation.tokenUsage())
        assertNull(restoredConversation.summary())
        assertEquals(0, jdbcTemplate(databasePath).queryForObject("SELECT COUNT(*) FROM llm_request_usage", Int::class.java))
    }

    @Test
    fun `repository upgrades a database containing only the previous message schema`() {
        val databasePath = tempDirectory.resolve("legacy.db")
        val jdbcTemplate = jdbcTemplate(databasePath)
        jdbcTemplate.execute(
            """
            CREATE TABLE chat_message (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                created_at TEXT NOT NULL
            )
            """.trimIndent(),
        )
        jdbcTemplate.update(
            "INSERT INTO chat_message(role, content, created_at) VALUES (?, ?, ?)",
            Role.USER.name,
            "Сохраненное сообщение",
            "2026-01-01T00:00:00Z",
        )

        val restoredConversation = repository(databasePath).load(1)

        assertEquals(listOf(ChatMessage(Role.USER, "Сохраненное сообщение")), restoredConversation.messages())
        assertEquals(ConversationTokenUsage.ZERO, restoredConversation.tokenUsage())
        assertNull(restoredConversation.summary())
    }

    @Test
    fun `repository discards a legacy summary cursor beyond the migrated conversation`() {
        val databasePath = tempDirectory.resolve("legacy-invalid-summary.db")
        val jdbcTemplate = jdbcTemplate(databasePath)
        jdbcTemplate.execute(
            """
            CREATE TABLE chat_message (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                created_at TEXT NOT NULL
            )
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            CREATE TABLE conversation_summary (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                content TEXT NOT NULL,
                summarized_message_count INTEGER NOT NULL CHECK (summarized_message_count > 0),
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """.trimIndent(),
        )
        jdbcTemplate.update(
            "INSERT INTO chat_message(role, content, created_at) VALUES (?, ?, ?)",
            Role.USER.name,
            "Сохраненное сообщение",
            "2026-01-01T00:00:00Z",
        )
        jdbcTemplate.update(
            """
            INSERT INTO conversation_summary(
                id, content, summarized_message_count, created_at, updated_at
            ) VALUES (1, ?, 10, ?, ?)
            """.trimIndent(),
            "Устаревшее summary",
            "2026-01-01T00:00:00Z",
            "2026-01-01T00:00:00Z",
        )

        val restoredConversation = repository(databasePath).load(1)

        assertEquals(listOf(ChatMessage(Role.USER, "Сохраненное сообщение")), restoredConversation.messages())
        assertNull(restoredConversation.summary())
        assertEquals(
            0,
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_conversation_summary WHERE task_id = 1",
                Int::class.java,
            ),
        )
        assertNull(repository(databasePath).load(1).summary())
    }

    private fun requestUsage(
        provider: LlmProvider,
        model: String,
        usage: TokenUsage,
    ) = LlmRequestUsage(
        provider = provider,
        model = model,
        tokenUsage = usage,
        responseTimeMs = 125,
    )

    private fun repository(databasePath: Path) =
        SqliteConversationRepository(jdbcTemplate(databasePath))

    private fun jdbcTemplate(databasePath: Path): JdbcTemplate {
        val dataSource = SQLiteDataSource().apply {
            url = "jdbc:sqlite:${databasePath.toAbsolutePath()}"
        }
        return JdbcTemplate(dataSource)
    }
}
