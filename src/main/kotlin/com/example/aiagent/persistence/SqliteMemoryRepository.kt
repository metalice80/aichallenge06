package com.example.aiagent.persistence

import com.example.aiagent.invariant.TaskInvariantSnapshot
import com.example.aiagent.agent.ChatMessage
import com.example.aiagent.context.strategy.ContextStrategyType
import com.example.aiagent.memory.EffectiveContext
import com.example.aiagent.memory.LastMemoryUpdate
import com.example.aiagent.memory.MemoryChange
import com.example.aiagent.memory.MemoryChangeType
import com.example.aiagent.memory.MemoryEntry
import com.example.aiagent.memory.MemoryLayerUpdate
import com.example.aiagent.memory.MemoryRepository
import com.example.aiagent.profile.UserProfileSnapshot
import com.example.aiagent.memory.MemoryUpdate
import com.example.aiagent.task.TaskStateSnapshot
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Instant

@Repository
class SqliteMemoryRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val jsonMapper: ObjectMapper,
) : MemoryRepository {
    init {
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS working_memory (
                task_id INTEGER NOT NULL,
                key TEXT NOT NULL,
                value TEXT NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                PRIMARY KEY(task_id, key)
            )
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS long_term_memory (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS last_memory_update (
                task_id INTEGER PRIMARY KEY,
                user_message TEXT NOT NULL,
                working_changes TEXT NOT NULL,
                long_term_changes TEXT NOT NULL,
                error TEXT,
                updated_at TEXT NOT NULL
            )
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS effective_context (
                task_id INTEGER PRIMARY KEY,
                strategy TEXT NOT NULL,
                system_prompt TEXT NOT NULL,
                long_term_memory TEXT NOT NULL,
                user_profile TEXT,
                working_memory TEXT NOT NULL,
                task_state TEXT,
                short_term TEXT NOT NULL,
                current_user_message TEXT NOT NULL,
                prepared_at TEXT NOT NULL
            )
            """.trimIndent(),
        )
        addColumnIfMissing("effective_context", "user_profile", "TEXT")
        addColumnIfMissing("effective_context", "task_state", "TEXT")
        addColumnIfMissing("effective_context", "task_invariants", "TEXT NOT NULL DEFAULT '[]'")
    }

    override fun findWorking(taskId: Long): List<MemoryEntry> = jdbcTemplate.query(
        "SELECT key, value FROM working_memory WHERE task_id = ? ORDER BY key",
        { resultSet, _ -> MemoryEntry(resultSet.getString("key"), resultSet.getString("value")) },
        taskId,
    )

    override fun findLongTerm(): List<MemoryEntry> = jdbcTemplate.query(
        "SELECT key, value FROM long_term_memory ORDER BY key",
    ) { resultSet, _ -> MemoryEntry(resultSet.getString("key"), resultSet.getString("value")) }

    @Transactional
    override fun apply(taskId: Long, userMessage: String, update: MemoryUpdate): LastMemoryUpdate {
        val normalizedWorking = normalize(update.working)
        val normalizedLongTerm = normalize(update.longTerm)
        val workingChanges = applyWorking(taskId, normalizedWorking)
        val longTermChanges = applyLongTerm(normalizedLongTerm)
        return saveLastUpdate(
            LastMemoryUpdate(
                taskId = taskId,
                userMessage = userMessage,
                working = workingChanges,
                longTerm = longTermChanges,
                updatedAt = Instant.now(),
            ),
        )
    }

    @Transactional
    override fun recordFailure(taskId: Long, userMessage: String, error: String): LastMemoryUpdate =
        saveLastUpdate(
            LastMemoryUpdate(
                taskId = taskId,
                userMessage = userMessage,
                working = emptyList(),
                longTerm = emptyList(),
                error = error,
                updatedAt = Instant.now(),
            ),
        )

    override fun lastUpdate(taskId: Long): LastMemoryUpdate? = jdbcTemplate.query(
        """
        SELECT user_message, working_changes, long_term_changes, error, updated_at
        FROM last_memory_update
        WHERE task_id = ?
        """.trimIndent(),
        { resultSet, _ ->
            LastMemoryUpdate(
                taskId = taskId,
                userMessage = resultSet.getString("user_message"),
                working = jsonMapper.readValue(
                    resultSet.getString("working_changes"),
                    Array<MemoryChange>::class.java,
                ).toList(),
                longTerm = jsonMapper.readValue(
                    resultSet.getString("long_term_changes"),
                    Array<MemoryChange>::class.java,
                ).toList(),
                error = resultSet.getString("error"),
                updatedAt = Instant.parse(resultSet.getString("updated_at")),
            )
        },
        taskId,
    ).firstOrNull()

    @Transactional
    override fun saveEffectiveContext(context: EffectiveContext) {
        jdbcTemplate.update(
            """
            INSERT INTO effective_context(
                task_id, strategy, system_prompt, long_term_memory, user_profile,
                working_memory, task_state, task_invariants, short_term, current_user_message, prepared_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(task_id) DO UPDATE SET
                strategy = excluded.strategy,
                system_prompt = excluded.system_prompt,
                long_term_memory = excluded.long_term_memory,
                user_profile = excluded.user_profile,
                working_memory = excluded.working_memory,
                task_state = excluded.task_state,
                task_invariants = excluded.task_invariants,
                short_term = excluded.short_term,
                current_user_message = excluded.current_user_message,
                prepared_at = excluded.prepared_at
            """.trimIndent(),
            context.taskId,
            context.strategy.name,
            context.systemPrompt,
            jsonMapper.writeValueAsString(context.longTermMemory),
            context.userProfile?.let(jsonMapper::writeValueAsString),
            jsonMapper.writeValueAsString(context.workingMemory),
            context.taskState?.let(jsonMapper::writeValueAsString),
            jsonMapper.writeValueAsString(context.taskInvariants),
            jsonMapper.writeValueAsString(context.shortTerm),
            jsonMapper.writeValueAsString(context.currentUserMessage),
            context.preparedAt.toString(),
        )
    }

    override fun effectiveContext(taskId: Long): EffectiveContext? = jdbcTemplate.query(
        "SELECT * FROM effective_context WHERE task_id = ?",
        { resultSet, _ ->
            EffectiveContext(
                taskId = taskId,
                strategy = ContextStrategyType.valueOf(resultSet.getString("strategy")),
                systemPrompt = resultSet.getString("system_prompt"),
                longTermMemory = jsonMapper.readValue(
                    resultSet.getString("long_term_memory"),
                    Array<MemoryEntry>::class.java,
                ).toList(),
                userProfile = resultSet.getString("user_profile")?.let { value ->
                    jsonMapper.readValue(value, UserProfileSnapshot::class.java)
                },
                workingMemory = jsonMapper.readValue(
                    resultSet.getString("working_memory"),
                    Array<MemoryEntry>::class.java,
                ).toList(),
                taskState = resultSet.getString("task_state")?.let { value ->
                    jsonMapper.readValue(value, TaskStateSnapshot::class.java)
                },
                taskInvariants = jsonMapper.readValue(
                    resultSet.getString("task_invariants"),
                    Array<TaskInvariantSnapshot>::class.java,
                ).toList(),
                shortTerm = jsonMapper.readValue(
                    resultSet.getString("short_term"),
                    Array<ChatMessage>::class.java,
                ).toList(),
                currentUserMessage = jsonMapper.readValue(
                    resultSet.getString("current_user_message"),
                    ChatMessage::class.java,
                ),
                preparedAt = Instant.parse(resultSet.getString("prepared_at")),
            )
        },
        taskId,
    ).firstOrNull()

    @Transactional
    override fun clearWorking(taskId: Long) {
        jdbcTemplate.update("DELETE FROM working_memory WHERE task_id = ?", taskId)
    }

    @Transactional
    override fun clearLongTerm() {
        jdbcTemplate.update("DELETE FROM long_term_memory")
    }

    private fun normalize(update: MemoryLayerUpdate): MemoryLayerUpdate {
        val upsert = linkedMapOf<String, MemoryEntry>()
        update.upsert.forEach { entry ->
            val key = entry.key.trim()
            val value = entry.value.trim()
            require(key.isNotEmpty()) { "Memory key must not be blank" }
            require(value.isNotEmpty()) { "Memory value must not be blank" }
            upsert[key] = MemoryEntry(key, value)
        }
        return MemoryLayerUpdate(
            upsert = upsert.values.toList(),
            delete = update.delete.map(String::trim).filter(String::isNotEmpty).distinct(),
        )
    }

    private fun applyWorking(taskId: Long, update: MemoryLayerUpdate): List<MemoryChange> {
        val existing = findWorking(taskId).associate { it.key to it.value }
        val changes = changes(existing, update)
        val upsertKeys = update.upsert.mapTo(mutableSetOf(), MemoryEntry::key)
        update.delete.filterNot(upsertKeys::contains).forEach { key ->
            jdbcTemplate.update("DELETE FROM working_memory WHERE task_id = ? AND key = ?", taskId, key)
        }
        val now = Instant.now().toString()
        update.upsert.forEach { entry ->
            jdbcTemplate.update(
                """
                INSERT INTO working_memory(task_id, key, value, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(task_id, key) DO UPDATE SET
                    value = excluded.value,
                    updated_at = excluded.updated_at
                """.trimIndent(),
                taskId,
                entry.key,
                entry.value,
                now,
                now,
            )
        }
        return changes
    }

    private fun applyLongTerm(update: MemoryLayerUpdate): List<MemoryChange> {
        val existing = findLongTerm().associate { it.key to it.value }
        val changes = changes(existing, update)
        val upsertKeys = update.upsert.mapTo(mutableSetOf(), MemoryEntry::key)
        update.delete.filterNot(upsertKeys::contains).forEach { key ->
            jdbcTemplate.update("DELETE FROM long_term_memory WHERE key = ?", key)
        }
        val now = Instant.now().toString()
        update.upsert.forEach { entry ->
            jdbcTemplate.update(
                """
                INSERT INTO long_term_memory(key, value, created_at, updated_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(key) DO UPDATE SET
                    value = excluded.value,
                    updated_at = excluded.updated_at
                """.trimIndent(),
                entry.key,
                entry.value,
                now,
                now,
            )
        }
        return changes
    }

    private fun changes(existing: Map<String, String>, update: MemoryLayerUpdate): List<MemoryChange> {
        val upsertByKey = update.upsert.associateBy(MemoryEntry::key)
        return buildList {
            update.delete.filterNot(upsertByKey::containsKey).forEach { key ->
                existing[key]?.let { old ->
                    add(MemoryChange(MemoryChangeType.DELETED, key, oldValue = old))
                }
            }
            update.upsert.forEach { entry ->
                val old = existing[entry.key]
                when {
                    old == null -> add(
                        MemoryChange(MemoryChangeType.ADDED, entry.key, newValue = entry.value),
                    )
                    old != entry.value -> add(
                        MemoryChange(
                            MemoryChangeType.UPDATED,
                            entry.key,
                            oldValue = old,
                            newValue = entry.value,
                        ),
                    )
                }
            }
        }
    }

    private fun saveLastUpdate(update: LastMemoryUpdate): LastMemoryUpdate {
        jdbcTemplate.update(
            """
            INSERT INTO last_memory_update(
                task_id, user_message, working_changes, long_term_changes, error, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(task_id) DO UPDATE SET
                user_message = excluded.user_message,
                working_changes = excluded.working_changes,
                long_term_changes = excluded.long_term_changes,
                error = excluded.error,
                updated_at = excluded.updated_at
            """.trimIndent(),
            update.taskId,
            update.userMessage,
            jsonMapper.writeValueAsString(update.working),
            jsonMapper.writeValueAsString(update.longTerm),
            update.error,
            update.updatedAt.toString(),
        )
        return update
    }

    private fun addColumnIfMissing(table: String, column: String, definition: String) {
        val columns = jdbcTemplate.queryForList("PRAGMA table_info($table)")
            .mapNotNull { row -> row["name"]?.toString() }
        if (column !in columns) {
            jdbcTemplate.execute("ALTER TABLE $table ADD COLUMN $column $definition")
        }
    }

}
