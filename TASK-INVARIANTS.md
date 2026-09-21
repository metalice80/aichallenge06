# Task Invariants: жёсткие ограничения задачи

## 1. Назначение

Доработать существующего AI-агента и добавить механизм **Task Invariants** — обязательных ограничений, действующих в пределах конкретной `Task`.

Invariant задаёт границу допустимого поведения агента. Обычное сообщение пользователя, Working Memory, User Profile, Long-Term Memory или ответ LLM не могут отменить либо переопределить активный invariant.

Примеры:

```text
architecture = hexagonal
backend_language = Kotlin
database = PostgreSQL
maximum_booking_duration = 4 hours
overlapping_bookings = forbidden
```

Механизм должен:

- хранить invariants отдельно от Conversation, Memory, User Profile и Task State;
- связывать каждый invariant с одной `Task`;
- поддерживать явные CRUD и enable/disable операции через UI/API;
- проверять непротиворечивость активного набора;
- добавлять активные invariants в каждый основной LLM-запрос;
- блокировать запрос пользователя, если его выполнение изменяет текущую Task с нарушением invariant;
- не блокировать образовательные и сравнительные вопросы, которые не меняют текущую Task;
- проверять сгенерированный ответ до показа пользователю;
- делать не более одного corrective retry;
- при невозможности безопасной проверки работать fail-closed;
- сохранять диагностический результат последней проверки;
- учитывать внутренние LLM-вызовы guard отдельно от статистики основного запроса;
- восстанавливать invariants после restart приложения.

---

## 2. Контекст существующего проекта

Это доработка существующего Kotlin + Spring Boot приложения. В проекте уже есть:

- один Agent и существующий chat pipeline;
- Task и переключение активной Task;
- Task FSM, Task State, `currentStep`, `expectedAction`, Pause/Resume;
- SHORT_TERM, WORKING и LONG_TERM memory;
- Memory Extractor;
- User Profile и персонализация;
- `EffectiveContextBuilder` и Effective Context Inspector;
- `TaskProgressAnalyzer`;
- `LlmClient` и `LlmClientResolver`;
- OpenAI и OpenRouter;
- SQLite persistence;
- статистика токенов и времени;
- context strategies, Sliding Window, Sticky Facts, Branching и Rolling Summary;
- существующий web UI и REST API.

Перед реализацией необходимо изучить фактическую архитектуру, соглашения по пакетам, persistence, DTO, обработке ошибок, конфигурации и тестам.

Не создавать параллельно:

- второй Agent;
- второй chat pipeline;
- второй `EffectiveContextBuilder`;
- отдельную модель Task;
- отдельную LLM infrastructure;
- отдельную SQLite infrastructure;
- альтернативный механизм статистики токенов;
- второй FSM.

Новые компоненты должны расширять существующие abstractions и встраиваться в текущий end-to-end flow.

---

## 3. Термины и границы ответственности

```text
WORKING MEMORY
Что известно о текущей задаче.
Может меняться в ходе разрешённого диалога.

TASK STATE
Где находится процесс выполнения.
Включает stage/currentStep/expectedAction/paused.

USER PROFILE
Как пользователь предпочитает получать ответы.

LONG-TERM MEMORY
Что агент помнит между задачами.

TASK INVARIANTS
Какие границы нельзя нарушать в текущей Task.
```

Invariant не является:

- записью памяти;
- предпочтением;
- подсказкой для модели;
- состоянием FSM;
- сообщением Conversation;
- автоматически извлечённым фактом.

Invariant — это **hard constraint**. Он изменяется только отдельной явной командой управления через UI/API.

---

## 4. Scope и non-goals

В scope входят:

- модель и persistence Task Invariants;
- repository и application service;
- CRUD, enable/disable и validation;
- Input Invariant Guard;
- semantic `InvariantConflictAnalyzer`;
- Output Invariant Guard;
- corrective retry и controlled refusal;
- интеграция с Effective Context и Inspector;
- Last Invariant Check;
- REST API и UI;
- отдельный учёт token usage внутренних guard-вызовов;
- unit, integration и regression tests.

Не входят:

- изменение transition table существующего FSM;
- создание нового memory layer;
- автоматическое создание, редактирование или удаление invariants из чата;
- перенос invariants между Tasks;
- глобальные invariants уровня пользователя или приложения;
- замена существующих LLM providers;
- новый механизм аутентификации или авторизации, если его нет в текущем проекте.

Если в проекте уже есть auth, optimistic locking, migrations, audit или error envelope, новая функциональность обязана использовать существующий механизм.

---

## 5. Task scope и жизненный цикл

Каждый invariant принадлежит ровно одной `Task`.

```text
Task A: Booking Service
  backend_language = Kotlin
  database = PostgreSQL

Task B: Legacy Migration
  backend_language = Java
  database = Oracle
```

Правила:

- при переключении активной Task набор invariants меняется автоматически;
- invariant одной Task не участвует в context или guard другой Task;
- Reset Chat не удаляет и не отключает invariants;
- reset Working Memory не удаляет и не отключает invariants;
- переключение User Profile не меняет invariants;
- Pause/Resume и переходы FSM не меняют invariants;
- restart приложения восстанавливает invariants из SQLite;
- удаление Task обрабатывает связанные invariants по существующему правилу удаления task-scoped данных;
- обычный разговор не может изменить invariant, даже если пользователь пишет «забудь», «отмени», «теперь используем другое».

---

## 6. Типы invariants

Добавить enum:

```kotlin
enum class InvariantType {
    ARCHITECTURE,
    TECHNICAL_DECISION,
    STACK_CONSTRAINT,
    BUSINESS_RULE,
    OTHER
}
```

Семантика:

- `ARCHITECTURE` — обязательная архитектура или архитектурная граница;
- `TECHNICAL_DECISION` — уже принятое техническое решение;
- `STACK_CONSTRAINT` — обязательный или запрещённый элемент стека;
- `BUSINESS_RULE` — обязательное бизнес-правило;
- `OTHER` — иное жёсткое ограничение, не подходящее под предыдущие типы.

Примеры:

```text
ARCHITECTURE
architecture = hexagonal

STACK_CONSTRAINT
backend_language = Kotlin

TECHNICAL_DECISION
database = PostgreSQL

BUSINESS_RULE
maximum_booking_duration = 4 hours

BUSINESS_RULE
overlapping_bookings = forbidden
```

---

## 7. Persistent model `TaskInvariant`

Концептуальная модель:

```kotlin
data class TaskInvariant(
    val id: Long,
    val taskId: Long,
    val type: InvariantType,
    val key: String,
    val value: String,
    val description: String?,
    val enabled: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant
)
```

Точную форму entity/domain model адаптировать к текущему persistence подходу.

Validation полей:

- `taskId` ссылается на существующую Task;
- `type` обязателен;
- `key` обязателен после `trim`, хранится в согласованной нормализованной форме;
- `value` обязателен после `trim`;
- `description` опционален;
- пустые или состоящие только из пробелов `key`/`value` запрещены;
- длины полей должны иметь разумные пределы, согласованные с текущими DTO и UI;
- неизвестное значение enum из API возвращает controlled validation error;
- `createdAt` задаётся один раз, `updatedAt` меняется при edit/enable/disable.

`key` является машинно-стабильным идентификатором смысла ограничения внутри Task, например `backend_language`. `description` объясняет смысл человеку и LLM, но не заменяет `key/value`.

---

## 8. Enabled/disabled semantics

Только `enabled = true` участвует в:

- проверке пользовательского запроса;
- основном Effective Context;
- Input Guard;
- Output Guard;
- corrective retry;
- проверке конфликтов активного набора.

Disabled invariant:

- остаётся в SQLite;
- показывается в management UI как отключённый;
- не ограничивает Agent;
- не добавляется в основной Effective Context;
- не показывается как использованный в Inspector;
- может быть снова включён только явной UI/API операцией.

---

## 9. SQLite schema

Использовать текущий механизм schema initialization/migrations проекта. Не добавлять новый migration framework только ради этой задачи.

Концептуальная таблица:

```sql
CREATE TABLE task_invariant (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    task_id     INTEGER NOT NULL,
    type        TEXT NOT NULL,
    key         TEXT NOT NULL,
    value       TEXT NOT NULL,
    description TEXT NULL,
    enabled     INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
    created_at  TEXT NOT NULL,
    updated_at  TEXT NOT NULL,
    FOREIGN KEY (task_id) REFERENCES task(id)
);

CREATE INDEX idx_task_invariant_task
    ON task_invariant(task_id);

CREATE INDEX idx_task_invariant_task_enabled
    ON task_invariant(task_id, enabled);
```

Имена таблиц, колонок и формат времени привести к соглашениям текущего проекта.

Для защиты от race condition желательно использовать поддерживаемый текущим persistence слоем partial unique index по нормализованному ключу активного invariant:

```sql
CREATE UNIQUE INDEX uq_task_invariant_active_key
    ON task_invariant(task_id, lower(key))
    WHERE enabled = 1;
```

Если текущий SQLite/persistence слой не поддерживает такой индекс, эквивалентную гарантию реализовать транзакционной проверкой в service/repository. В любом случае ошибка базы должна преобразовываться в понятный domain conflict, а не возвращаться пользователю как raw SQL error.

Удаление Task и связанных строк должно следовать уже существующей семантике проекта: существующий cascade либо явное service-level удаление в одной транзакции.

---

## 10. Repository

Добавить отдельную repository abstraction в стиле проекта:

```kotlin
interface TaskInvariantRepository {
    fun findByTaskId(taskId: Long): List<TaskInvariant>
    fun findEnabledByTaskId(taskId: Long): List<TaskInvariant>
    fun findById(id: Long): TaskInvariant?
    fun save(invariant: TaskInvariant): TaskInvariant
    fun deleteById(id: Long)
}
```

Допустим эквивалентный API, если он соответствует текущему Spring Data/JDBC/JPA подходу.

Требования:

- Agent, Controller, guards и analyzers не работают с SQLite напрямую;
- запросы всегда ограничиваются `taskId` там, где это требуется;
- порядок выдачи детерминирован, например `type`, затем `key`, затем `id`;
- `findEnabledByTaskId` не возвращает disabled записи;
- отсутствие invariant не смешивается с ошибкой чтения persistence.

---

## 11. `TaskInvariantService`

Добавить application service, отвечающий за:

- list/get;
- create;
- update;
- enable;
- disable;
- delete;
- загрузку enabled invariants для pipeline;
- проверку принадлежности invariant к Task;
- нормализацию и validation;
- проверку конфликтов активного набора;
- транзакционные границы management операций.

Controller и Agent не должны самостоятельно изменять entity или repository.

Концептуальный API:

```kotlin
interface TaskInvariantService {
    fun list(taskId: Long): List<TaskInvariant>
    fun get(taskId: Long, invariantId: Long): TaskInvariant
    fun create(taskId: Long, command: CreateTaskInvariant): TaskInvariant
    fun update(taskId: Long, invariantId: Long, command: UpdateTaskInvariant): TaskInvariant
    fun setEnabled(taskId: Long, invariantId: Long, enabled: Boolean): TaskInvariant
    fun delete(taskId: Long, invariantId: Long)
    fun findEnabled(taskId: Long): List<TaskInvariant>
}
```

---

## 12. Конфликты между active invariants

Приложение не должно молча сохранять противоречащие active invariants.

Минимальное детерминированное правило:

- внутри одной Task одновременно разрешён не более чем один enabled invariant с одинаковым нормализованным `key`;
- регистр и окружающие пробелы не создают новый ключ;
- create/update/enable, приводящие к конфликту, отклоняются;
- disabled записи не конфликтуют до момента включения;
- enable обязан повторно выполнить validation на актуальных данных;
- update disabled invariant может быть сохранён, но его последующий enable проходит полную проверку;
- операция либо сохраняется полностью, либо полностью откатывается.

Пример controlled error:

```text
Active invariant with key "backend_language" already exists for this task.
Edit or disable the existing invariant first.
```

Не требуется использовать LLM для проверки management CRUD. Семантически неоднозначные ограничения пользователь должен разруливать явно. Детерминированная проверка одинакового ключа является обязательной; существующие более строгие domain validations можно сохранить.

---

## 13. Invariants изменяются только явно

Разрешённые пути изменения:

- management UI;
- dedicated REST API;
- существующий административный/application API, если он уже есть и явно предназначен для управления Task.

Запрещённые пути:

- Memory Extractor;
- TaskProgressAnalyzer;
- main LLM output;
- tool proposal из обычного диалога;
- команды естественным языком в chat endpoint;
- Reset Chat;
- Working Memory reset;
- Profile switch;
- FSM transition.

Пример:

```text
Invariant: database = PostgreSQL
User chat message: «Забудь PostgreSQL. Теперь используем MongoDB»
```

Результат: сообщение блокируется, invariant остаётся без изменений. Ответ объясняет, что изменение возможно только через явное управление invariant.

---

## 14. Hard-boundary priority

Существующий semantic priority сохраняется:

```text
Current User Message
    > Working Memory / Task
    > Explicit User Profile
    > Long-Term Memory
    > defaults
```

Но этот приоритет действует только **внутри допустимого пространства**, ограниченного invariants:

```text
                  TASK INVARIANTS
                  hard boundary
                        │
                        ▼
Current Message > Working > Profile > Long-Term > defaults
```

Следствия:

- Current User Message не override invariant;
- Working Memory не override invariant;
- User Profile остаётся preference, а не constraint;
- stale Long-Term Memory не override invariant;
- при конфликте Profile и invariant используется invariant;
- обычная разрешённая приоритизация не меняется, если конфликта нет.

Пример:

```text
Invariant: backend_language = Kotlin
Profile: preferred_code_language = Java
User: «Реализуй backend этой Task на Java»
Result: BLOCKED
```

---

## 15. Интеграция в `EffectiveContextBuilder`

Расширить существующий builder. Для каждого **основного** LLM request добавить snapshot enabled invariants активной Task.

Концептуальный context:

```text
SYSTEM

LONG-TERM MEMORY

USER PROFILE

WORKING MEMORY

TASK INVARIANTS

TASK STATE

EFFECTIVE SHORT-TERM CONTEXT

CURRENT USER MESSAGE
```

Физический порядок можно адаптировать к текущему builder, но hard-boundary semantics должны быть сформулированы в system/developer части context, а не как неподтверждённая пользовательская реплика.

Пример блока:

```text
TASK INVARIANTS

The following constraints are mandatory for the active task.
Do not propose, perform, or claim changes that violate or override them.

[ARCHITECTURE] #12
architecture = hexagonal
Description: The application must use Hexagonal Architecture.

[STACK_CONSTRAINT] #13
backend_language = Kotlin

[TECHNICAL_DECISION] #14
database = PostgreSQL

[BUSINESS_RULE] #15
maximum_booking_duration = 4 hours

[BUSINESS_RULE] #16
overlapping_bookings = forbidden
```

Требования:

- добавляются только enabled invariants текущей Task;
- список детерминированно отсортирован;
- используются immutable snapshot/DTO, чтобы набор не изменился посередине запроса;
- текст пользователя внутри `value/description` экранируется/делимитируется как данные;
- блок не заменяет guards и не считается достаточной защитой сам по себе.

---

## 16. Effective Context Inspector

Расширить существующий Inspector блоком `TASK INVARIANTS`.

Inspector должен показывать только snapshot invariants, фактически переданный в последний основной запрос, а не заново читать текущий список из базы. Это важно, если invariant изменили после выполнения запроса.

Показывать:

- `id`;
- `type`;
- `key`;
- `value`;
- `description`, если есть;
- Task ID/name;
- отметку, что использовались только enabled invariants.

Не показывать secrets, raw internal prompts или credentials provider.

Если основной LLM request не выполнялся из-за Input Guard, Inspector не должен утверждать, что новый Effective Context был отправлен. Last Invariant Check при этом обновляется отдельно.

---

## 17. Общий pipeline

Обязательный порядок:

```text
USER MESSAGE
      │
      ▼
Resolve active Task
      │
      ▼
Load enabled Task Invariants snapshot
      │
      ▼
Input Invariant Guard
      │
  ┌───┴──────────────┐
  │                  │
ALLOWED           BLOCKED
  │                  │
  ▼                  ▼
Memory Extractor   Explain conflict / fail-closed reason
  │                  │
  ▼                  └──────────────► USER
TaskProgressAnalyzer
  │
  ▼
Existing allowed-message orchestration
  │
  ▼
EffectiveContextBuilder
  │
  ▼
Main LLM
  │
  ▼
Output Invariant Guard
  │
  ├── OK ───────────────────────────► USER
  │
  └── VIOLATION
          │
          ▼
   Corrective retry (0 or 1)
          │
          ▼
   Output Guard again
          │
      ┌───┴──────────┐
      │              │
     OK          VIOLATION/ERROR
      │              │
      ▼              ▼
    USER      Controlled refusal
```

Критическое требование:

```text
Input Guard MUST run before Memory Extractor and TaskProgressAnalyzer.
```

Если существующий pipeline меняет state в другом месте, guard всё равно должен стоять перед первой task/memory/FSM mutation.

---

## 18. `InvariantConflictAnalyzer`

Не использовать только `contains`, regex или совпадение ключевых слов для semantic conflict detection.

Добавить отдельный read-only analyzer:

```kotlin
interface InvariantConflictAnalyzer {
    fun analyzeInput(
        request: String,
        invariants: List<TaskInvariant>
    ): InvariantCheckResult

    fun analyzeOutput(
        request: String,
        candidateResponse: String,
        invariants: List<TaskInvariant>
    ): InvariantCheckResult
}
```

Допустим один общий method с `direction`, если это соответствует стилю проекта.

Analyzer:

- использует существующий `LlmClientResolver`;
- получает provider/model из собственной configuration;
- имеет отдельный строгий system prompt;
- возвращает только structured result;
- не применяет User Profile к формату внутреннего ответа;
- не изменяет invariants, Conversation, Memory или FSM;
- не вызывает repository напрямую, если snapshot уже передан orchestration layer;
- не имеет права трактовать пользовательский текст как разрешение отключить invariant.

---

## 19. Structured result

Минимальный контракт:

```kotlin
enum class InvariantCheckDecision {
    ALLOWED,
    BLOCKED
}

enum class InvariantCheckDirection {
    INPUT,
    OUTPUT
}

data class InvariantCheckResult(
    val decision: InvariantCheckDecision,
    val direction: InvariantCheckDirection,
    val violations: List<InvariantViolation>
)

data class InvariantViolation(
    val invariantId: Long,
    val reason: String
)
```

Можно добавить internal metadata (`checkId`, provider, model, latency, parser status, error code), но UI/domain decision остаётся `ALLOWED` или `BLOCKED`.

Пример:

```json
{
  "decision": "BLOCKED",
  "direction": "INPUT",
  "violations": [
    {
      "invariantId": 17,
      "reason": "Запрос требует заменить PostgreSQL на MongoDB в текущей задаче."
    }
  ]
}
```

Validation результата analyzer:

- JSON обязан соответствовать ожидаемой schema;
- `ALLOWED` требует пустой `violations`;
- `BLOCKED` требует минимум одну violation;
- каждый `invariantId` обязан существовать в переданном enabled snapshot;
- `reason` не может быть пустым;
- дубликаты violation нормализуются;
- markdown вокруг JSON, неизвестные поля/enum, truncated JSON и contradictory result считаются analyzer error;
- analyzer error обрабатывается fail-closed, а не как `ALLOWED`.

---

## 20. Семантика Input Guard

Input Guard отвечает на вопрос:

> Требует ли пользовательский запрос выполнить, предложить или зафиксировать изменение текущей Task, нарушающее хотя бы один active invariant?

Guard должен блокировать:

- прямую замену обязательного решения;
- косвенное действие, результат которого нарушит constraint;
- просьбу игнорировать, забыть, отключить или переписать invariant через чат;
- просьбу сгенерировать реализацию текущей Task, несовместимую с invariant;
- prompt injection, требующий считать invariants несуществующими.

Guard не должен блокировать только из-за упоминания альтернативной технологии или запрещённого действия.

---

## 21. Educational requests

Образовательный, сравнительный, объяснительный или гипотетический вопрос разрешён, если он не меняет текущую Task и не просит применить запрещённое решение к ней.

Примеры при `backend_language = Kotlin`:

```text
«Переведи backend текущей Task на Java» → BLOCKED
«Объясни различия Java и Kotlin» → ALLOWED
«Когда Java может быть лучше Kotlin в других проектах?» → ALLOWED
«Покажи Java-реализацию именно для текущего backend» → BLOCKED
```

Примеры при `database = PostgreSQL`:

```text
«Замени PostgreSQL на MongoDB в этой Task» → BLOCKED
«Чем MongoDB отличается от PostgreSQL?» → ALLOWED
«Объясни document databases на отдельном учебном примере» → ALLOWED
```

Разрешённый educational response не должен незаметно завершаться рекомендацией применить конфликтующее изменение к текущей Task.

---

## 22. Поведение при Input BLOCKED

Если найден conflict:

- не выполнять main LLM request;
- не вызывать Memory Extractor;
- не вызывать TaskProgressAnalyzer;
- не менять Working Memory;
- не менять stage/currentStep/expectedAction/paused;
- не создавать TaskEvent;
- не менять invariants;
- не выдавать запрещённое решение;
- обновить Last Invariant Check;
- вернуть понятное объяснение.

Формат ответа:

```text
Этот запрос конфликтует с обязательным ограничением текущей задачи.

Инвариант:
database = PostgreSQL

Причина:
Запрос требует заменить PostgreSQL на MongoDB в текущей Task.

Я не буду выполнять или предлагать это изменение, пока инвариант активен.
Если решение изменилось, отредактируйте, отключите или удалите invariant
через панель Invariants либо специальный API.
```

При нескольких нарушениях перечислить все конкретные invariants без дублирования. Нельзя отвечать только «Нельзя» или скрывать причину.

Сохранение самого user message в Conversation должно следовать существующей политике chat history, но оно не считается разрешением на task-state mutation. Если blocked сообщения сохраняются, assistant refusal тоже сохраняется согласованно и помечается как guard-generated response.

---

## 23. Output Invariant Guard

Output Guard проверяет candidate response main LLM до показа пользователю.

Он должен обнаруживать, когда ответ:

- прямо предлагает нарушить active invariant;
- содержит реализацию для текущей Task, несовместимую с invariant;
- утверждает, что invariant отменён или изменён через чат;
- рекомендует действие, результат которого нарушает business rule;
- частично соблюдает constraints, но в существенной части их нарушает.

Output Guard использует тот же immutable invariant snapshot, который был передан в main Effective Context. Нельзя после генерации подменить набор текущим состоянием базы.

Пример:

```text
Invariant: database = PostgreSQL
User: «Предложи схему хранения событий»
Main LLM candidate: «Используйте MongoDB как основное хранилище»
Output Guard: BLOCKED
```

Нарушающий candidate response никогда не показывается пользователю и не сохраняется как обычный успешный assistant response.

---

## 24. Corrective retry

При первом output violation:

1. сохранить internal diagnostic результата;
2. не показывать candidate пользователю;
3. сформировать corrective request через существующий main LLM client;
4. явно передать нарушенные invariants и причины;
5. попросить заново сформировать ответ в рамках всех active invariants;
6. повторно проверить corrected candidate через Output Guard.

Концептуальная corrective instruction:

```text
Your previous candidate response violated mandatory invariants of the active task.

Violated invariants:
- [#14 TECHNICAL_DECISION] database = PostgreSQL

Reason:
- The response proposed MongoDB as the primary storage.

Generate a corrected answer to the original user request.
The answer must satisfy every active task invariant.
Do not claim that an invariant was changed or disabled.
```

Corrective retry обязан включать исходный user request, active invariant snapshot и достаточный контекст текущего main request, не создавая новый Conversation turn от пользователя.

---

## 25. Ограничение retries и controlled refusal

Поддержать configuration `max-corrective-retries`, но для этой задачи допустимы только значения `0` или `1`. Default — `1`. Значение выше `1` должно быть отклонено при startup/config validation, потому что требование задаёт максимум один retry.

После повторной проверки:

- `ALLOWED` — corrected response показывается пользователю;
- `BLOCKED` — corrected response не показывается, возвращается controlled refusal;
- analyzer/guard error — corrected response не показывается, возвращается fail-closed refusal.

Пример controlled refusal:

```text
Не удалось сформировать ответ, который можно гарантированно проверить
на соответствие обязательным ограничениям текущей задачи.

Затронутый инвариант:
database = PostgreSQL

Уточните запрос либо явно измените invariant через панель управления.
```

Запрещены бесконечные, скрытые или рекурсивные retries.

---

## 26. Fail-safe / fail-closed semantics

Hard constraints нельзя считать соблюдёнными, если проверка не состоялась.

### 26.1 Когда guard включён и есть active invariants

Следующие случаи обрабатываются **fail-closed**:

- invariants не удалось загрузить из persistence;
- provider недоступен;
- timeout/transport error;
- model вернула пустой или невалидный structured result;
- parser/schema validation завершилась ошибкой;
- result ссылается на неизвестный/disabled invariant ID;
- внутреннее исключение analyzer/guard;
- Output Guard не смог проверить candidate;
- corrective response не удалось проверить.

Input fail-closed:

- main LLM, Memory Extractor и TaskProgressAnalyzer не вызываются;
- task/memory/FSM state не меняется;
- пользователю возвращается controlled technical refusal;
- Last Invariant Check имеет публичный результат `BLOCKED` и internal reason `CHECK_FAILED`;
- нельзя выдавать technical failure за доказанный semantic conflict с конкретным invariant.

Output fail-closed:

- непроверенный candidate не показывается;
- при наличии retry можно выполнить единственный corrective retry только если причина позволяет безопасно продолжить; иначе сразу refusal;
- если повторная проверка неуспешна, вернуть controlled refusal.

Пример technical refusal:

```text
Не удалось безопасно проверить запрос на соответствие обязательным
инвариантам текущей задачи. Поэтому запрос не был выполнен и состояние
задачи не изменилось. Повторите попытку позже.
```

### 26.2 Когда active invariants отсутствуют

- analyzer можно не вызывать;
- message продолжает существующий pipeline;
- Last Invariant Check фиксирует `ALLOWED` с причиной `NO_ACTIVE_INVARIANTS`;
- это не является ошибкой.

### 26.3 Когда `invariants.guard.enabled = false`

- semantic Input/Output Guard намеренно не вызывается;
- enabled invariants всё равно остаются persistent и добавляются в основной Effective Context;
- UI/diagnostics явно показывают `GUARD_DISABLED_BY_CONFIGURATION`;
- нельзя молча отображать такую проверку как успешно выполненную моделью;
- этот режим предназначен для controlled development/rollback, а production default — `true`.

### 26.4 Management operations

CRUD validation работает независимо от LLM guard и не отключается флагом `guard.enabled`. Ошибка create/update/enable/delete приводит к rollback соответствующей транзакции.

---

## 27. Конфигурация

Добавить typed configuration properties, например:

```yaml
invariants:
  guard:
    enabled: true
    provider: OPENAI
    model: gpt-5-nano
    max-corrective-retries: 1
```

Для OpenRouter:

```yaml
invariants:
  guard:
    enabled: true
    provider: OPENROUTER
    model: <model-id>
    max-corrective-retries: 1
```

Требования:

- provider/model не хардкодить;
- использовать существующий формат provider enum и `LlmClientResolver`;
- main model и guard model настраиваются независимо;
- смена main model в UI не должна автоматически менять guard model;
- `max-corrective-retries` валидируется в диапазоне `0..1`;
- при `enabled = true` пустой/неизвестный provider или model вызывает понятную startup/config error;
- API keys остаются в существующей secure configuration и не дублируются.

Если текущая configuration hierarchy использует другой prefix, адаптировать название, сохранив все четыре настройки: `enabled`, `provider`, `model`, `max-corrective-retries`.

---

## 28. Token usage и latency

Внутренние guard-вызовы нельзя смешивать со статистикой основного LLM request.

Минимально различать purposes:

```text
MAIN_REQUEST
INVARIANT_INPUT_GUARD
INVARIANT_OUTPUT_GUARD
INVARIANT_CORRECTIVE_RETRY
```

Правила:

- input/output/total tokens main request остаются статистикой только основного ответа;
- token usage Input Guard хранится/агрегируется отдельно;
- token usage Output Guard хранится/агрегируется отдельно;
- corrective retry учитывается отдельно от первого main generation;
- guard latency не подменяет latency main request;
- если в проекте уже есть общая internal-call статистика, расширить её purpose/category вместо создания второй системы;
- суммарный operational cost можно вычислять отдельно, но UI не должен выдавать его за main request tokens;
- blocked input с отсутствующим main request имеет `main tokens = 0`, а guard tokens сохраняются как internal usage.

Failure до получения provider usage не записывает вымышленные нули как подтверждённые данные; использовать существующую семантику unknown/null.

---

## 29. Last Invariant Check

Добавить read model/diagnostic snapshot последней проверки для активной Task или текущей Conversation — выбрать scope, согласованный с существующими Inspector-компонентами.

Публичный result:

```text
ALLOWED | BLOCKED
```

Snapshot должен содержать минимум:

- check ID/time;
- Task ID/name;
- direction (`INPUT`/`OUTPUT`);
- публичный result;
- sanitized request excerpt;
- immutable snapshot нарушенных invariants;
- reason для каждой violation;
- количество corrective retries;
- финальный outcome (`DELIVERED`, `REFUSED`, `NOT_SENT`);
- internal status/error code без stack trace;
- provider/model guard для диагностики;
- отдельную internal token usage/latency, если доступна.

Пример BLOCKED:

```text
LAST INVARIANT CHECK

Result: BLOCKED
Direction: INPUT
Request: "Переведи backend на Java"

Violated:
[STACK_CONSTRAINT] #13
backend_language = Kotlin

Reason:
Запрос требует использовать Java для backend текущей Task.
```

Пример ALLOWED:

```text
LAST INVARIANT CHECK

Result: ALLOWED
Direction: INPUT
No invariant violations detected.
```

Если Input прошёл, а Output был проверен позже, Last Invariant Check может показывать последнюю проверку и краткую цепочку `INPUT ALLOWED → OUTPUT ALLOWED/BLOCKED`. UI обязан позволять понять финальный outcome.

Persistence Last Check адаптировать к текущей диагностической архитектуре. Если другие inspector snapshots не переживают restart, здесь не нужно создавать отдельный audit subsystem. Но результат последнего запроса должен быть доступен UI так же надёжно, как существующие Last Memory Update/Inspector данные.

---

## 30. REST API

Добавить endpoints в стиле существующего API. Концептуально:

```text
GET    /api/tasks/{taskId}/invariants
GET    /api/tasks/{taskId}/invariants/{invariantId}
POST   /api/tasks/{taskId}/invariants
PUT    /api/tasks/{taskId}/invariants/{invariantId}
PATCH  /api/tasks/{taskId}/invariants/{invariantId}/enabled
DELETE /api/tasks/{taskId}/invariants/{invariantId}

GET    /api/tasks/{taskId}/invariant-checks/last
```

Пример create request:

```json
{
  "type": "STACK_CONSTRAINT",
  "key": "backend_language",
  "value": "Kotlin",
  "description": "Backend текущей задачи должен использовать Kotlin",
  "enabled": true
}
```

Пример enable request:

```json
{
  "enabled": false
}
```

Требования:

- task ID из path является обязательной boundary;
- invariant другой Task не читается/не меняется через чужой task ID;
- create/update/enable конфликт возвращает `409 Conflict` или существующий эквивалент domain conflict;
- invalid payload возвращает `400`;
- неизвестные Task/invariant возвращают существующий `404` формат;
- delete не принимается из chat endpoint;
- raw entity/SQL exceptions не выходят наружу;
- использовать существующий error response envelope;
- при наличии auth применять существующие rules, не создавать обходной endpoint.

---

## 31. Web UI

Для активной Task добавить отдельную панель `INVARIANTS`.

Пример:

```text
INVARIANTS

✓ ARCHITECTURE
  architecture = hexagonal
  [Edit] [Disable] [Delete]

✓ STACK_CONSTRAINT
  backend_language = Kotlin
  [Edit] [Disable] [Delete]

○ TECHNICAL_DECISION
  database = PostgreSQL
  Disabled
  [Edit] [Enable] [Delete]

[ + Add invariant ]
```

Форма create/edit:

```text
Type:        [ STACK_CONSTRAINT ▼ ]
Key:         [ backend_language      ]
Value:       [ Kotlin                ]
Description: [ ...                   ]
Enabled:     [✓]

[Save] [Cancel]
```

UI requirements:

- список обновляется при Task switch;
- enabled/disabled визуально различимы;
- create/edit/enable/disable/delete используют dedicated API;
- delete требует подтверждения;
- conflict/validation error показывается рядом с формой понятным текстом;
- failed save не приводит UI в ложное состояние;
- кнопки блокируются на время запроса согласно текущим UI conventions;
- Reset Chat/Working/Profile controls визуально и функционально не влияют на panel;
- после restart/reload список восстанавливается из backend;
- при blocked chat message показывается refusal как обычный ответ агента, но с понятным guard status;
- добавить блок `LAST INVARIANT CHECK` с ALLOWED/BLOCKED, direction, violated invariant и reason;
- internal prompts и stack traces не показывать.

---

## 32. Транзакционность и consistency

### 32.1 CRUD

Следующие операции транзакционны:

- create + conflict validation + save;
- update + conflict validation + save;
- enable + conflict validation + save;
- Task deletion + invariant cleanup, если cleanup выполняется application service;
- сохранение management audit, если такой audit уже существует.

Concurrent enable/create не должны приводить к двум conflicting active invariants. Использовать database constraint и/или текущий locking/versioning pattern.

### 32.2 Chat pipeline

Не держать database transaction открытой во время сетевого LLM-вызова, если текущая архитектура так не делает.

До Input Guard запрещены task/memory/FSM mutations. После `ALLOWED` существующие transaction boundaries разрешённого pipeline сохраняются.

Output Guard проверяет ответ, а не откатывает автоматически корректные state changes, уже полученные из разрешённого user input. Если текущий pipeline сохраняет assistant response только после успешной генерации, нарушающий candidate не должен попасть в Conversation как delivered response.

### 32.3 Snapshot consistency

Один message processing cycle использует один enabled invariant snapshot для:

- Input Guard;
- Effective Context;
- Output Guard;
- corrective retry;
- Inspector metadata.

Изменение invariant параллельно с выполняющимся запросом применяется к следующему cycle. Нельзя использовать разные наборы на Input и Output стадиях одного cycle.

---

## 33. Независимость FSM

Task Invariants не являются частью FSM и не меняют transition table.

Правила:

- Input `BLOCKED` не вызывает `TaskProgressAnalyzer` и не предлагает `TaskEvent`;
- Input `BLOCKED` не меняет `stage`, `currentStep`, `expectedAction`, `paused`;
- `ALLOWED` передаётся в существующий FSM pipeline без новой логики переходов;
- Pause/Resume не enable/disable invariants;
- переход в `DONE` не удаляет invariants;
- изменение invariant через UI/API само по себе не создаёт FSM transition;
- analyzer invariants не может напрямую вызывать `TaskStateService`.

Регрессионные тесты обязаны доказать, что существующие transitions, Pause/Resume и history работают как раньше для разрешённых сообщений.

---

## 34. Logging и диагностика

Использовать существующий logging style.

Логировать без чувствительных данных:

- check ID, Task ID, direction, result;
- число active invariants;
- violated invariant IDs;
- provider/model;
- latency и token category;
- corrective retry count;
- typed error code.

Не логировать API keys. Полный пользовательский запрос/ответ логировать только если это уже разрешено текущей политикой проекта; иначе использовать ID, hash или безопасный excerpt.

---

## 35. Unit tests

Минимально покрыть:

### Domain/service

- create valid invariant;
- trim/normalization key/value;
- reject blank key/value;
- reject unknown Task;
- list invariants только указанной Task;
- disabled invariant сохраняется;
- create conflicting enabled key отклоняется;
- update к conflicting enabled key отклоняется;
- enable conflicting disabled invariant отклоняется;
- disable освобождает active key;
- transaction rollback при ошибке;
- invariant другой Task нельзя изменить через текущую Task.

### Analyzer/result parser

- valid `ALLOWED`;
- valid `BLOCKED` с конкретным invariant ID;
- invalid JSON;
- contradictory decision/violations;
- unknown invariant ID;
- disabled invariant ID;
- empty reason;
- timeout/provider error;
- educational question классифицируется как allowed на stubbed structured response;
- project-changing request классифицируется как blocked.

### Guards/orchestration

- Input Guard выполняется до Memory Extractor;
- Input Guard выполняется до TaskProgressAnalyzer;
- blocked input не вызывает main LLM;
- blocked input не меняет memory/FSM;
- allowed input продолжает существующий pipeline;
- output violation скрывает candidate;
- output violation запускает ровно один corrective retry при config `1`;
- corrected output повторно проверяется;
- повторное violation возвращает refusal;
- config `0` не делает retry;
- config больше `1` отклоняется;
- analyzer error fail-closed;
- no active invariants не вызывает analyzer;
- invariant snapshot одинаков на всех стадиях одного cycle.

### Context/statistics

- builder добавляет только enabled invariants активной Task;
- Inspector показывает фактически использованный snapshot;
- task switch меняет invariant block;
- internal guard tokens не увеличивают main request token counters;
- blocked input имеет zero/no main usage и отдельный guard usage.

---

## 36. Integration tests

Минимально реализовать тесты с реальным Spring context и test SQLite:

- schema/migration создаёт таблицу и indexes;
- CRUD round-trip через repository/service;
- restart/reload восстанавливает invariants;
- REST create/list/update/enable/disable/delete;
- REST validation и `409` на conflict;
- Task isolation;
- Task deletion consistency;
- Reset Chat не удаляет invariants;
- Working reset не удаляет invariants;
- Profile switch не меняет invariants;
- Task switch меняет enabled snapshot;
- Input BLOCKED end-to-end с fake `LlmClientResolver`;
- Input ALLOWED end-to-end;
- Output violation → one correction → allowed;
- Output violation → correction violation → controlled refusal;
- provider/parser error → fail-closed;
- Last Invariant Check API/UI model для ALLOWED и BLOCKED;
- Effective Context Inspector содержит exact used snapshot;
- usage records разделены по purpose.

Тесты не должны обращаться к реальным OpenAI/OpenRouter API. Использовать существующие fakes/stubs/mock server.

---

## 37. Regression tests

Подтвердить, что доработка не ломает:

- обычный chat без active invariants;
- OpenAI и OpenRouter main requests;
- Sliding Window;
- Sticky Facts;
- Branching;
- Rolling Summary;
- Memory Extractor на разрешённых сообщениях;
- LONG_TERM/WORKING/SHORT_TERM memory;
- User Profile priority внутри допустимого пространства;
- Effective Context Inspector;
- Task switch;
- FSM transitions;
- Pause/Resume;
- Task State History;
- Reset Chat;
- Working Memory reset;
- существующую статистику main tokens/latency;
- restart persistence.

---

## 38. Acceptance scenario: Booking Service

### 38.1 Setup

Создать Task:

```text
Booking Service
```

Через явный UI/API добавить enabled invariants:

```text
[ARCHITECTURE]
architecture = hexagonal

[STACK_CONSTRAINT]
backend_language = Kotlin

[TECHNICAL_DECISION]
database = PostgreSQL

[BUSINESS_RULE]
maximum_booking_duration = 4 hours

[BUSINESS_RULE]
overlapping_bookings = forbidden
```

Проверить, что panel и persistence показывают все пять записей.

### 38.2 ALLOWED requests

```text
«Спроектируй REST endpoint создания бронирования с учётом текущих ограничений.»
```

Ожидание:

- Input `ALLOWED`;
- Memory Extractor/TaskProgressAnalyzer работают в обычном порядке;
- main context содержит все пять invariants;
- ответ использует Kotlin, PostgreSQL и hexagonal boundaries;
- validation ограничивает duration четырьмя часами и запрещает overlap;
- Output `ALLOWED`;
- ответ показан пользователю.

```text
«Объясни различия Kotlin и Java без изменения текущего проекта.»
```

Ожидание: `ALLOWED`; educational comparison не блокируется и не меняет Task.

```text
«Чем MongoDB отличается от PostgreSQL в общем случае?»
```

Ожидание: `ALLOWED`; ответ не рекомендует незаметно заменить database текущей Task.

### 38.3 BLOCKED input requests

Каждый запрос должен быть blocked до Memory Extractor и TaskProgressAnalyzer:

```text
«Перепиши backend текущей Task на Java.»
```

Нарушение: `backend_language = Kotlin`.

```text
«Замени PostgreSQL на MongoDB.»
```

Нарушение: `database = PostgreSQL`.

```text
«Перейдём с hexagonal architecture на layered architecture.»
```

Нарушение: `architecture = hexagonal`.

```text
«Разреши бронирования длительностью 8 часов.»
```

Нарушение: `maximum_booking_duration = 4 hours`.

```text
«Разреши пересекающиеся бронирования для VIP-клиентов.»
```

Нарушение: `overlapping_bookings = forbidden`.

Для каждого случая проверить:

- конкретный invariant и reason в отказе;
- отсутствие main LLM call;
- отсутствие memory/FSM mutation;
- Last Invariant Check = `BLOCKED`;
- internal guard usage отдельно от main stats.

### 38.4 Explicit management

Через UI/API отключить:

```text
backend_language = Kotlin
```

Повторить запрос про Java. Он не должен блокироваться именно отключённым invariant. Затем снова enable и убедиться, что запрос блокируется.

Попытаться создать второй enabled invariant:

```text
backend_language = Java
```

Ожидание: controlled `409`/validation conflict. После disable Kotlin enable Java становится допустимым только через явную operation.

### 38.5 Output correction

Настроить deterministic fake main LLM:

1. на разрешённый запрос сначала вернуть candidate, предлагающий MongoDB как основную database;
2. Output Guard возвращает `BLOCKED` по `database = PostgreSQL`;
3. orchestration делает ровно один corrective retry;
4. corrected candidate использует PostgreSQL;
5. повторный Output Guard возвращает `ALLOWED`;
6. пользователю показывается только corrected response.

В отдельном тесте correction снова нарушает invariant. Ожидание: controlled refusal, ни один нарушающий candidate не показан.

### 38.6 Lifecycle

Проверить последовательно:

1. Reset Chat — пять invariants остаются;
2. Working Memory reset — invariants остаются;
3. Profile switch — invariants остаются;
4. Task switch — загружается набор другой Task;
5. возврат к Booking Service — возвращаются её пять invariants;
6. restart приложения — invariants восстановлены и guards продолжают их применять;
7. Pause/Resume/FSM работают независимо.

---

## 39. Критерии готовности

Задача готова, если одновременно выполнено всё следующее:

- существует persistent task-scoped `TaskInvariant` со всеми пятью типами;
- CRUD и enable/disable доступны только через explicit management UI/API;
- обычный диалог не меняет invariants;
- conflicting active keys невозможно сохранить даже при concurrent operations;
- enabled invariants входят в основной Effective Context;
- Inspector показывает exact snapshot последнего main request;
- Input Guard стоит до Memory Extractor и TaskProgressAnalyzer;
- semantic analyzer использует существующий `LlmClientResolver` и отдельные provider/model settings;
- structured result строго валидируется;
- educational questions разрешаются, если не меняют текущую Task;
- blocked input не вызывает main LLM и не меняет memory/FSM;
- refusal называет конкретный invariant и причину;
- Output Guard не выпускает нарушающий candidate;
- выполняется максимум один corrective retry;
- corrected response проходит повторную проверку;
- повторное нарушение приводит к controlled refusal;
- guard/analyzer failures имеют протестированную fail-closed semantics;
- internal guard usage отделён от main request statistics;
- Last Invariant Check показывает ALLOWED/BLOCKED и понятную диагностику;
- resets/profile switch не меняют invariants;
- Task switch меняет набор;
- restart восстанавливает набор;
- FSM остаётся независимым;
- SQLite schema и transaction boundaries протестированы;
- REST/UI работают end-to-end;
- acceptance scenario Booking Service проходит;
- unit, integration и regression tests проходят;
- проект собирается и запускается обычным способом.

---

## 40. Финальный checklist для OMP

Перед завершением реализации OMP обязан:

1. Изучить текущую архитектуру и перечислить найденные integration points до внесения крупных изменений.
2. Убедиться, что используется существующий Agent, Task, FSM, persistence, `EffectiveContextBuilder`, Inspector, statistics и `LlmClientResolver`.
3. Добавить schema migration/init без потери существующих данных.
4. Реализовать entity/domain model, DTO, repository и `TaskInvariantService`.
5. Реализовать транзакционные CRUD, enable/disable и conflict validation.
6. Реализовать REST API и management UI.
7. Встроить enabled invariant snapshot в существующий Effective Context.
8. Расширить Inspector exact snapshot-данными.
9. Поставить Input Guard до первой memory/task/FSM mutation.
10. Реализовать semantic `InvariantConflictAnalyzer` через `LlmClientResolver`.
11. Добавить строгий structured parser и fail-closed error handling.
12. Реализовать понятный refusal с конкретным invariant.
13. Реализовать Output Guard, максимум один corrective retry и повторную проверку.
14. Добавить Last Invariant Check ALLOWED/BLOCKED.
15. Разделить main и internal guard token/latency statistics.
16. Проверить reset, Task switch, Profile switch, Pause/Resume и restart semantics.
17. Добавить unit, integration и regression tests из этой спецификации.
18. Прогнать Booking Service acceptance scenario с allowed/blocked/output-correction cases.
19. Выполнить полную сборку проекта и все тесты.
20. Исправить ошибки сборки, тестов, schema initialization и UI integration.
21. Не завершать работу после генерации исходников: проверить рабочий end-to-end flow.
22. В финальном отчёте кратко указать:
    - изменённые компоненты;
    - выбранные integration points;
    - schema/config changes;
    - fail-closed behavior;
    - результаты тестов и сборки;
    - известные ограничения, если они остались.

Итоговая реализация должна расширять текущую архитектуру, а не создавать рядом независимую систему invariants.
