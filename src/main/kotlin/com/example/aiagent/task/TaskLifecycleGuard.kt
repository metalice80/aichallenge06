package com.example.aiagent.task

import org.springframework.stereotype.Component

@Component
class TaskLifecycleGuard {
    fun evaluate(
        stage: TaskStage,
        requestedAction: TaskActionType,
    ): LifecycleGuardDecision =
        if (requestedAction in ALLOWED_ACTIONS.getValue(stage)) {
            LifecycleGuardDecision.Allowed
        } else {
            LifecycleGuardDecision.Blocked(blockedMessage(stage, requestedAction))
        }

    fun pausedMessage(task: AgentTask): String =
        "Task приостановлена. Текущий этап: ${task.stage}. " +
            "Сохранённый шаг: ${task.currentStep}. Выполните Resume, затем продолжите работу."

    fun analyzerFailureMessage(task: AgentTask): String =
        "Не удалось безопасно определить допустимость действия. Текущий этап: ${task.stage}. " +
            "Состояние Task не изменено. Повторите запрос; переходы доступны только через явные UI/API controls."


    private fun blockedMessage(stage: TaskStage, action: TaskActionType): String = when (stage) {
        TaskStage.PLANNING ->
            "Действие $action пока не может быть выполнено. Текущий этап: PLANNING. " +
                "Сначала подготовьте план; реализация доступна только после явного подтверждения перехода."
        TaskStage.EXECUTION ->
            "Действие $action пока не может быть выполнено. Текущий этап: EXECUTION. " +
                "Завершите реализацию и явно перейдите к validation до финализации."
        TaskStage.VALIDATION ->
            "Действие $action пока не может быть выполнено. Текущий этап: VALIDATION. " +
                "Выполните проверки и явно зафиксируйте их результат."
        TaskStage.DONE ->
            "Действие $action недопустимо. Текущий этап: DONE. " +
                "Завершённая Task доступна только для чтения статуса и итогового summary."
    }


    companion object {
        private val ALLOWED_ACTIONS = mapOf(
            TaskStage.PLANNING to setOf(
                TaskActionType.PLAN,
                TaskActionType.STATUS,
                TaskActionType.NONE,
            ),
            TaskStage.EXECUTION to setOf(
                TaskActionType.IMPLEMENT,
                TaskActionType.STATUS,
                TaskActionType.NONE,
            ),
            TaskStage.VALIDATION to setOf(
                TaskActionType.VALIDATE,
                TaskActionType.STATUS,
                TaskActionType.NONE,
            ),
            TaskStage.DONE to setOf(
                TaskActionType.STATUS,
                TaskActionType.NONE,
            ),
        )
    }
}

sealed interface LifecycleGuardDecision {
    data object Allowed : LifecycleGuardDecision
    data class Blocked(val message: String) : LifecycleGuardDecision
}
