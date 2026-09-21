package com.example.aiagent.task

import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TaskLifecycleGuardTest {
    private val guard = TaskLifecycleGuard()

    @Test
    fun `implementation is blocked in planning with actionable explanation`() {
        val decision = guard.evaluate(
            currentStage = TaskStage.PLANNING,
            effectiveStage = TaskStage.PLANNING,
            requestedAction = TaskActionType.IMPLEMENT,
        )

        val blocked = assertInstanceOf(LifecycleGuardDecision.Blocked::class.java, decision)
        assertTrue(blocked.message.contains("PLANNING"))
        assertTrue(blocked.message.contains("PLAN_APPROVED"))
    }

    @Test
    fun `finalization is blocked in execution until validation`() {
        val decision = guard.evaluate(
            currentStage = TaskStage.EXECUTION,
            effectiveStage = TaskStage.EXECUTION,
            requestedAction = TaskActionType.FINALIZE,
        )

        val blocked = assertInstanceOf(LifecycleGuardDecision.Blocked::class.java, decision)
        assertTrue(blocked.message.contains("EXECUTION"))
        assertTrue(blocked.message.contains("VALIDATION", ignoreCase = true))
    }

    @Test
    fun `validation action is allowed only in validation stage`() {
        assertInstanceOf(
            LifecycleGuardDecision.Allowed::class.java,
            guard.evaluate(TaskStage.VALIDATION, TaskStage.VALIDATION, TaskActionType.VALIDATE),
        )
        assertInstanceOf(
            LifecycleGuardDecision.Blocked::class.java,
            guard.evaluate(TaskStage.PLANNING, TaskStage.PLANNING, TaskActionType.VALIDATE),
        )
    }

    @Test
    fun `done permits status but blocks new implementation`() {
        assertInstanceOf(
            LifecycleGuardDecision.Allowed::class.java,
            guard.evaluate(TaskStage.DONE, TaskStage.DONE, TaskActionType.STATUS),
        )
        assertInstanceOf(
            LifecycleGuardDecision.Blocked::class.java,
            guard.evaluate(TaskStage.DONE, TaskStage.DONE, TaskActionType.IMPLEMENT),
        )
    }

    @Test
    fun `accepted transitions authorize action in their effective stage`() {
        assertInstanceOf(
            LifecycleGuardDecision.Allowed::class.java,
            guard.evaluate(
                TaskStage.PLANNING,
                TaskStage.EXECUTION,
                TaskActionType.IMPLEMENT,
                TaskEvent.PLAN_APPROVED,
            ),
        )
        assertInstanceOf(
            LifecycleGuardDecision.Allowed::class.java,
            guard.evaluate(
                TaskStage.VALIDATION,
                TaskStage.DONE,
                TaskActionType.FINALIZE,
                TaskEvent.VALIDATION_PASSED,
            ),
        )
    }
}
