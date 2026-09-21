package com.example.aiagent.persistence

import com.example.aiagent.agent.ChatMessage
import com.example.aiagent.agent.Conversation
import com.example.aiagent.agent.Role
import com.example.aiagent.context.ContextStateService
import com.example.aiagent.context.branch.ConversationBranchService
import com.example.aiagent.context.strategy.ContextStrategyType
import com.example.aiagent.invariant.InvariantType
import com.example.aiagent.invariant.TaskInvariantSnapshot
import com.example.aiagent.memory.EffectiveContext
import com.example.aiagent.memory.MemoryChangeType
import com.example.aiagent.memory.MemoryEntry
import com.example.aiagent.memory.MemoryLayerUpdate
import com.example.aiagent.memory.MemoryUpdate
import com.example.aiagent.profile.ExpertiseLevel
import com.example.aiagent.profile.ResponseFormat
import com.example.aiagent.profile.ResponseLanguage
import com.example.aiagent.profile.ResponseStyle
import com.example.aiagent.profile.UserProfileSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.sqlite.SQLiteDataSource
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Path
import java.time.Instant

class SqliteMemoryLayersIntegrationTest {
    @TempDir
    lateinit var tempDirectory: Path

    @Test
    fun `Task conversations and working memory are isolated while long-term memory survives switching and restart`() {
        val databasePath = tempDirectory.resolve("memory-restart.db")
        val firstJdbc = jdbcTemplate(databasePath)
        val firstTasks = SqliteTaskRepository(firstJdbc)
        val conversations = SqliteConversationRepository(firstJdbc)
        val memory = SqliteMemoryRepository(firstJdbc, jacksonObjectMapper())
        val taskA = firstTasks.create("Task A")
        conversations.save(
            Conversation(taskA.id).apply {
                addAll(listOf(ChatMessage(Role.USER, "A question"), ChatMessage(Role.ASSISTANT, "A answer")))
            },
        )
        memory.apply(
            taskA.id,
            "Use Kotlin and PostgreSQL",
            MemoryUpdate(
                working = MemoryLayerUpdate(
                    upsert = listOf(
                        MemoryEntry("language", "Kotlin"),
                        MemoryEntry("database", "PostgreSQL"),
                    ),
                ),
                longTerm = MemoryLayerUpdate(
                    upsert = listOf(
                        MemoryEntry("preferred_answer_language", "Russian"),
                        MemoryEntry("preferred_code_language", "Kotlin"),
                    ),
                ),
            ),
        )

        val taskB = firstTasks.create("Task B")
        conversations.save(
            Conversation(taskB.id).apply {
                addAll(listOf(ChatMessage(Role.USER, "B question"), ChatMessage(Role.ASSISTANT, "B answer")))
            },
        )
        memory.apply(
            taskB.id,
            "Use Java and MySQL",
            MemoryUpdate(
                working = MemoryLayerUpdate(
                    upsert = listOf(
                        MemoryEntry("language", "Java"),
                        MemoryEntry("database", "MySQL"),
                    ),
                ),
            ),
        )
        firstTasks.activate(taskA.id)

        val restoredJdbc = jdbcTemplate(databasePath)
        val restoredTasks = SqliteTaskRepository(restoredJdbc)
        val restoredConversations = SqliteConversationRepository(restoredJdbc)
        val restoredMemory = SqliteMemoryRepository(restoredJdbc, jacksonObjectMapper())

        assertEquals(taskA.id, restoredTasks.active().id)
        assertEquals(listOf("A question", "A answer"), restoredConversations.load(taskA.id).messages().map { it.content })
        assertEquals(listOf("B question", "B answer"), restoredConversations.load(taskB.id).messages().map { it.content })
        assertEquals(
            listOf(MemoryEntry("database", "PostgreSQL"), MemoryEntry("language", "Kotlin")),
            restoredMemory.findWorking(taskA.id),
        )
        assertEquals(
            listOf(MemoryEntry("database", "MySQL"), MemoryEntry("language", "Java")),
            restoredMemory.findWorking(taskB.id),
        )
        assertEquals(
            listOf(
                MemoryEntry("preferred_answer_language", "Russian"),
                MemoryEntry("preferred_code_language", "Kotlin"),
            ),
            restoredMemory.findLongTerm(),
        )
        assertEquals(taskB.id, restoredTasks.activate(taskB.id).id)
        assertEquals(taskA.id, restoredTasks.activate(taskA.id).id)
    }

    @Test
    fun `stable keys update without duplicates delete cleanly and layer clears stay separate`() {
        val databasePath = tempDirectory.resolve("memory-upsert.db")
        val jdbc = jdbcTemplate(databasePath)
        val tasks = SqliteTaskRepository(jdbc)
        val memory = SqliteMemoryRepository(jdbc, jacksonObjectMapper())
        val taskA = tasks.create("Task A")
        val taskB = tasks.create("Task B")
        memory.apply(
            taskA.id,
            "Initial values",
            MemoryUpdate(
                working = MemoryLayerUpdate(
                    upsert = listOf(MemoryEntry("database", "PostgreSQL"), MemoryEntry("language", "Kotlin")),
                ),
                longTerm = MemoryLayerUpdate(upsert = listOf(MemoryEntry("answer_language", "Russian"))),
            ),
        )
        memory.apply(
            taskB.id,
            "Task B database",
            MemoryUpdate(working = MemoryLayerUpdate(upsert = listOf(MemoryEntry("database", "MySQL")))),
        )

        val update = memory.apply(
            taskA.id,
            "Use MySQL and forget language",
            MemoryUpdate(
                working = MemoryLayerUpdate(
                    upsert = listOf(MemoryEntry("database", "MySQL")),
                    delete = listOf("language"),
                ),
            ),
        )

        assertEquals(listOf(MemoryEntry("database", "MySQL")), memory.findWorking(taskA.id))
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM working_memory WHERE task_id = ?", Int::class.java, taskA.id))
        assertEquals(setOf(MemoryChangeType.UPDATED, MemoryChangeType.DELETED), update.working.map { it.type }.toSet())
        assertEquals(update, memory.lastUpdate(taskA.id))

        memory.clearWorking(taskA.id)
        assertTrue(memory.findWorking(taskA.id).isEmpty())
        assertEquals(listOf(MemoryEntry("database", "MySQL")), memory.findWorking(taskB.id))
        assertEquals(listOf(MemoryEntry("answer_language", "Russian")), memory.findLongTerm())

        memory.clearLongTerm()
        assertTrue(memory.findLongTerm().isEmpty())
        assertEquals(listOf(MemoryEntry("database", "MySQL")), memory.findWorking(taskB.id))
    }

    @Test
    fun `short-term reset preserves both memory layers and other Task conversations`() {
        val databasePath = tempDirectory.resolve("task-reset.db")
        val jdbc = jdbcTemplate(databasePath)
        val tasks = SqliteTaskRepository(jdbc)
        val conversations = SqliteConversationRepository(jdbc)
        val facts = SqliteMemoryFactRepository(jdbc)
        val branches = ConversationBranchService(SqliteConversationBranchRepository(jdbc))
        val memory = SqliteMemoryRepository(jdbc, jacksonObjectMapper())
        val taskA = tasks.create("Task A")
        val taskB = tasks.create("Task B")
        conversations.save(Conversation(taskA.id).apply { add(ChatMessage(Role.USER, "A")) })
        conversations.save(Conversation(taskB.id).apply { add(ChatMessage(Role.USER, "B")) })
        memory.apply(
            taskA.id,
            "remember",
            MemoryUpdate(
                working = MemoryLayerUpdate(upsert = listOf(MemoryEntry("database", "PostgreSQL"))),
                longTerm = MemoryLayerUpdate(upsert = listOf(MemoryEntry("language", "Russian"))),
            ),
        )
        branches.activeHistory(taskA.id, conversations.load(taskA.id).messages())

        ContextStateService(conversations, facts, branches).reset(taskA.id)

        assertTrue(conversations.load(taskA.id).messages().isEmpty())
        assertEquals(listOf("B"), conversations.load(taskB.id).messages().map { it.content })
        assertEquals(listOf(MemoryEntry("database", "PostgreSQL")), memory.findWorking(taskA.id))
        assertEquals(listOf(MemoryEntry("language", "Russian")), memory.findLongTerm())
        val taskABranches = branches.branches(taskA.id)
        assertEquals(1, taskABranches.size)
        assertEquals("Main", taskABranches.single().name)
        assertNull(taskABranches.single().parentBranchId)
    }

    @Test
    fun `branch histories remain isolated between Tasks`() {
        val databasePath = tempDirectory.resolve("task-branches.db")
        val jdbc = jdbcTemplate(databasePath)
        val tasks = SqliteTaskRepository(jdbc)
        val branches = ConversationBranchService(SqliteConversationBranchRepository(jdbc))
        val taskA = tasks.create("Task A")
        val taskB = tasks.create("Task B")
        val historyA = listOf(ChatMessage(Role.USER, "A root"), ChatMessage(Role.ASSISTANT, "A answer"))
        val historyB = listOf(ChatMessage(Role.USER, "B root"), ChatMessage(Role.ASSISTANT, "B answer"))
        branches.activeHistory(taskA.id, historyA)
        branches.activeHistory(taskB.id, historyB)
        val childA = branches.createBranch(taskA.id, historyA)
        branches.appendToActive(taskA.id, listOf(ChatMessage(Role.USER, "A branch")))

        assertEquals(historyA + ChatMessage(Role.USER, "A branch"), branches.activeHistory(taskA.id, emptyList()))
        assertEquals(historyB, branches.activeHistory(taskB.id, emptyList()))
        assertNotEquals(branches.branches(taskB.id).single().id, childA.id)
    }

    @Test
    fun `effective context profile column migrates and logical sections survive recreation`() {
        val databasePath = tempDirectory.resolve("effective-context.db")
        val jdbc = jdbcTemplate(databasePath)
        jdbc.execute(
            """
            CREATE TABLE effective_context (
                task_id INTEGER PRIMARY KEY,
                strategy TEXT NOT NULL,
                system_prompt TEXT NOT NULL,
                long_term_memory TEXT NOT NULL,
                working_memory TEXT NOT NULL,
                short_term TEXT NOT NULL,
                current_user_message TEXT NOT NULL,
                prepared_at TEXT NOT NULL
            )
            """.trimIndent(),
        )
        val tasks = SqliteTaskRepository(jdbc)
        val task = tasks.create("Task A")
        val prepared = EffectiveContext(
            taskId = task.id,
            strategy = ContextStrategyType.SLIDING_WINDOW,
            systemPrompt = "System",
            longTermMemory = listOf(MemoryEntry("preferred_language", "Russian")),
            userProfile = UserProfileSnapshot(
                id = 3,
                name = "Developer",
                responseLanguage = ResponseLanguage.RUSSIAN,
                expertiseLevel = ExpertiseLevel.ADVANCED,
                responseStyle = ResponseStyle.CONCISE,
                responseFormat = ResponseFormat.CODE_FIRST,
                customInstructions = "Prefer Kotlin.",
            ),
            workingMemory = listOf(MemoryEntry("database", "PostgreSQL")),
            taskState = task.stateSnapshot(),
            taskInvariants = listOf(
                TaskInvariantSnapshot(
                    id = 17,
                    taskId = task.id,
                    taskName = task.name,
                    type = InvariantType.TECHNICAL_DECISION,
                    key = "database",
                    value = "PostgreSQL",
                    description = "Primary relational database",
                ),
            ),
            shortTerm = listOf(ChatMessage(Role.ASSISTANT, "Previous answer")),
            currentUserMessage = ChatMessage(Role.USER, "Current question"),
            preparedAt = Instant.parse("2026-01-01T00:00:00Z"),
        )

        SqliteMemoryRepository(jdbc, jacksonObjectMapper()).saveEffectiveContext(prepared)
        val restored = SqliteMemoryRepository(
            jdbcTemplate(databasePath),
            jacksonObjectMapper(),
        ).effectiveContext(task.id)

        assertEquals(prepared, restored)
    }

    private fun jdbcTemplate(databasePath: Path): JdbcTemplate {
        val dataSource = SQLiteDataSource().apply {
            url = "jdbc:sqlite:${databasePath.toAbsolutePath()}"
        }
        return JdbcTemplate(dataSource)
    }
}
