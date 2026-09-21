# Формализованное состояние задачи: Finite State Machine

## 1. Цель

Доработать существующего AI-агента и реализовать формализованное состояние `Task` в виде конечного автомата — Finite State Machine (FSM).

Каждая задача должна явно хранить:

- текущий этап выполнения;
- текущий конкретный шаг;
- ожидаемое следующее действие;
- состояние pause/resume;
- историю изменения состояния.

Агент должен использовать это состояние при каждом основном LLM-запросе.

Основная цель — позволить пользователю приостановить работу на любом незавершённом этапе, позднее вернуться к задаче и продолжить её с того же места без повторного объяснения контекста.

---

# 2. Существующий проект

Это доработка существующего приложения.

В проекте уже реализованы:

- Kotlin;
- Spring Boot;
- web chat UI;
- Agent abstraction;
- Task;
- выбор активной Task;
- SHORT_TERM memory;
- WORKING memory;
- LONG_TERM memory;
- User Profile;
- персонализация;
- Memory Extractor;
- Memory Inspector;
- Effective Context Inspector;
- OpenAI;
- OpenRouter;
- LlmClient / LlmClientResolver;
- SQLite persistence;
- token statistics;
- Sliding Window;
- Sticky Facts;
- Branching;
- Rolling Summary;
- reset.

Перед внесением изменений сначала изучить существующую архитектуру.

Не создавать:

- новый параллельный Agent;
- отдельную Task infrastructure;
- второй Effective Context pipeline;
- отдельную persistence infrastructure.

Переиспользовать существующие компоненты.

---

# 3. Основная модель FSM

Task должна иметь формальное состояние:

```text
PLANNING
    ↓
EXECUTION
    ↓
VALIDATION
    ↓
DONE
```

Основной happy path:

```text
PLANNING
   │
   │ PLAN_APPROVED
   ▼
EXECUTION
   │
   │ EXECUTION_COMPLETED
   ▼
VALIDATION
   │
   │ VALIDATION_PASSED
   ▼
DONE
```

При неуспешной validation:

```text
VALIDATION
   │
   │ VALIDATION_FAILED
   ▼
EXECUTION
```

---

# 4. TaskStage

Добавить enum:

```kotlin
enum class TaskStage {
    PLANNING,
    EXECUTION,
    VALIDATION,
    DONE
}
```

Значение должно храниться persistent в SQLite.

---

# 5. Семантика этапов

## PLANNING

На этом этапе:

- определяется цель;
- собираются требования;
- выявляются ограничения;
- принимаются основные решения;
- формируется план выполнения.

Примеры `currentStep`:

```text
Собрать требования к REST API
```

```text
Выбрать database
```

```text
Согласовать архитектуру
```

---

## EXECUTION

На этом этапе происходит непосредственное выполнение задачи.

Примеры:

```text
Реализовать persistence layer
```

```text
Создать REST controller
```

```text
Добавить authentication
```

```text
Реализовать unit tests
```

---

## VALIDATION

На этом этапе проверяется результат.

Примеры:

```text
Запустить integration tests
```

```text
Проверить acceptance criteria
```

```text
Проверить обработку ошибок
```

---

## DONE

Task завершена.

Для обычного workflow:

```text
DONE
```

является terminal state.

---

# 6. TaskEvent

Добавить явные события FSM.

Например:

```kotlin
enum class TaskEvent {
    PLAN_APPROVED,
    EXECUTION_COMPLETED,
    VALIDATION_PASSED,
    VALIDATION_FAILED
}
```

Изменение stage должно происходить через event.

Не изменять stage произвольным присваиванием:

```kotlin
task.stage = TaskStage.EXECUTION
```

из разных частей приложения.

---

# 7. Разрешённые переходы

FSM должна явно определять допустимые transitions.

```text
PLANNING
+
PLAN_APPROVED
=
EXECUTION
```

```text
EXECUTION
+
EXECUTION_COMPLETED
=
VALIDATION
```

```text
VALIDATION
+
VALIDATION_PASSED
=
DONE
```

```text
VALIDATION
+
VALIDATION_FAILED
=
EXECUTION
```

---

# 8. Запрещённые переходы

FSM должна отклонять некорректные transitions.

Например:

```text
PLANNING → DONE
```

напрямую запрещён.

Также запрещены:

```text
PLANNING + VALIDATION_PASSED
EXECUTION + PLAN_APPROVED
DONE + EXECUTION_COMPLETED
```

и другие события, не предусмотренные transition table.

Ошибка должна быть контролируемой и не повреждать Task state.

---

# 9. TaskStateMachine

Выделить отдельный компонент.

Например:

```kotlin
interface TaskStateMachine {

    fun transition(
        currentStage: TaskStage,
        event: TaskEvent
    ): TaskStage
}
```

Или equivalent API, соответствующий архитектуре проекта.

`TaskStateMachine` должна быть детерминированной.

Одинаковая комбинация:

```text
state + event
```

всегда приводит к одному результату либо к controlled invalid-transition error.

---

# 10. LLM не управляет FSM напрямую

LLM не должна иметь возможность произвольно записать:

```text
stage = DONE
```

в database.

Правильная архитектура:

```text
LLM / Agent
     ↓
proposed event
     ↓
TaskStateMachine
     ↓
validate transition
     ↓
new TaskStage
     ↓
persistence
```

Таким образом FSM остаётся контролируемой приложением.

---

# 11. Current Step

Кроме крупного этапа Task должна хранить:

```text
currentStep
```

Это динамическое текстовое описание конкретной текущей работы.

Например:

```text
stage = EXECUTION

currentStep =
"Реализовать persistence layer"
```

или:

```text
stage = VALIDATION

currentStep =
"Проверить integration tests"
```

---

# 12. Current Step и Stage

Stage отвечает:

> На каком крупном этапе находится задача?

Current Step отвечает:

> Что конкретно сейчас делается?

Например:

```text
Stage:
EXECUTION

Current Step:
Реализовать authentication через OAuth2
```

---

# 13. Expected Action

Task должна хранить ожидаемое следующее действие:

```text
expectedAction
```

Не ограничиваться только строкой.

Использовать тип + description.

Например:

```kotlin
data class ExpectedAction(
    val type: ExpectedActionType,
    val description: String?
)
```

---

# 14. ExpectedActionType

Минимально:

```kotlin
enum class ExpectedActionType {
    USER_INPUT,
    USER_CONFIRMATION,
    AGENT_ACTION,
    VALIDATION,
    NONE
}
```

---

# 15. Примеры Expected Action

### USER_INPUT

```text
type:
USER_INPUT

description:
Выбрать JWT или OAuth2
```

---

### USER_CONFIRMATION

```text
type:
USER_CONFIRMATION

description:
Подтвердить использование PostgreSQL
```

---

### AGENT_ACTION

```text
type:
AGENT_ACTION

description:
Предложить структуру repository layer
```

---

### VALIDATION

```text
type:
VALIDATION

description:
Запустить integration tests
```

---

### NONE

Использовать, например, для:

```text
DONE
```

---

# 16. Формальное Task State

Концептуально Task должна содержать:

```text
Task
│
├── id
├── name
│
├── stage
├── currentStep
├── expectedActionType
├── expectedActionDescription
├── paused
│
├── Working Memory
├── Conversation / Branches
└── State History
```

---

# 17. Pause — не Stage

Не добавлять:

```text
PAUSED
```

в `TaskStage`.

Pause является ортогональным состоянием.

Использовать:

```text
paused = true / false
```

Например:

```text
stage = EXECUTION
paused = true
```

Это позволяет сохранить точное место выполнения.

---

# 18. Pause

Task должна иметь возможность быть приостановлена на любом незавершённом этапе:

```text
PLANNING
EXECUTION
VALIDATION
```

При Pause должны сохраниться без изменений:

```text
stage
currentStep
expectedAction
Working Memory
Conversation
```

Меняется только:

```text
paused = true
```

---

# 19. Pause DONE

Task в:

```text
DONE
```

нельзя приостановить.

Попытка Pause должна возвращать controlled validation error.

---

# 20. Resume

Resume:

```text
paused = true
       ↓
paused = false
```

Не менять:

```text
stage
currentStep
expectedAction
```

После Resume Agent должен иметь возможность продолжить работу с того же места.

---

# 21. Главный сценарий Pause/Resume

До Pause:

```text
Task:
Payment Service

Stage:
EXECUTION

Current Step:
Реализовать authentication

Expected Action:
USER_INPUT

Description:
Выбрать JWT или OAuth2
```

После Pause:

```text
Stage:
EXECUTION

Current Step:
Реализовать authentication

Expected Action:
Выбрать JWT или OAuth2

Paused:
true
```

После restart + Resume:

```text
Stage:
EXECUTION

Current Step:
Реализовать authentication

Expected Action:
Выбрать JWT или OAuth2

Paused:
false
```

Пользователь пишет:

```text
Продолжай.
```

Agent должен понимать, что именно необходимо продолжить.

---

# 22. Persistence

FSM state должна храниться в SQLite.

Минимально persistent:

```text
stage
current_step
expected_action_type
expected_action_description
paused
```

Можно добавить поля в существующую таблицу Task либо использовать отдельную таблицу, если это лучше соответствует текущей архитектуре.

Не создавать лишнюю таблицу без необходимости.

---

# 23. Restart

После restart приложения должны восстановиться:

```text
Task
TaskStage
currentStep
expectedAction
paused
```

Agent не должен начинать Task заново.

---

# 24. State History

Добавить persistent историю изменения состояния Task.

Например:

```text
task_state_history

id
task_id
event
from_stage
to_stage
paused
description
created_at
```

Конкретная схема может отличаться.

---

# 25. Что сохранять в State History

Минимально записывать:

- stage transitions;
- Pause;
- Resume.

Например:

```text
10:00
Task created
Stage = PLANNING
```

```text
10:20
PLAN_APPROVED

PLANNING → EXECUTION
```

```text
11:15
PAUSE

Stage = EXECUTION
Step = Implement persistence
```

```text
15:30
RESUME

Stage = EXECUTION
Step = Implement persistence
```

```text
17:00
EXECUTION_COMPLETED

EXECUTION → VALIDATION
```

---

# 26. Atomic State Update

Изменение:

```text
stage
currentStep
expectedAction
history
```

должно быть логически атомарным.

Не должно возникать состояния:

```text
stage обновлён
history не записана
```

или наоборот.

Использовать Spring transaction там, где это разумно.

---

# 27. Task State Service

Добавить application service.

Например:

```text
TaskStateService
```

Он отвечает за:

- получение state;
- применение event;
- Pause;
- Resume;
- update currentStep;
- update expectedAction;
- запись state history.

Controller и Agent не должны напрямую изменять database state.

---

# 28. Task Progress Analyzer / Coordinator

Для автоматического определения текущего шага и следующего действия допускается использовать отдельный компонент:

```text
TaskProgressAnalyzer
```

или:

```text
TaskStateCoordinator
```

Он может использовать LLM.

---

# 29. Роль Task Progress Analyzer

Analyzer может предложить:

```text
currentStep
expectedAction
TaskEvent
```

Например:

```json
{
  "currentStep": "Реализовать repository layer",
  "expectedAction": {
    "type": "AGENT_ACTION",
    "description": "Предложить интерфейс repository"
  },
  "proposedEvent": null
}
```

или:

```json
{
  "currentStep": "Проверить integration tests",
  "expectedAction": {
    "type": "VALIDATION",
    "description": "Запустить integration tests"
  },
  "proposedEvent": "EXECUTION_COMPLETED"
}
```

---

# 30. Analyzer не изменяет state

Analyzer возвращает proposal.

Далее:

```text
proposal
   ↓
TaskStateService
   ↓
TaskStateMachine
   ↓
validation
   ↓
persistent state
```

Не позволять Analyzer напрямую записывать `stage`.

---

# 31. Analyzer configuration

Если Analyzer использует LLM, provider и model задавать через `application.yml`.

Например:

```yaml
task:
  state:
    analyzer:
      enabled: true
      provider: OPENAI
      model: gpt-5-nano
```

или:

```yaml
task:
  state:
    analyzer:
      enabled: true
      provider: OPENROUTER
      model: <model-id>
```

Не хардкодить provider/model.

---

# 32. Main Model и Analyzer независимы

Например:

```text
Main Agent:
OpenRouter / model A

Task Analyzer:
OpenAI / model B
```

Смена основной модели пользователем не должна менять Analyzer configuration.

---

# 33. User Profile и Analyzer

Не применять автоматически User Profile к внутреннему Task Progress Analyzer.

Например:

```text
Profile:
responseStyle = concise
```

не должен менять structured response Analyzer.

Analyzer использует собственный system prompt.

---

# 34. Analyzer error

Если Analyzer завершился ошибкой:

- Task state не повреждать;
- stage не менять;
- currentStep не удалять;
- expectedAction не удалять;
- Conversation не терять;
- ошибку залогировать.

Основной Agent по возможности должен продолжить работу с последним сохранённым state.

---

# 35. Task State в Effective Context

Task State автоматически добавлять к каждому основному пользовательскому LLM request.

Например:

```text
TASK STATE

Task:
Payment Service

Stage:
EXECUTION

Current Step:
Implement authentication

Expected Action:
USER_INPUT

Expected Action Description:
Choose JWT or OAuth2

Paused:
false
```

---

# 36. Effective Context

Общий conceptual context теперь должен содержать:

```text
SYSTEM

LONG-TERM MEMORY

USER PROFILE

WORKING MEMORY

TASK STATE

EFFECTIVE SHORT-TERM CONTEXT

CURRENT USER MESSAGE
```

Порядок можно адаптировать к существующему Context Builder, но Task State должен быть явно представлен.

---

# 37. Task State и Working Memory

Не смешивать эти понятия.

Working Memory отвечает:

> Что известно о текущей задаче?

Task State отвечает:

> Где сейчас находится процесс выполнения задачи?

Пример:

```text
WORKING MEMORY

language = Kotlin
database = PostgreSQL
auth = OAuth2
```

```text
TASK STATE

stage = EXECUTION
currentStep = Реализовать OAuth2 authentication
expectedAction = AGENT_ACTION
```

Оба блока должны быть доступны Agent.

---

# 38. Task State и Short-Term

Даже если исходные сообщения вышли из Sliding Window, Task State остаётся доступным.

Например Short-Term уже не содержит обсуждение persistence layer.

Но:

```text
TASK STATE

stage = EXECUTION
currentStep = Implement persistence layer
```

позволяет пользователю написать:

```text
Продолжай.
```

---

# 39. Task State и Long-Term

Long-Term Memory глобальна.

Task State принадлежит конкретной Task.

Переключение Task должно менять Task State, но не Long-Term Memory.

---

# 40. Task State и Profile

Profile не принадлежит Task.

Переключение Profile:

```text
Developer → Student
```

не должно менять FSM state.

Переключение Task:

```text
Task A → Task B
```

не должно менять active Profile.

---

# 41. Изоляция Task State

Каждая Task имеет независимый FSM state.

Например:

```text
Task A: Booking

Stage:
EXECUTION

Current Step:
Implement persistence
```

и:

```text
Task B: Shop

Stage:
VALIDATION

Current Step:
Validate payment error handling
```

Эти состояния не должны смешиваться.

---

# 42. Переключение Task

При переключении:

```text
Booking → Shop
```

UI и Agent должны получить state Shop.

При возврате:

```text
Shop → Booking
```

восстанавливается state Booking.

---

# 43. UI Task State

Расширить существующий Task UI.

Например:

```text
┌──────────────────────────────────────────────┐
│ Task: [ Payment Service ▼ ]                 │
│                                              │
│ Stage:          EXECUTION                    │
│ Current step:   Implement authentication     │
│ Expected:       Choose JWT or OAuth2         │
│                                              │
│ [ Pause ]                                    │
└──────────────────────────────────────────────┘
```

---

# 44. UI Paused

Если:

```text
paused = true
```

показывать:

```text
Task paused

Stage:
EXECUTION

Current step:
Implement authentication

Expected:
Choose JWT or OAuth2

[ Resume ]
```

---

# 45. UI DONE

Для DONE:

```text
Stage:
DONE

✓ Task completed
```

Не показывать Pause.

---

# 46. Visual Progress

Желательно добавить простой индикатор:

```text
Planning
   ✓
Execution
   ●
Validation
   ○
Done
   ○
```

или:

```text
PLANNING → EXECUTION → VALIDATION → DONE
             ↑
           current
```

Не требуется сложная визуализация.

---

# 47. State History UI

Добавить возможность посмотреть историю состояния.