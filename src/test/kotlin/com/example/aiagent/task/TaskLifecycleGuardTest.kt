package com.example.aiagent.task

import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TaskLifecycleGuardTest {
    private val guard = TaskLifecycleGuard()

    @Test
    fun `work permissions depend only on current persisted stage`() {
        val allowed = mapOf(
            TaskStage.PLANNING to setOf(TaskActionType.PLAN, TaskActionType.STATUS, TaskActionType.NONE),
            TaskStage.EXECUTION to setOf(TaskActionType.IMPLEMENT, TaskActionType.STATUS, TaskActionType.NONE),
            TaskStage.VALIDATION to setOf(TaskActionType.VALIDATE, TaskActionType.STATUS, TaskActionType.NONE),
            TaskStage.DONE to setOf(TaskActionType.STATUS, TaskActionType.NONE),
        )

        TaskStage.entries.forEach { stage ->
            TaskActionType.entries.forEach { action ->
                val decision = guard.evaluate(stage, action)
                val expectedType = if (action in allowed.getValue(stage)) {
                    LifecycleGuardDecision.Allowed::class.java
                } else {
                    LifecycleGuardDecision.Blocked::class.java
                }
                assertInstanceOf(expectedType, decision, "$stage + $action")
            }
        }
    }

    @Test
    fun `planning implementation is blocked with actionable explanation`() {
        val decision = guard.evaluate(TaskStage.PLANNING, TaskActionType.IMPLEMENT)

        val blocked = assertInstanceOf(LifecycleGuardDecision.Blocked::class.java, decision)
        assertTrue(blocked.message.contains("PLANNING"))
        assertTrue(blocked.message.contains("явного подтверждения"))
    }

    @Test
    fun `execution finalization is blocked until explicit validation`() {
        val decision = guard.evaluate(TaskStage.EXECUTION, TaskActionType.FINALIZE)

        val blocked = assertInstanceOf(LifecycleGuardDecision.Blocked::class.java, decision)
        assertTrue(blocked.message.contains("EXECUTION"))
        assertTrue(blocked.message.contains("validation", ignoreCase = true))
    }
}
