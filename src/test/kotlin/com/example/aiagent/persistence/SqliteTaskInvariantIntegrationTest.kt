package com.example.aiagent.persistence

import com.example.aiagent.invariant.CreateTaskInvariant
import com.example.aiagent.invariant.InvariantType
import com.example.aiagent.invariant.InvalidTaskInvariantException
import com.example.aiagent.invariant.TaskInvariantConflictException
import com.example.aiagent.invariant.TaskInvariantNotFoundException
import com.example.aiagent.invariant.TaskInvariantService
import com.example.aiagent.invariant.UpdateTaskInvariant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.sqlite.SQLiteDataSource
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class SqliteTaskInvariantIntegrationTest {
    @TempDir
    lateinit var tempDirectory: Path

    @Test
    fun `CRUD normalization conflict isolation and restart use persistent task scope`() {
        val database = tempDirectory.resolve("invariants.db")
        val first = fixture(database)
        val booking = first.tasks.create("Booking Service")
        val other = first.tasks.create("Other Task")

        val kotlin = first.service.create(
            booking.id,
            CreateTaskInvariant(
                InvariantType.STACK_CONSTRAINT,
                " Backend_Language ",
                " Kotlin ",
                " Backend must use Kotlin ",
                enabled = true,
            ),
        )
        val java = first.service.create(
            booking.id,
            CreateTaskInvariant(
                InvariantType.STACK_CONSTRAINT,
                "backend_language",
                "Java",
                null,
                enabled = false,
            ),
        )
        first.service.create(
            other.id,
            CreateTaskInvariant(InvariantType.STACK_CONSTRAINT, "backend_language", "Java", null, true),
        )

        assertEquals("backend_language", kotlin.key)
        assertEquals("Kotlin", kotlin.value)
        assertEquals("Backend must use Kotlin", kotlin.description)
        assertFalse(java.enabled)
        assertEquals(2, first.service.list(booking.id).size)
        assertEquals(listOf(kotlin.id), first.service.findEnabled(booking.id).map { it.id })
        assertThrows(TaskInvariantConflictException::class.java) {
            first.service.setEnabled(booking.id, java.id, true)
        }
        assertThrows(TaskInvariantConflictException::class.java) {
            first.service.update(
                booking.id,
                java.id,
                UpdateTaskInvariant(
                    InvariantType.TECHNICAL_DECISION,
                    " BACKEND_LANGUAGE ",
                    "Java",
                    null,
                    enabled = true,
                ),
            )
        }
        assertFalse(first.service.get(booking.id, java.id).enabled)
        assertEquals("backend_language", first.service.get(booking.id, java.id).key)
        assertThrows(TaskInvariantNotFoundException::class.java) {
            first.service.get(other.id, kotlin.id)
        }

        first.service.setEnabled(booking.id, kotlin.id, false)
        val enabledJava = first.service.setEnabled(booking.id, java.id, true)
        assertTrue(enabledJava.enabled)
        first.service.delete(booking.id, kotlin.id)

        val restored = fixture(database)
        assertEquals(listOf(enabledJava.id), restored.service.findEnabled(booking.id).map { it.id })
        assertEquals("Java", restored.service.get(booking.id, enabledJava.id).value)
        assertEquals(1, restored.service.list(other.id).size)
    }

    @Test
    fun `invalid commands and unknown Tasks do not persist partial rows`() {
        val fixture = fixture(tempDirectory.resolve("validation.db"))
        val task = fixture.tasks.create("Validation Task")

        assertThrows(InvalidTaskInvariantException::class.java) {
            fixture.service.create(
                task.id,
                CreateTaskInvariant(InvariantType.OTHER, "   ", "value", null, true),
            )
        }
        assertThrows(InvalidTaskInvariantException::class.java) {
            fixture.service.create(
                task.id,
                CreateTaskInvariant(InvariantType.OTHER, "key", "\n ", null, true),
            )
        }
        assertThrows(TaskInvariantNotFoundException::class.java) {
            fixture.service.create(
                Long.MAX_VALUE,
                CreateTaskInvariant(InvariantType.OTHER, "key", "value", null, true),
            )
        }

        assertTrue(fixture.service.list(task.id).isEmpty())
    }

    @Test
    fun `schema creates required indexes and active key uniqueness`() {
        val fixture = fixture(tempDirectory.resolve("schema.db"))
        val task = fixture.tasks.create("Schema Task")
        fixture.service.create(
            task.id,
            CreateTaskInvariant(InvariantType.OTHER, "constraint", "A", null, true),
        )

        val indexes = fixture.jdbc.queryForList("PRAGMA index_list(task_invariant)")
            .mapNotNull { it["name"]?.toString() }
        assertTrue("idx_task_invariant_task" in indexes)
        assertTrue("idx_task_invariant_task_enabled" in indexes)
        assertTrue("uq_task_invariant_active_key" in indexes)
        assertThrows(TaskInvariantConflictException::class.java) {
            fixture.service.create(
                task.id,
                CreateTaskInvariant(InvariantType.OTHER, "constraint", "B", null, true),
            )
        }
        assertEquals(1, fixture.service.findEnabled(task.id).size)
    }

    @Test
    fun `concurrent creates cannot persist two enabled invariants with one key`() {
        val database = tempDirectory.resolve("concurrent.db")
        val setup = fixture(database)
        val task = setup.tasks.create("Concurrent Task")
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val futures = listOf("Kotlin", "Java").map { value ->
            executor.submit<Boolean> {
                val worker = fixture(database)
                ready.countDown()
                start.await()
                try {
                    worker.service.create(
                        task.id,
                        CreateTaskInvariant(
                            InvariantType.STACK_CONSTRAINT,
                            "backend_language",
                            value,
                            null,
                            true,
                        ),
                    )
                    true
                } catch (_: TaskInvariantConflictException) {
                    false
                }
            }
        }
        ready.await()
        start.countDown()
        val outcomes = futures.map { it.get() }
        executor.shutdownNow()

        assertEquals(1, outcomes.count { it })
        assertEquals(1, fixture(database).service.findEnabled(task.id).size)
    }

    private fun fixture(database: Path): Fixture {
        val dataSource = SQLiteDataSource().apply {
            url = "jdbc:sqlite:${database.toAbsolutePath()}?busy_timeout=5000"
        }
        val jdbc = JdbcTemplate(dataSource)
        val tasks = SqliteTaskRepository(jdbc)
        val repository = SqliteTaskInvariantRepository(jdbc, jacksonObjectMapper())
        return Fixture(jdbc, tasks, TaskInvariantService(repository, tasks))
    }

    private data class Fixture(
        val jdbc: JdbcTemplate,
        val tasks: SqliteTaskRepository,
        val service: TaskInvariantService,
    )
}
