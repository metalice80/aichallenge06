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
        addTaskIdColumnIfMissing()
        jdbcTemplate.execute(
            "CREATE INDEX IF NOT EXISTS idx_conversation_branch_task_id ON conversation_branch(task_id, id)",
        )
        ensureTaskBranch(1)
    }

    @Transactional
    override fun ensureMainInitialized(taskId: Long, seedHistory: List<ChatMessage>) {
        ensureTaskBranch(taskId)
        val main = jdbcTemplate.query(
            """
            SELECT id, initialized
            FROM conversation_branch
            WHERE task_id = ? AND parent_branch_id IS NULL
            ORDER BY id LIMIT 1
            """.trimIndent(),
            { resultSet, _ -> resultSet.getLong("id") to resultSet.getBoolean("initialized") },
            taskId,
        ).firstOrNull() ?: error("Main branch is missing for Task $taskId")
        if (main.second) {
            return
        }
        insertMessages(main.first, seedHistory)
        jdbcTemplate.update("UPDATE conversation_branch SET initialized = 1 WHERE id = ?", main.first)
    }

    @Transactional
    override fun findAll(taskId: Long): List<ConversationBranch> {
        ensureTaskBranch(taskId)
        return jdbcTemplate.query(
            """
            SELECT id, name, parent_branch_id, checkpoint_message_count, active
            FROM conversation_branch
            WHERE task_id = ?
            ORDER BY id
            """.trimIndent(),
            { resultSet, _ ->
                ConversationBranch(
                    id = resultSet.getLong("id"),
                    name = resultSet.getString("name"),
                    parentBranchId = resultSet.getLong("parent_branch_id").let { value ->
                        if (resultSet.wasNull()) null else value
                    },
                    checkpointMessageCount = resultSet.getInt("checkpoint_message_count"),
                    active = resultSet.getBoolean("active"),
                )
            },
            taskId,
        )
    }

    override fun activeBranch(taskId: Long): ConversationBranch =
        findAll(taskId).singleOrNull(ConversationBranch::active)
            ?: error("Exactly one active conversation branch is required for Task $taskId")

    override fun effectiveHistory(taskId: Long, branchId: Long): List<ChatMessage> =
        effectiveHistory(taskId, branchId, mutableSetOf())

    @Transactional
    override fun createFromActive(taskId: Long): ConversationBranch {
        val parent = activeBranch(taskId)
        val checkpoint = effectiveHistory(taskId, parent.id).size
        val name = "Branch ${branchCount(taskId)}"
        jdbcTemplate.update("UPDATE conversation_branch SET active = 0 WHERE task_id = ?", taskId)
        jdbcTemplate.update(
            """
            INSERT INTO conversation_branch(
                task_id, name, parent_branch_id, checkpoint_message_count,
                active, initialized, created_at
            ) VALUES (?, ?, ?, ?, 1, 1, ?)
            """.trimIndent(),
            taskId,
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
    override fun activate(taskId: Long, branchId: Long) {
        require(branchExists(taskId, branchId)) {
            "Conversation branch $branchId does not exist in Task $taskId"
        }
        jdbcTemplate.update("UPDATE conversation_branch SET active = 0 WHERE task_id = ?", taskId)
        jdbcTemplate.update(
            "UPDATE conversation_branch SET active = 1 WHERE task_id = ? AND id = ?",
            taskId,
            branchId,
        )
    }

    @Transactional
    override fun appendToActive(taskId: Long, messages: List<ChatMessage>) {
        if (messages.isEmpty()) {
            return
        }
        insertMessages(activeBranch(taskId).id, messages)
    }

    @Transactional
    override fun reset(taskId: Long) {
        jdbcTemplate.update(
            "DELETE FROM branch_message WHERE branch_id IN (SELECT id FROM conversation_branch WHERE task_id = ?)",
            taskId,
        )
        jdbcTemplate.update("DELETE FROM conversation_branch WHERE task_id = ?", taskId)
        insertMainBranch(taskId)
    }

    private fun effectiveHistory(
        taskId: Long,
        branchId: Long,
        visited: MutableSet<Long>,
    ): List<ChatMessage> {
        check(visited.add(branchId)) { "Conversation branch ancestry contains a cycle" }
        val branch = findAll(taskId).firstOrNull { it.id == branchId }
            ?: error("Conversation branch $branchId does not exist in Task $taskId")
        val inherited = branch.parentBranchId?.let { parentId ->
            effectiveHistory(taskId, parentId, visited).take(branch.checkpointMessageCount)
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

    private fun insertMessages(branchId: Long, messages: List<ChatMessage>) {
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

    private fun branchCount(taskId: Long): Int = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM conversation_branch WHERE task_id = ?",
        Int::class.java,
        taskId,
    ) ?: 0

    private fun branchExists(taskId: Long, branchId: Long): Boolean =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM conversation_branch WHERE task_id = ? AND id = ?",
            Int::class.java,
            taskId,
            branchId,
        ) == 1

    private fun ensureTaskBranch(taskId: Long) {
        if (branchCount(taskId) == 0) {
            insertMainBranch(taskId)
        }
    }

    private fun insertMainBranch(taskId: Long) {
        jdbcTemplate.update(
            """
            INSERT INTO conversation_branch(
                task_id, name, parent_branch_id, checkpoint_message_count,
                active, initialized, created_at
            ) VALUES (?, 'Main', NULL, 0, 1, 0, ?)
            """.trimIndent(),
            taskId,
            Instant.now().toString(),
        )
    }

    private fun addTaskIdColumnIfMissing() {
        val columns = jdbcTemplate.queryForList("PRAGMA table_info(conversation_branch)")
            .mapNotNull { row -> row["name"]?.toString() }
        if ("task_id" !in columns) {
            jdbcTemplate.execute(
                "ALTER TABLE conversation_branch ADD COLUMN task_id INTEGER NOT NULL DEFAULT 1",
            )
        }
    }
}
