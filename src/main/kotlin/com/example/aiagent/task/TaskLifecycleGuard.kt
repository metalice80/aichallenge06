package com.example.aiagent.task

import org.springframework.stereotype.Component

@Component
class TaskLifecycleGuard {
    fun evaluate(
        currentStage: TaskStage,
        effectiveStage: TaskStage,
        requestedAction: TaskActionType,
        acceptedEvent: TaskEvent? = null,
    ): LifecycleGuardDecision {
        val allowed = when (effectiveStage) {
            TaskStage.PLANNING -> requestedAction in setOf(
                TaskActionType.PLAN,
                TaskActionType.STATUS,
                TaskActionType.NONE,
            )
            TaskStage.EXECUTION -> requestedAction in setOf(
                TaskActionType.IMPLEMENT,
                TaskActionType.STATUS,
                TaskActionType.NONE,
            )
            TaskStage.VALIDATION -> requestedAction in setOf(
                TaskActionType.VALIDATE,
                TaskActionType.STATUS,
                TaskActionType.NONE,
            )
            TaskStage.DONE -> requestedAction in setOf(TaskActionType.STATUS, TaskActionType.NONE) ||
                (acceptedEvent == TaskEvent.VALIDATION_PASSED && requestedAction == TaskActionType.FINALIZE)
        }
        return if (allowed) {
            LifecycleGuardDecision.Allowed
        } else {
            LifecycleGuardDecision.Blocked(blockedMessage(currentStage, requestedAction))
        }
    }

    fun pausedMessage(task: AgentTask): String =
        "Task приостановлена. Текущий этап: ${task.stage}. " +
            "Сохранённый шаг: ${task.currentStep}. Выполните Resume, затем продолжите работу."

    fun analyzerFailureMessage(task: AgentTask): String =
        "Не удалось безопасно определить допустимость действия. Текущий этап: ${task.stage}. " +
            "Состояние Task не изменено. Повторите запрос или используйте явный lifecycle control для события ${nextEvent(task.stage)}."

    fun invalidTransitionMessage(task: AgentTask, event: TaskEvent): String =
        "Событие $event недопустимо. Текущий этап: ${task.stage}. " +
            "Состояние Task не изменено. Следующее допустимое событие: ${nextEvent(task.stage)}."

    private fun blockedMessage(stage: TaskStage, action: TaskActionType): String = when (stage) {
        TaskStage.PLANNING ->
            "Действие $action пока не может быть выполнено. Текущий этап: PLANNING. " +
                "Сначала подготовьте и явно утвердите план событием PLAN_APPROVED."
        TaskStage.EXECUTION ->
            "Действие $action пока не может быть выполнено. Текущий этап: EXECUTION. " +
                "Завершите реализацию, примените EXECUTION_COMPLETED и выполните validation до финализации."
        TaskStage.VALIDATION ->
            "Действие $action пока не может быть выполнено. Текущий этап: VALIDATION. " +
                "Выполните проверки и примените VALIDATION_PASSED либо VALIDATION_FAILED."
        TaskStage.DONE ->
            "Действие $action недопустимо. Текущий этап: DONE. " +
                "Завершённая Task доступна только для чтения статуса и итогового summary."
    }

    private fun nextEvent(stage: TaskStage): String = when (stage) {
        TaskStage.PLANNING -> TaskEvent.PLAN_APPROVED.name
        TaskStage.EXECUTION -> TaskEvent.EXECUTION_COMPLETED.name
        TaskStage.VALIDATION -> "${TaskEvent.VALIDATION_PASSED.name} или ${TaskEvent.VALIDATION_FAILED.name}"
        TaskStage.DONE -> "нет — DONE является terminal state"
    }
}

sealed interface LifecycleGuardDecision {
    data object Allowed : LifecycleGuardDecision
    data class Blocked(val message: String) : LifecycleGuardDecision
}
