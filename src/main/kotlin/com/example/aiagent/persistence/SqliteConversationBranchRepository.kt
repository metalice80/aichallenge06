package com.example.aiagent.persistence

import com.example.aiagent.agent.ChatMessage
import com.example.aiagent.agent.Role
import com.example.aiagent.context.branch.ConversationBranch
import com.example.aiagent.context.branch.ConversationBranchRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Repository
class SqliteConversationBranchRepository(
    private val jdbcTemplate: JdbcTemplate,
) : ConversationBranchRepository {
    init {
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS conversation_branch (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                parent_branch_id INTEGER,
                checkpoint_message_count INTEGER NOT NULL,
                active INTEGER NOT NULL,
                initialized INTEGER NOT NULL,
                created_at TEXT NOT NULL
            )
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS branch_message (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                branch_id INTEGER NOT NULL,
                position INTEGER NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                created_at TEXT NOT NULL,
                UNIQUE(branch_id, position)
            )
            """.trimIndent(),
        )
        if (branchCount() == 0) {
            insertMainBranch()
        }
    }

    @Transactional
    override fun ensureMainInitialized(seedHistory: List<ChatMessage>) {
        val main = jdbcTemplate.query(
            "SELECT id, initialized FROM conversation_branch WHERE parent_branch_id IS NULL ORDER BY id LIMIT 1",
        ) { resultSet, _ -> resultSet.getLong("id") to resultSet.getBoolean("initialized") }
            .firstOrNull() ?: error("Main branch is missing")
        if (main.second) {
            return
        }
        insertMessages(main.first, seedHistory)
        jdbcTemplate.update("UPDATE conversation_branch SET initialized = 1 WHERE id = ?", main.first)
    }

    override fun findAll(): List<ConversationBranch> = jdbcTemplate.query(
        """
        SELECT id, name, parent_branch_id, checkpoint_message_count, active
        FROM conversation_branch
        ORDER BY id
        """.trimIndent(),
    ) { resultSet, _ ->
        ConversationBranch(
            id = resultSet.getLong("id"),
            name = resultSet.getString("name"),
            parentBranchId = resultSet.getLong("parent_branch_id").let { value ->
                if (resultSet.wasNull()) null else value
            },
            checkpointMessageCount = resultSet.getInt("checkpoint_message_count"),
            active = resultSet.getBoolean("active"),
        )
    }

    override fun activeBranch(): ConversationBranch =
        findAll().singleOrNull(ConversationBranch::active)
            ?: error("Exactly one active conversation branch is required")

    override fun effectiveHistory(branchId: Long): List<ChatMessage> =
        effectiveHistory(branchId, mutableSetOf())

    @Transactional
    override fun createFromActive(): ConversationBranch {
        val parent = activeBranch()
        val checkpoint = effectiveHistory(parent.id).size
        val name = "Branch ${branchCount()}"
        jdbcTemplate.update("UPDATE conversation_branch SET active = 0")
        jdbcTemplate.update(
            """
            INSERT INTO conversation_branch(
                name,
                parent_branch_id,
                checkpoint_message_count,
                active,
                initialized,
                created_at
            ) VALUES (?, ?, ?, 1, 1, ?)
            """.trimIndent(),
            name,
            parent.id,
            checkpoint,
            Instant.now().toString(),
        )
        val id = checkNotNull(
            jdbcTemplate.queryForObject("SELECT MAX(id) FROM conversation_branch", Long::class.java),
        )
        return ConversationBranch(id, name, parent.id, checkpoint, active = true)
    }

    @Transactional
    override fun activate(branchId: Long) {
        require(branchExists(branchId)) { "Conversation branch $branchId does not exist" }
        jdbcTemplate.update("UPDATE conversation_branch SET active = 0")
        jdbcTemplate.update("UPDATE conversation_branch SET active = 1 WHERE id = ?", branchId)
    }

    @Transactional
    override fun appendToActive(messages: List<ChatMessage>) {
        if (messages.isEmpty()) {
            return
        }
        insertMessages(activeBranch().id, messages)
    }

    @Transactional
    override fun reset() {
        jdbcTemplate.update("DELETE FROM branch_message")
        jdbcTemplate.update("DELETE FROM conversation_branch")
        insertMainBranch()
    }

    private fun effectiveHistory(
        branchId: Long,
        visited: MutableSet<Long>,
    ): List<ChatMessage> {
        check(visited.add(branchId)) { "Conversation branch ancestry contains a cycle" }
        val branch = findAll().firstOrNull { it.id == branchId }
            ?: error("Conversation branch $branchId does not exist")
        val inherited = branch.parentBranchId?.let { parentId ->
            effectiveHistory(parentId, visited).take(branch.checkpointMessageCount)
        }.orEmpty()
        return inherited + ownMessages(branchId)
    }

    private fun ownMessages(branchId: Long): List<ChatMessage> = jdbcTemplate.query(
        "SELECT role, content FROM branch_message WHERE branch_id = ? ORDER BY position",
        { resultSet, _ ->
            ChatMessage(
                role = Role.valueOf(resultSet.getString("role")),
                content = resultSet.getString("content"),
            )
        },
        branchId,
    )

    private fun insertMessages(
        branchId: Long,
        messages: List<ChatMessage>,
    ) {
        val firstPosition = jdbcTemplate.queryForObject(
            "SELECT COALESCE(MAX(position) + 1, 0) FROM branch_message WHERE branch_id = ?",
            Int::class.java,
            branchId,
        ) ?: 0
        val createdAt = Instant.now()
        messages.forEachIndexed { index, message ->
            jdbcTemplate.update(
                """
                INSERT INTO branch_message(branch_id, position, role, content, created_at)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
                branchId,
                firstPosition + index,
                message.role.name,
                message.content,
                createdAt.plusNanos(index.toLong()).toString(),
            )
        }
    }

    private fun branchCount(): Int =
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM conversation_branch", Int::class.java) ?: 0

    private fun branchExists(branchId: Long): Boolean =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM conversation_branch WHERE id = ?",
            Int::class.java,
            branchId,
        ) == 1

    private fun insertMainBranch() {
        jdbcTemplate.update(
            """
            INSERT INTO conversation_branch(
                name,
                parent_branch_id,
                checkpoint_message_count,
                active,
                initialized,
                created_at
            ) VALUES ('Main', NULL, 0, 1, 0, ?)
            """.trimIndent(),
            Instant.now().toString(),
        )
    }
}
