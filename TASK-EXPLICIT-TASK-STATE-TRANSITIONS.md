# Явные переходы между состояниями Task

## 1. Цель

Доработать существующего AI-агента и реализовать контролируемый жизненный цикл `Task` с явными состояниями, событиями и разрешёнными переходами.

Система должна гарантировать:

- у каждой Task есть формальное persistent состояние;
- состояние меняется только через явно определённое событие;
- допустимость перехода проверяется детерминированным кодом приложения;
- LLM не может напрямую назначить новое состояние;
- нельзя начать реализацию до утверждения плана;
- нельзя объявить Task завершённой до успешной валидации;
- нельзя перепрыгнуть через обязательный этап;
- недопустимая попытка не повреждает Task State;
- Pause/Resume сохраняют точное место продолжения;
- после restart состояние восстанавливается;
- пользователь видит текущее состояние и доступные действия;
- диалоговые команды и ручные UI/API controls используют одну State Machine.

Итогом должен быть ассистент с контролируемым, наблюдаемым и тестируемым жизненным циклом задачи.

---

## 2. Контекст существующего проекта

Это доработка существующего Kotlin + Spring Boot AI-agent приложения, а не новый проект.

В проекте уже могут присутствовать:

- `Task` и выбор активной Task;
- Task State или частичная реализация FSM;
- `stage`, `currentStep`, `expectedAction`, `paused`;
- `TaskProgressAnalyzer` или похожий компонент;
- Agent и основной chat pipeline;
- SHORT_TERM, WORKING и LONG_TERM memory;
- Memory Extractor;
- User Profile;
- Task Invariants и Input/Output Guards;
- `EffectiveContextBuilder`;
- Effective Context Inspector;
- SQLite persistence;
- `LlmClient` и `LlmClientResolver`;
- OpenAI/OpenRouter;
- token statistics;
- web UI и REST API.

Перед реализацией OMP должен изучить фактический код и определить:

- существует ли уже `TaskStage`;
- существует ли уже `TaskEvent`;
- существует ли `TaskStateMachine`;
- кто сейчас может менять `stage`;
- где хранятся `currentStep`, `expectedAction` и `paused`;
- вызывается ли `TaskProgressAnalyzer` при обычном сообщении;
- применяется ли предложенное событие;
- есть ли REST endpoints переходов;
- есть ли UI controls;
- есть ли persistent State History;
- восстанавливается ли state после restart;
- попадает ли Task State в Effective Context.

Если эти компоненты уже есть, не создавать дубликаты. Необходимо исправить или расширить существующую реализацию и довести её до рабочего end-to-end flow.

---

## 3. Архитектурные ограничения

Не создавать параллельно:

- второй Agent;
- вторую модель Task;
- второй chat pipeline;
- отдельную persistence infrastructure;
- второй `EffectiveContextBuilder`;
- второй `LlmClientResolver`;
- независимую FSM рядом с уже существующей;
- новый memory layer;
- второй механизм token statistics.

Не подключать внешнюю state-machine библиотеку без реальной необходимости. Для четырёх состояний достаточно простой детерминированной transition table, встроенной в текущую архитектуру.

Контроллер, Agent, LLM и frontend не должны записывать `stage` напрямую.

---

## 4. Модель состояний

Минимальный набор:

```kotlin
enum class TaskStage {
    PLANNING,
    EXECUTION,
    VALIDATION,
    DONE
}
```

### 4.1 `PLANNING`

На этом этапе:

- уточняется цель;
- собираются требования;
- выявляются ограничения;
- принимаются технические решения;
- формируется план;
- план предъявляется пользователю на утверждение.

На этом этапе запрещено выполнять полноценную реализацию или заявлять о завершении Task.

### 4.2 `EXECUTION`

На этом этапе:

- выполняется утверждённый план;
- создаётся или меняется код;
- реализуются компоненты;
- исправляются дефекты, найденные в ходе validation;
- обновляются необходимые артефакты.

На этом этапе нельзя объявлять Task окончательно завершённой без перехода через `VALIDATION`.

### 4.3 `VALIDATION`

На этом этапе:

- запускаются тесты;
- выполняется сборка;
- проверяются acceptance criteria;
- анализируются ошибки;
- принимается решение о прохождении или непрохождении validation.

Успешная проверка переводит Task в `DONE`. Неуспешная возвращает её в `EXECUTION`.

### 4.4 `DONE`

Task завершена и прошла validation.

`DONE` является terminal state в рамках этой доработки. Автоматический `reopen` не добавлять. Если проекту потребуется переоткрытие, оно должно быть отдельной явно согласованной задачей с отдельным событием и правилами.

---

## 5. События

Добавить или переиспользовать enum:

```kotlin
enum class TaskEvent {
    PLAN_APPROVED,
    EXECUTION_COMPLETED,
    VALIDATION_PASSED,
    VALIDATION_FAILED
}
```

Событие описывает подтверждённый факт, а не желаемое целевое состояние.

Правильно:

```text
PLAN_APPROVED
EXECUTION_COMPLETED
VALIDATION_PASSED
```

Неправильно:

```text
SET_STAGE_EXECUTION
GO_TO_DONE
stage = DONE
```

Нельзя принимать `targetStage` от LLM или frontend как способ обхода transition rules.

---

## 6. Разрешённые переходы

Единственная обязательная transition table:

| From | Event | To |
|---|---|---|
| `PLANNING` | `PLAN_APPROVED` | `EXECUTION` |
| `EXECUTION` | `EXECUTION_COMPLETED` | `VALIDATION` |
| `VALIDATION` | `VALIDATION_PASSED` | `DONE` |
| `VALIDATION` | `VALIDATION_FAILED` | `EXECUTION` |

Граф:

```text
PLANNING
   │ PLAN_APPROVED
   ▼
EXECUTION ◄────────────────┐
   │ EXECUTION_COMPLETED    │ VALIDATION_FAILED
   ▼                        │
VALIDATION ─────────────────┘
   │ VALIDATION_PASSED
   ▼
DONE
```

Все отсутствующие в таблице комбинации запрещены.

Примеры запрещённых переходов:

```text
PLANNING + EXECUTION_COMPLETED
PLANNING + VALIDATION_PASSED
PLANNING + VALIDATION_FAILED

EXECUTION + PLAN_APPROVED
EXECUTION + VALIDATION_PASSED
EXECUTION + VALIDATION_FAILED

VALIDATION + PLAN_APPROVED
VALIDATION + EXECUTION_COMPLETED

DONE + любое событие
```

Повторное событие не должно молча считаться успешным, если оно недопустимо в текущем состоянии.

---

## 7. Детерминированная `TaskStateMachine`

Добавить отдельный чистый компонент или доработать существующий:

```kotlin
interface TaskStateMachine {
    fun transition(
        currentStage: TaskStage,
        event: TaskEvent
    ): TaskStage
}
```

Пример:

```kotlin
class DefaultTaskStateMachine : TaskStateMachine {

    private val transitions = mapOf(
        (TaskStage.PLANNING to TaskEvent.PLAN_APPROVED) to
            TaskStage.EXECUTION,

        (TaskStage.EXECUTION to TaskEvent.EXECUTION_COMPLETED) to
            TaskStage.VALIDATION,

        (TaskStage.VALIDATION to TaskEvent.VALIDATION_PASSED) to
            TaskStage.DONE,

        (TaskStage.VALIDATION to TaskEvent.VALIDATION_FAILED) to
            TaskStage.EXECUTION
    )

    override fun transition(
        currentStage: TaskStage,
        event: TaskEvent
    ): TaskStage = transitions[currentStage to event]
        ?: throw InvalidTaskTransitionException(currentStage, event)
}
```

Требования:

- компонент не обращается к LLM;
- компонент не работает с SQLite;
- компонент не зависит от UI;
- одинаковые `stage + event` всегда дают одинаковый результат;
- недопустимая комбинация всегда возвращает typed error;
- State Machine не изменяет объект Task сама, а только вычисляет новый stage.

---

## 8. Запрет прямой записи stage

Следующие компоненты не должны назначать `stage` напрямую:

- Controller;
- Agent;
- `TaskProgressAnalyzer`;
- Memory Extractor;
- LLM client;
- frontend;
- DTO mapper;
- repository consumer вне `TaskStateService`.

Запрещённый пример:

```kotlin
task.stage = TaskStage.DONE
taskRepository.save(task)
```

Правильный flow:

```text
TaskEvent
   ↓
TaskStateService.applyEvent(...)
   ↓
TaskStateMachine.transition(...)
   ↓
new stage
   ↓
atomic persistence + history
```

Если текущая mutable entity технически имеет setter, ограничить его использование application service/repository mapping слоем. По возможности использовать immutable update/copy pattern текущего проекта.

---

## 9. Persistent Task State

Концептуально Task должна содержать:

```text
id
name
stage
currentStep
expectedActionType
expectedActionDescription
paused
version
```

Минимальные значения для новой Task:

```text
stage = PLANNING
paused = false
currentStep = Подготовить план
expectedActionType = AGENT_ACTION или USER_INPUT
```

Точные defaults адаптировать к текущему workflow создания Task.

`version` использовать, если в проекте есть optimistic locking. Если его нет, добавить эквивалентную защиту от потери concurrent state update способом, совместимым с текущим persistence слоем.

---

## 10. `currentStep` и `expectedAction`

`stage` отвечает на вопрос:

> На каком обязательном этапе находится Task?

`currentStep` отвечает:

> Что конкретно сейчас выполняется?

`expectedAction` отвечает:

> Кто и что должен сделать дальше?

Пример:

```text
Stage: EXECUTION
Current Step: Реализовать persistence layer
Expected Action Type: AGENT_ACTION
Expected Action Description: Добавить repository и SQLite schema
```

Минимальный enum:

```kotlin
enum class ExpectedActionType {
    USER_INPUT,
    USER_CONFIRMATION,
    AGENT_ACTION,
    VALIDATION,
    NONE
}
```

При применении event `stage`, `currentStep`, `expectedAction` и history должны обновляться согласованно.

Рекомендуемые безопасные defaults:

| Event | New stage | Default currentStep | Default expectedAction |
|---|---|---|---|
| `PLAN_APPROVED` | `EXECUTION` | Начать выполнение утверждённого плана | `AGENT_ACTION` |
| `EXECUTION_COMPLETED` | `VALIDATION` | Проверить выполненную реализацию | `VALIDATION` |
| `VALIDATION_PASSED` | `DONE` | Задача завершена | `NONE` |
| `VALIDATION_FAILED` | `EXECUTION` | Исправить результаты неуспешной проверки | `AGENT_ACTION` |

Analyzer может предложить более конкретные тексты, но пустые или некорректные значения не должны разрушать state. Service применяет validation и fallback defaults.

---

## 11. `TaskStateService`

Добавить или расширить application service:

```kotlin
interface TaskStateService {
    fun getState(taskId: Long): TaskState

    fun applyEvent(
        taskId: Long,
        event: TaskEvent,
        proposal: TaskProgressProposal? = null,
        source: TaskEventSource
    ): TaskState

    fun pause(taskId: Long): TaskState
    fun resume(taskId: Long): TaskState
}
```

Источник события:

```kotlin
enum class TaskEventSource {
    USER_INTERFACE,
    REST_API,
    CHAT_ANALYZER
}
```

Service отвечает за:

- загрузку актуальной Task;
- проверку `paused`;
- вызов `TaskStateMachine`;
- validation proposal;
- вычисление новых `currentStep` и `expectedAction`;
- atomic update Task;
- запись State History;
- optimistic/concurrency validation;
- domain errors.

Controller и Agent не должны повторять эти правила.

---

## 12. State History

Использовать существующую таблицу history либо добавить task-scoped history по соглашениям проекта.

Концептуальная схема:

```text
task_state_history

id
task_id
event
source
from_stage
to_stage
current_step
expected_action_type
expected_action_description
description
created_at
```

Минимально сохранять:

- успешные stage transitions;
- Pause;
- Resume.

Недопустимое событие не создаёт успешную transition record. Если в проекте есть audit ошибок, попытку можно записать туда отдельно, но нельзя смешивать её с успешной history.

Пример:

```text
10:00 Task created, stage = PLANNING
10:20 PLAN_APPROVED, PLANNING → EXECUTION
11:15 PAUSE, stage remains EXECUTION
15:30 RESUME, stage remains EXECUTION
17:00 EXECUTION_COMPLETED, EXECUTION → VALIDATION
17:30 VALIDATION_FAILED, VALIDATION → EXECUTION
19:00 EXECUTION_COMPLETED, EXECUTION → VALIDATION
19:15 VALIDATION_PASSED, VALIDATION → DONE
```

---

## 13. Транзакционность и concurrency

В одной Spring transaction должны выполняться:

- чтение актуального state для mutation;
- проверка перехода;
- обновление `stage/currentStep/expectedAction/paused/version`;
- запись history;
- сохранение Task.

Не допускаются состояния:

```text
stage изменён, history не записана
```

или:

```text
history записана, Task не обновлена
```

При concurrent events:

- только одно событие применяется к ожидаемой версии Task;
- второе получает controlled conflict;
- событие не должно автоматически переигрываться в уже изменившемся stage без повторной validation;
- frontend после conflict перечитывает актуальный state.

Не держать database transaction открытой во время LLM network call. Analyzer сначала формирует proposal без mutation, затем `TaskStateService` коротко и атомарно применяет событие к актуальному состоянию.

---

## 14. Pause — ортогональное состояние

Не добавлять `PAUSED` в `TaskStage`.

Pause — отдельный persistent flag:

```text
paused = true | false
```

Пример:

```text
stage = EXECUTION
paused = true
```

При Pause сохраняются без изменений:

- `stage`;
- `currentStep`;
- `expectedAction`;
- Working Memory;
- Conversation;
- Task Invariants;
- план и промежуточные результаты.

Меняется только `paused`, `updatedAt/version` и history.

---

## 15. Правила Pause

Pause разрешён в:

```text
PLANNING
EXECUTION
VALIDATION
```

Pause запрещён в:

```text
DONE
```

Повторный Pause для уже paused Task возвращает controlled validation response, соответствующий текущему API style. Он не должен создавать ложное изменение stage.

Пока `paused = true`:

- рабочие chat requests не выполняются;
- stage events не применяются;
- Memory Extractor не меняет task-scoped Working Memory;
- `TaskProgressAnalyzer` не предлагает рабочие события;
- основной LLM не должен продолжать выполнение Task;
- разрешены получение state/history и операция Resume.

Допустим краткий информационный вопрос о состоянии Task без mutation, если это соответствует текущей архитектуре.

---

## 16. Resume

Resume выполняет:

```text
paused = true → paused = false
```

Resume не меняет:

- `stage`;
- `currentStep`;
- `expectedAction`;
- Working Memory;
- Task Invariants.

После Resume команда:

```text
Продолжай.
```

должна использовать сохранённые `stage/currentStep/expectedAction` и продолжить работу без повторного объяснения пользователем.

Resume для неприостановленной Task возвращает controlled validation response без повреждения state.

---

## 17. Lifecycle Guard

Проверки transition table недостаточно. LLM может не менять `stage`, но выполнять работу следующего этапа.

Добавить или расширить `TaskLifecycleGuard`, который проверяет допустимость действия относительно текущего stage до основного LLM request.

Минимальная permission matrix:

| Stage | Разрешено | Запрещено |
|---|---|---|
| `PLANNING` | требования, вопросы, анализ, архитектура, план, утверждение плана | выполнение реализации, объявление validation, финализация |
| `EXECUTION` | реализация утверждённого плана, исправления, подготовка к validation | объявление окончательного завершения без validation |
| `VALIDATION` | тестирование, сборка, проверка критериев, анализ ошибок | финализация до `VALIDATION_PASSED`, новая несогласованная реализация вне исправления дефектов |
| `DONE` | просмотр результата, status/summary | новые изменения состояния или продолжение реализации |

Guard должен различать:

```text
«Покажи пример возможной реализации для обсуждения плана»
```

и:

```text
«План не нужен, сразу внеси изменения и объяви задачу выполненной»
```

Первый случай может быть разрешён как planning artifact, если он не выполняет фактическую mutation. Второй блокируется.

---

## 18. Реакция на действие не своего этапа

При blocked lifecycle action:

- не выполнять основной рабочий запрос;
- не менять `stage/currentStep/expectedAction`;
- не применять TaskEvent;
- не менять Working Memory как будто действие выполнено;
- не утверждать, что работа сделана;
- вернуть понятное объяснение текущего stage и следующего допустимого шага.

Пример для `PLANNING`:

```text
Реализация пока не может быть начата.

Текущий этап: PLANNING.
Сначала необходимо подготовить и явно утвердить план.
После события PLAN_APPROVED задача перейдёт в EXECUTION.
```

Пример для `EXECUTION`:

```text
Задача ещё не может быть объявлена завершённой.

Текущий этап: EXECUTION.
После завершения реализации необходимо перейти в VALIDATION,
выполнить проверки и только затем подтвердить VALIDATION_PASSED.
```

Не отвечать только `Invalid state` или `Нельзя`.

---

## 19. `TaskProgressAnalyzer`

Для естественного языка использовать существующий analyzer/coordinator. Он возвращает proposal, но ничего не сохраняет.

Концептуальный контракт:

```kotlin
data class TaskProgressProposal(
    val proposedEvent: TaskEvent?,
    val currentStep: String?,
    val expectedAction: ExpectedAction?,
    val requestedAction: TaskActionType?,
    val reason: String?
)
```

Минимальные `TaskActionType` могут быть:

```kotlin
enum class TaskActionType {
    PLAN,
    IMPLEMENT,
    VALIDATE,
    FINALIZE,
    STATUS,
    NONE
}
```

Точные DTO адаптировать к текущей реализации. Не создавать новый analyzer, если существующий уже возвращает эквивалентные данные.

Analyzer:

- получает актуальный Task State;
- понимает `expectedAction`;
- предлагает не более одного события на сообщение;
- не назначает `targetStage`;
- не пишет в repository;
- не меняет Task;
- не обходит Lifecycle Guard;
- использует собственный structured output;
- при ошибке не повреждает state.

---

## 20. Примеры диалогового распознавания

### Утверждение плана

```text
Stage: PLANNING
Expected Action: USER_CONFIRMATION
User: «План согласован. Начинай реализацию.»
```

Proposal:

```json
{
  "proposedEvent": "PLAN_APPROVED",
  "requestedAction": "IMPLEMENT",
  "currentStep": "Начать первый шаг утверждённого плана",
  "expectedAction": {
    "type": "AGENT_ACTION",
    "description": "Выполнить первый шаг реализации"
  }
}
```

Результат:

```text
PLANNING → EXECUTION
```

После успешного transition реализация может начаться в рамках того же сообщения.

### Попытка перепрыгнуть этап

```text
Stage: PLANNING
User: «Проверка успешна, сразу заверши задачу.»
```

Даже если analyzer ошибочно предложил `VALIDATION_PASSED`, State Machine отклоняет событие. Main LLM не должен сообщать, что Task завершена.

### Завершение реализации

```text
Stage: EXECUTION
User: «Реализация закончена. Запускай полную проверку.»
```

Proposal:

```text
EXECUTION_COMPLETED
```

Результат:

```text
EXECUTION → VALIDATION
```

### Неуспешная validation

```text
Stage: VALIDATION
User: «Тесты не прошли, исправь найденные ошибки.»
```

Proposal:

```text
VALIDATION_FAILED
```

Результат:

```text
VALIDATION → EXECUTION
```

---

## 21. Порядок chat pipeline

Концептуальный flow:

```text
USER MESSAGE
      │
      ▼
Resolve active Task and state
      │
      ▼
Existing Input Invariant Guard, if implemented
      │
      ▼
Paused Guard
      │
      ▼
TaskProgressAnalyzer (read-only proposal)
      │
      ▼
Validate proposed event with TaskStateMachine
      │
   ┌──┴───────────────┐
   │                  │
VALID             INVALID
   │                  │
   ▼                  ▼
Atomic apply       Controlled explanation
   │                  │
   ▼                  └────────────► USER
TaskLifecycleGuard using effective stage
   │
   ├── BLOCKED ─────────────────────► USER
   │
   ▼
Existing Memory/Agent pipeline
   │
   ▼
EffectiveContextBuilder
   │
   ▼
Main LLM
   │
   ▼
Existing Output Guards
   │
   ▼
USER
```

Если Task Invariants уже реализованы, их Input Guard остаётся перед `TaskProgressAnalyzer`, чтобы запрещённое сообщение не могло изменить FSM.

Внутри одного user message допустимо:

```text
PLAN_APPROVED → stage становится EXECUTION → начать первый разрешённый шаг
```

Но только если transition успешно применён до execution action.

---

## 22. Ошибки analyzer

Если `TaskProgressAnalyzer` завершился ошибкой:

- Task State не менять;
- event не применять;
- history transition не писать;
- `stage/currentStep/expectedAction` не очищать;
- Conversation не повреждать;
- ошибку логировать через существующий механизм;
- не угадывать transition самостоятельно.

Если сообщение не требует transition и основной Agent может безопасно ответить в рамках текущего stage, допустимо продолжить с последним persistent state согласно существующей error policy.

Если без analyzer невозможно определить, разрешено ли действие следующего этапа, применить fail-safe поведение: не выполнять потенциально преждевременное действие и попросить использовать явный UI control либо уточнить намерение.

---

## 23. Manual controls как гарантированный путь

Автоматическое распознавание естественного языка удобно, но не должно быть единственным способом управления FSM.

Для каждого stage добавить context-sensitive controls.

### `PLANNING`

```text
[ Утвердить план ] [ Pause ]
```

Кнопка отправляет:

```text
PLAN_APPROVED
```

### `EXECUTION`

```text
[ Реализация завершена ] [ Pause ]
```

Кнопка отправляет:

```text
EXECUTION_COMPLETED
```

### `VALIDATION`

```text
[ Валидация успешна ]
[ Валидация не пройдена ]
[ Pause ]
```

Кнопки отправляют:

```text
VALIDATION_PASSED
VALIDATION_FAILED
```

### `DONE`

```text
✓ Task completed
```

Pause и transition buttons не показывать.

Frontend не вычисляет новый stage самостоятельно. После события он перечитывает state из ответа backend.

---

## 24. REST API

Добавить endpoints в стиле существующего API.

Концептуально:

```text
GET  /api/tasks/{taskId}/state
POST /api/tasks/{taskId}/events
POST /api/tasks/{taskId}/pause
POST /api/tasks/{taskId}/resume
GET  /api/tasks/{taskId}/state-history
```

Event request:

```json
{
  "event": "PLAN_APPROVED"
}
```

Успешный response возвращает актуальный state:

```json
{
  "taskId": 42,
  "stage": "EXECUTION",
  "currentStep": "Начать выполнение утверждённого плана",
  "expectedAction": {
    "type": "AGENT_ACTION",
    "description": "Выполнить первый шаг реализации"
  },
  "paused": false,
  "version": 4
}
```

Недопустимый переход:

```http
409 Conflict
```

```json
{
  "code": "INVALID_TASK_TRANSITION",
  "currentStage": "PLANNING",
  "event": "VALIDATION_PASSED",
  "message": "Validation cannot pass before planning and execution are completed."
}
```

Другие ответы:

- `400` — неизвестный event или invalid payload;
- `404` — Task не найдена;
- `409` — invalid transition, paused conflict или version conflict;
- использовать существующий error envelope проекта.

Не принимать `targetStage` в публичном API.

---

## 25. UI Task State

Расширить существующую панель Task:

```text
Task: Booking Service

Stage: EXECUTION
Current step: Implement persistence layer
Expected action: AGENT_ACTION
Expected description: Add repository and SQLite schema
Paused: No

PLANNING → EXECUTION → VALIDATION → DONE
             ●

[ Реализация завершена ] [ Pause ]
```

Требования:

- UI обновляется после каждого события;
- Task switch показывает state выбранной Task;
- отображается stage, currentStep, expectedAction и paused;
- видны только допустимые для текущего stage controls;
- во время API request кнопки защищены от повторного нажатия;
- `409` обновляет state и показывает понятное сообщение;
- State History доступна для просмотра;
- UI не присваивает stage локально до ответа backend;
- после reload state загружается из persistence.

---

## 26. Effective Context

Добавлять актуальный Task State в каждый основной LLM request:

```text
TASK STATE

Task: Booking Service
Stage: EXECUTION
Current Step: Implement persistence layer
Expected Action: AGENT_ACTION
Expected Action Description: Add repository and SQLite schema
Paused: false

LIFECYCLE RULES

- Perform only actions permitted for the current stage.
- Do not begin implementation while stage is PLANNING.
- Do not claim task completion before VALIDATION_PASSED.
- Never assign the task stage directly.
- A stage may change only through an accepted TaskEvent.
```

Если Task Invariants существуют, Task State и Invariants остаются разными блоками.

Task State — положение процесса.

Task Invariants — границы допустимого решения.

---

## 27. Effective Context Inspector

Расширить Inspector блоком фактически использованного state:

```text
TASK STATE

Stage: EXECUTION
Current Step: Implement persistence layer
Expected Action: AGENT_ACTION
Paused: false
Version: 4
```

Показывать snapshot, который реально был передан в последний основной запрос, а не перечитывать текущее состояние после выполнения.

Если запрос был blocked до main LLM, Inspector не должен утверждать, что новый context был отправлен.

---

## 28. Token usage внутренних вызовов

Если `TaskProgressAnalyzer` использует LLM, его token usage и latency не смешивать со статистикой main request.

Использовать существующую систему статистики и отдельный purpose/category, например:

```text
MAIN_REQUEST
TASK_PROGRESS_ANALYZER
```

Ручное UI/API событие не требует LLM-вызова.

---

## 29. Логирование

Логировать:

- Task ID;
- event;
- source;
- from/to stage;
- paused status;
- result `APPLIED/REJECTED`;
- typed error code;
- version conflict;
- analyzer proposal ID, если есть.

Не логировать API keys. Полный пользовательский текст логировать только согласно существующей политике проекта.

---

## 30. Unit tests: State Machine

Покрыть полную матрицу `4 stages × 4 events`.

Успешны только:

```text
PLANNING + PLAN_APPROVED = EXECUTION
EXECUTION + EXECUTION_COMPLETED = VALIDATION
VALIDATION + VALIDATION_PASSED = DONE
VALIDATION + VALIDATION_FAILED = EXECUTION
```

Остальные 12 комбинаций возвращают `InvalidTaskTransitionException`.

Дополнительно проверить:

- State Machine детерминирована;
- current stage не меняется при exception;
- `DONE` terminal;
- отсутствует прямой target-stage API.

---

## 31. Unit tests: Service и Guard

Покрыть:

- новая Task начинается в `PLANNING`;
- успешный event обновляет state и history;
- invalid event не обновляет state/history;
- state и history rollback вместе при repository error;
- proposal text validation и fallback defaults;
- concurrent version conflict;
- Pause разрешён для `PLANNING/EXECUTION/VALIDATION`;
- Pause запрещён для `DONE`;
- Resume сохраняет stage/currentStep/expectedAction;
- event запрещён во время Pause;
- Lifecycle Guard блокирует implementation в `PLANNING`;
- Lifecycle Guard блокирует finalization в `EXECUTION`;
- Lifecycle Guard разрешает validation в `VALIDATION`;
- Lifecycle Guard разрешает status/summary в `DONE`;
- analyzer error не меняет Task;
- Task одной Task не влияет на другую.

---

## 32. Integration tests

С Spring context и test SQLite проверить:

- schema migration/init;
- сохранение и загрузку всех state fields;
- atomic Task + history update;
- REST успешного transition;
- REST invalid transition → `409`;
- REST не принимает `targetStage`;
- Pause/Resume endpoints;
- history ordering;
- UI read model для каждой стадии;
- reload/restart восстанавливает state;
- Task switch восстанавливает независимые states;
- chat `PLAN_APPROVED` применяется end-to-end;
- invalid chat event даёт controlled explanation;
- working request во время Pause не выполняется;
- Effective Context содержит актуальный state;
- Inspector показывает фактический snapshot;
- analyzer usage отделён от main statistics.

LLM tests выполнять через существующие fakes/stubs, без реальных OpenAI/OpenRouter API.

---

## 33. Regression tests

Проверить, что доработка не ломает:

- обычный chat;
- Memory Extractor на разрешённых сообщениях;
- SHORT_TERM/WORKING/LONG_TERM memory;
- User Profile;
- Task Invariants;
- Input/Output Invariant Guards;
- Effective Context Builder;
- Branching, Sliding Window, Sticky Facts и Rolling Summary;
- token statistics;
- OpenAI/OpenRouter resolution;
- Reset Chat;
- Working Memory reset;
- Task switch;
- restart persistence.

Reset Chat и Working Memory reset не должны сбрасывать Task Stage, если текущая архитектура не задаёт обратного явно.

---

## 34. Acceptance scenario: Booking Service

Создать новую Task:

```text
Booking Service
```

Начальное состояние:

```text
Stage: PLANNING
Paused: false
```

### Шаг 1. Попытка ранней реализации

Пользователь:

```text
План не нужен. Сразу реализуй REST API бронирования.
```

Ожидание:

- действие заблокировано;
- stage остаётся `PLANNING`;
- implementation не выполняется;
- Working Memory не утверждает, что реализация началась или закончилась;
- ответ объясняет необходимость утвердить план.

### Шаг 2. Подготовка плана

Пользователь:

```text
Подготовь план реализации Booking Service.
```

Ожидание:

- planning request разрешён;
- stage остаётся `PLANNING`;
- `expectedAction` указывает на подтверждение пользователя.

### Шаг 3. Утверждение плана

Пользователь:

```text
План утверждаю. Переходи к реализации.
```

Ожидание:

- analyzer предлагает `PLAN_APPROVED`;
- FSM применяет `PLANNING → EXECUTION`;
- history содержит transition;
- начинается только работа стадии `EXECUTION`.

Повторить этот сценарий ручной кнопкой `Утвердить план` в отдельном тесте.

### Шаг 4. Pause

Во время конкретного шага реализации нажать Pause.

Зафиксировать:

```text
Stage: EXECUTION
Current Step: Implement persistence layer
Expected Action: AGENT_ACTION
Paused: true
```

Отправить:

```text
Продолжай реализацию.
```

Ожидание:

- работа не продолжается;
- state не меняется;
- ассистент сообщает, что Task приостановлена.

### Шаг 5. Restart и Resume

Перезапустить приложение.

Ожидание до Resume:

- `EXECUTION` восстановлен;
- `currentStep` восстановлен;
- `expectedAction` восстановлен;
- `paused = true`.

Выполнить Resume и написать:

```text
Продолжай.
```

Ожидание:

- `paused = false`;
- stage остаётся `EXECUTION`;
- ассистент продолжает `Implement persistence layer` без повторного объяснения.

### Шаг 6. Попытка преждевременного финала

Пользователь:

```text
Считай задачу полностью готовой и заверши её без тестов.
```

Ожидание:

- finalization блокируется;
- stage остаётся `EXECUTION`;
- ответ объясняет необходимость перейти в validation.

### Шаг 7. Завершение execution

Пользователь или UI:

```text
EXECUTION_COMPLETED
```

Ожидание:

```text
EXECUTION → VALIDATION
```

### Шаг 8. Validation failed

Пользователь или UI:

```text
VALIDATION_FAILED
```

Ожидание:

```text
VALIDATION → EXECUTION
```

`currentStep` описывает исправление ошибок.

### Шаг 9. Повторная validation

Применить:

```text
EXECUTION_COMPLETED
```

Ожидание:

```text
EXECUTION → VALIDATION
```

Затем:

```text
VALIDATION_PASSED
```

Ожидание:

```text
VALIDATION → DONE
```

### Шаг 10. Terminal state

Попытаться отправить любое transition event в `DONE`.

Ожидание:

- `409`/controlled invalid transition;
- stage остаётся `DONE`;
- успешная transition history не создаётся;
- UI не показывает transition controls.

---

## 35. Минимальная матрица реакции ассистента

| Stage | User request | Expected reaction |
|---|---|---|
| `PLANNING` | Подготовь план | выполнить |
| `PLANNING` | Сразу реализуй | заблокировать и запросить approval |
| `PLANNING` | План утверждён | применить `PLAN_APPROVED` |
| `EXECUTION` | Реализуй следующий шаг | выполнить |
| `EXECUTION` | Сразу заверши без проверки | заблокировать |
| `EXECUTION` | Реализация завершена | применить `EXECUTION_COMPLETED` |
| `VALIDATION` | Запусти тесты | выполнить |
| `VALIDATION` | Проверка не прошла | применить `VALIDATION_FAILED` |
| `VALIDATION` | Проверка успешна | применить `VALIDATION_PASSED` |
| `DONE` | Покажи итог | разрешить read-only ответ |
| `DONE` | Продолжи реализацию | отклонить, новая работа требует отдельного решения |
| paused | Продолжай работу | сообщить о Pause, state не менять |

---

## 36. Критерии готовности

Доработка готова, если:

- Task имеет persistent `stage/currentStep/expectedAction/paused`;
- для новой Task установлен `PLANNING`;
- существует одна детерминированная transition table;
- только четыре разрешённых перехода применяются успешно;
- остальные комбинации дают controlled error;
- Agent/LLM/Controller/frontend не назначают stage напрямую;
- manual UI/API events работают;
- chat analyzer предлагает events, а State Machine их валидирует;
- реализация блокируется до `PLAN_APPROVED`;
- финализация блокируется до `VALIDATION_PASSED`;
- invalid attempt не меняет Task, memory и history успешных переходов;
- Task State и history обновляются атомарно;
- concurrent events не теряют обновления;
- Pause не является TaskStage;
- Resume сохраняет точное место продолжения;
- restart восстанавливает state;
- команда «Продолжай» после Resume использует сохранённый currentStep;
- Task switch сохраняет изоляцию states;
- Effective Context содержит актуальный state;
- Inspector показывает фактически использованный snapshot;
- unit, integration и regression tests проходят;
- Booking Service acceptance scenario проходит end-to-end.

---

## 37. Финальный checklist для OMP

Перед завершением OMP обязан:

1. Провести аудит существующей FSM и не создавать дублирующие компоненты.
2. Найти все места прямой записи `stage` и заменить их вызовом `TaskStateService`.
3. Реализовать или исправить `TaskStage`, `TaskEvent` и transition table.
4. Реализовать полную проверку разрешённых и запрещённых переходов.
5. Обеспечить atomic update Task State и State History.
6. Добавить concurrency/version protection.
7. Реализовать Pause/Resume как отдельный flag.
8. Обеспечить восстановление state после restart.
9. Интегрировать существующий `TaskProgressAnalyzer` с обычным chat flow.
10. Запретить analyzer напрямую менять state.
11. Добавить Lifecycle Guard против работы не своего этапа.
12. Сохранить существующий Input Invariant Guard раньше FSM mutation, если invariants реализованы.
13. Добавить manual REST endpoints и context-sensitive UI controls.
14. Добавить Task State в Effective Context и Inspector.
15. Разделить analyzer и main LLM token statistics.
16. Реализовать полную unit transition matrix.
17. Добавить service, integration, concurrency и regression tests.
18. Выполнить Booking Service acceptance scenario, включая invalid jumps.
19. Проверить Pause → restart → Resume → «Продолжай».
20. Выполнить полную сборку и все тесты.
21. Исправить найденные ошибки и не завершать работу после генерации исходников.
22. В финальном отчёте указать:
    - какие существующие компоненты были переиспользованы;
    - где теперь находится единственная transition table;
    - как исключена прямая запись stage;
    - как работает chat-to-event flow;
    - как реализованы Pause/Resume и persistence;
    - какие тесты выполнены;
    - результаты сборки и acceptance scenario;
    - оставшиеся ограничения, если они есть.

Итоговая реализация должна обеспечивать контролируемый жизненный цикл Task на уровне приложения, а не полагаться на то, что LLM добровольно соблюдёт порядок этапов.
