package com.example.aiagent.persistence

import com.example.aiagent.agent.ChatMessage
import com.example.aiagent.agent.Conversation
import com.example.aiagent.agent.LlmRequestUsage
import com.example.aiagent.agent.Role
import com.example.aiagent.context.ContextStateService
import com.example.aiagent.context.branch.ConversationBranchService
import com.example.aiagent.context.facts.FactsUpdate
import com.example.aiagent.context.facts.MemoryFact
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

class SqliteContextStateIntegrationTest {
    @TempDir
    lateinit var tempDirectory: Path

    @Test
    fun `sticky facts upsert delete and values survive repository recreation`() {
        val databasePath = tempDirectory.resolve("facts-restart.db")
        val firstRepository = SqliteMemoryFactRepository(jdbcTemplate(databasePath))
        firstRepository.apply(
            FactsUpdate(
                upsert = listOf(
                    MemoryFact("language", "Java"),
                    MemoryFact("database", "SQLite"),
                ),
            ),
        )
        firstRepository.apply(
            FactsUpdate(
                upsert = listOf(MemoryFact("language", "Kotlin")),
                deleteKeys = listOf("database"),
            ),
        )

        val restoredRepository = SqliteMemoryFactRepository(jdbcTemplate(databasePath))

        assertEquals(listOf(MemoryFact("language", "Kotlin")), restoredRepository.findAll())
    }

    @Test
    fun `two child branches diverge from the same persisted parent checkpoint`() {
        val databasePath = tempDirectory.resolve("branches-restart.db")
        val firstJdbcTemplate = jdbcTemplate(databasePath)
        val firstService = ConversationBranchService(SqliteConversationBranchRepository(firstJdbcTemplate))
        val commonHistory = listOf(
            ChatMessage(Role.USER, "Root question"),
            ChatMessage(Role.ASSISTANT, "Root answer"),
        )
        SqliteConversationRepository(firstJdbcTemplate).save(
            Conversation().apply { addAll(commonHistory) },
            LlmRequestUsage(
                provider = LlmProvider.OPENAI,
                model = "main-model",
                tokenUsage = TokenUsage(12, 4, 16),
                responseTimeMs = 30,
            ),
        )
        val mainId = firstService.branches().single().id
        firstService.activeHistory(commonHistory)
        val mainExtension = listOf(
            ChatMessage(Role.USER, "Main question"),
            ChatMessage(Role.ASSISTANT, "Main answer"),
        )
        firstService.appendToActive(mainExtension)

        val firstChild = firstService.createBranch(emptyList())
        val firstDivergence = listOf(
            ChatMessage(Role.USER, "Choose option A"),
            ChatMessage(Role.ASSISTANT, "A selected"),
        )
        firstService.appendToActive(firstDivergence)

        firstService.activateBranch(mainId, emptyList())
        val secondChild = firstService.createBranch(emptyList())
        val secondDivergence = listOf(
            ChatMessage(Role.USER, "Choose option B"),
            ChatMessage(Role.ASSISTANT, "B selected"),
        )
        firstService.appendToActive(secondDivergence)

        val restoredJdbcTemplate = jdbcTemplate(databasePath)
        val restoredService = ConversationBranchService(SqliteConversationBranchRepository(restoredJdbcTemplate))
        val expectedParentHistory = commonHistory + mainExtension

        assertEquals(secondChild.id, restoredService.branches().single { it.active }.id)
        assertEquals(expectedParentHistory + secondDivergence, restoredService.activeHistory(emptyList()))
        assertEquals(expectedParentHistory + firstDivergence, restoredService.activateBranch(firstChild.id, emptyList()))
        assertEquals(expectedParentHistory, restoredService.activateBranch(mainId, emptyList()))
        assertEquals(mainId, firstChild.parentBranchId)
        assertEquals(mainId, secondChild.parentBranchId)
        assertEquals(expectedParentHistory.size, firstChild.checkpointMessageCount)
        assertEquals(expectedParentHistory.size, secondChild.checkpointMessageCount)
        assertEquals(
            8,
            restoredJdbcTemplate.queryForObject("SELECT COUNT(*) FROM branch_message", Int::class.java),
            "Child branches must store only their own messages, not inherited copies",
        )
        assertEquals(
            1,
            restoredJdbcTemplate.queryForObject("SELECT COUNT(*) FROM llm_request_usage", Int::class.java),
            "Creating or activating branches must not duplicate token usage",
        )
    }

    @Test
    fun `reset clears conversation facts and branch graph and creates a fresh Main`() {
        val databasePath = tempDirectory.resolve("full-reset.db")
        val jdbcTemplate = jdbcTemplate(databasePath)
        val conversationRepository = SqliteConversationRepository(jdbcTemplate)
        val factsRepository = SqliteMemoryFactRepository(jdbcTemplate)
        val branchService = ConversationBranchService(SqliteConversationBranchRepository(jdbcTemplate))
        val conversation = Conversation().apply {
            addAll(
                listOf(
                    ChatMessage(Role.USER, "Question"),
                    ChatMessage(Role.ASSISTANT, "Answer"),
                ),
            )
        }
        conversationRepository.save(
            conversation,
            LlmRequestUsage(
                provider = LlmProvider.OPENAI,
                model = "test-model",
                tokenUsage = TokenUsage(10, 5, 15),
                responseTimeMs = 20,
            ),
        )
        factsRepository.apply(FactsUpdate(upsert = listOf(MemoryFact("language", "Kotlin"))))
        branchService.activeHistory(conversation.messages())
        branchService.createBranch(emptyList())

        ContextStateService(conversationRepository, factsRepository, branchService).reset()

        val restoredConversation = SqliteConversationRepository(jdbcTemplate(databasePath)).load()
        val restoredFacts = SqliteMemoryFactRepository(jdbcTemplate(databasePath)).findAll()
        val restoredBranches = ConversationBranchService(
            SqliteConversationBranchRepository(jdbcTemplate(databasePath)),
        ).branches()
        assertTrue(restoredConversation.messages().isEmpty())
        assertEquals(0, restoredConversation.tokenUsage().totalTokens)
        assertNull(restoredConversation.summary())
        assertTrue(restoredFacts.isEmpty())
        assertEquals(1, restoredBranches.size)
        assertEquals("Main", restoredBranches.single().name)
        assertNull(restoredBranches.single().parentBranchId)
        assertTrue(restoredBranches.single().active)
    }

    private fun jdbcTemplate(databasePath: Path): JdbcTemplate {
        val dataSource = SQLiteDataSource().apply {
            url = "jdbc:sqlite:${databasePath.toAbsolutePath()}"
        }
        return JdbcTemplate(dataSource)
    }
}
