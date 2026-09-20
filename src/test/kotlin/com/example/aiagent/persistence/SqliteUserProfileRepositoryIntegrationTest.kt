package com.example.aiagent.persistence

import com.example.aiagent.agent.ChatMessage
import com.example.aiagent.agent.Conversation
import com.example.aiagent.agent.Role
import com.example.aiagent.context.ContextStateService
import com.example.aiagent.context.branch.ConversationBranchService
import com.example.aiagent.memory.MemoryEntry
import com.example.aiagent.memory.MemoryLayerUpdate
import com.example.aiagent.memory.MemoryUpdate
import com.example.aiagent.profile.ExpertiseLevel
import com.example.aiagent.profile.ResponseFormat
import com.example.aiagent.profile.ResponseLanguage
import com.example.aiagent.profile.ResponseStyle
import com.example.aiagent.profile.UserProfileInput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.sqlite.SQLiteDataSource
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Path

class SqliteUserProfileRepositoryIntegrationTest {
    @TempDir
    lateinit var tempDirectory: Path

    @Test
    fun `multiple Profiles settings active selection and edits survive restart`() {
        val databasePath = tempDirectory.resolve("profiles-restart.db")
        val repository = SqliteUserProfileRepository(jdbcTemplate(databasePath))
        val developer = repository.create(
            profile("Developer", ExpertiseLevel.ADVANCED, ResponseStyle.CONCISE, ResponseFormat.CODE_FIRST),
        )
        val student = repository.create(
            profile("Student", ExpertiseLevel.BEGINNER, ResponseStyle.EDUCATIONAL, ResponseFormat.STEP_BY_STEP),
        )
        repository.create(
            profile("Manager", ExpertiseLevel.INTERMEDIATE, ResponseStyle.CONCISE, ResponseFormat.STRUCTURED),
        )
        repository.activate(student.id)
        repository.update(
            student.id,
            profile("Student", ExpertiseLevel.BEGINNER, ResponseStyle.DETAILED, ResponseFormat.STEP_BY_STEP),
        )

        val restored = SqliteUserProfileRepository(jdbcTemplate(databasePath))

        assertEquals(listOf("Developer", "Student", "Manager"), restored.findAll().map { it.name })
        assertEquals(student.id, restored.active()?.id)
        assertEquals(ResponseStyle.DETAILED, restored.active()?.responseStyle)
        assertEquals(ResponseFormat.CODE_FIRST, restored.findById(developer.id)?.responseFormat)
        assertEquals(1, restored.findAll().count { it.active })
    }

    @Test
    fun `Profile and Task switches plus chat reset preserve independent state`() {
        val databasePath = tempDirectory.resolve("profile-state-isolation.db")
        val jdbc = jdbcTemplate(databasePath)
        val profiles = SqliteUserProfileRepository(jdbc)
        val developer = profiles.create(
            profile("Developer", ExpertiseLevel.ADVANCED, ResponseStyle.CONCISE, ResponseFormat.CODE_FIRST),
        )
        val student = profiles.create(
            profile("Student", ExpertiseLevel.BEGINNER, ResponseStyle.EDUCATIONAL, ResponseFormat.STEP_BY_STEP),
        )
        val tasks = SqliteTaskRepository(jdbc)
        val taskA = tasks.create("Task A")
        val taskB = tasks.create("Task B")
        tasks.activate(taskA.id)
        val conversations = SqliteConversationRepository(jdbc)
        conversations.save(
            Conversation(taskA.id).apply {
                addAll(listOf(ChatMessage(Role.USER, "Question"), ChatMessage(Role.ASSISTANT, "Answer")))
            },
        )
        val memory = SqliteMemoryRepository(jdbc, jacksonObjectMapper())
        memory.apply(
            taskA.id,
            "Use Java",
            MemoryUpdate(
                working = MemoryLayerUpdate(upsert = listOf(MemoryEntry("language", "Java"))),
                longTerm = MemoryLayerUpdate(upsert = listOf(MemoryEntry("answer_language", "English"))),
            ),
        )

        profiles.activate(student.id)

        assertEquals(taskA.id, tasks.active().id)
        assertEquals(2, conversations.load(taskA.id).messages().size)
        assertEquals(listOf(MemoryEntry("language", "Java")), memory.findWorking(taskA.id))
        assertEquals(listOf(MemoryEntry("answer_language", "English")), memory.findLongTerm())

        tasks.activate(taskB.id)
        assertEquals(student.id, profiles.active()?.id)
        tasks.activate(taskA.id)
        ContextStateService(
            conversations,
            SqliteMemoryFactRepository(jdbc),
            ConversationBranchService(SqliteConversationBranchRepository(jdbc)),
        ).reset(taskA.id)

        assertTrue(conversations.load(taskA.id).messages().isEmpty())
        assertEquals(student.id, profiles.active()?.id)
        assertEquals(listOf(MemoryEntry("language", "Java")), memory.findWorking(taskA.id))
        assertEquals(listOf(MemoryEntry("answer_language", "English")), memory.findLongTerm())
        assertEquals(developer.id, profiles.findById(developer.id)?.id)
    }

    private fun profile(
        name: String,
        expertise: ExpertiseLevel,
        style: ResponseStyle,
        format: ResponseFormat,
    ) = UserProfileInput(
        name = name,
        responseLanguage = ResponseLanguage.RUSSIAN,
        expertiseLevel = expertise,
        responseStyle = style,
        responseFormat = format,
        customInstructions = "Profile $name instructions",
    )

    private fun jdbcTemplate(databasePath: Path): JdbcTemplate {
        val dataSource = SQLiteDataSource().apply {
            url = "jdbc:sqlite:${databasePath.toAbsolutePath()}"
        }
        return JdbcTemplate(dataSource)
    }
}
