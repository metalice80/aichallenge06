# Модель памяти агента: Memory Layers

## 1. Цель

Доработать существующего AI-агента и реализовать явную многоуровневую модель памяти.

Разделить память минимум на три слоя:

1. `SHORT_TERM` — краткосрочная память;
2. `WORKING` — рабочая память текущей задачи;
3. `LONG_TERM` — долговременная память агента.

Разные типы памяти должны:

- иметь различную семантику;
- иметь разный lifecycle;
- храниться отдельно;
- иметь явные правила записи;
- иметь явные правила очистки;
- участвовать в формировании контекста LLM.

Дополнительно необходимо реализовать диагностический UI, позволяющий проверить:

- какие данные находятся в каждом memory layer;
- какие изменения памяти произошли после последнего сообщения пользователя;
- какой effective context фактически сформирован для основного LLM-запроса;
- как memory layers влияют на ответы агента.

---

# 2. Существующий проект

Это доработка существующего приложения.

В проекте уже реализованы:

- Kotlin;
- Spring Boot;
- web chat UI;
- Agent abstraction;
- OpenAI;
- OpenRouter;
- LlmClient abstraction;
- выбор provider/model;
- OpenRouter plugins;
- SQLite persistence;
- восстановление состояния после restart;
- reset;
- token statistics;
- Rolling Summary;
- context strategies;
- Sliding Window;
- Sticky Facts;
- Branching.

Сначала изучить существующую архитектуру.

Не создавать новый параллельный Agent.

Не ломать существующие:

```text
OpenAI
OpenRouter
plugins
context strategies
branches
token statistics
persistence
```

Переиспользовать существующие abstractions и infrastructure там, где это возможно.

---

# 3. Memory и Context Strategy

`Memory` и `Context Strategy` являются разными понятиями.

Memory отвечает:

```text
Что агент знает?
Где информация хранится?
Как долго она живёт?
```

Context Strategy отвечает:

```text
Какая часть доступной информации
попадёт в конкретный LLM request?
```

Концептуально:

```text
                  MEMORY

        ┌──────────┼───────────┐
        ▼          ▼           ▼
   SHORT_TERM   WORKING     LONG_TERM
        │          │           │
        └──────────┼───────────┘
                   ▼
            Context Builder
                   │
                   ▼
           Context Strategy
                   │
                   ▼
               LLM Request
```

Не заменять существующие Context Strategies новой Memory model.

Они должны работать совместно.

---

# 4. Явная сущность Task

Для Working Memory необходимо ввести явное понятие:

```text
Task
```

Пользователь самостоятельно создаёт задачи и выбирает активную задачу в UI.

Агент не должен пытаться автоматически угадывать начало новой задачи.

Пример:

```text
Task:

[ Сервис бронирования ▼ ]

[ + Новая задача ] [ Завершить ]
```

---

# 5. Task model

Добавить persistent entity/model Task.

Минимально:

```text
id
name
status
createdAt
completedAt
```

Статусы:

```text
ACTIVE
COMPLETED
```

Допускается другое название статусов, если оно лучше соответствует архитектуре проекта.

---

# 6. Создание Task

Пользователь должен иметь возможность нажать:

```text
+ Новая задача
```

и указать название.

Например:

```text
Сервис бронирования
```

После создания задача становится активной.

---

# 7. Выбор Task

В UI добавить selector:

```text
Task:

[ Сервис бронирования ▼ ]
```

В selector должны отображаться существующие задачи.

При переключении Task должны переключаться:

- Working Memory;
- Short-Term conversation;
- branches текущей задачи, если используется Branching;
- соответствующий context state.

Long-Term Memory не переключается.

---

# 8. Завершение Task

Добавить:

```text
Завершить задачу
```

Завершённая задача:

- остаётся в SQLite;
- не теряет Working Memory;
- не теряет conversation history;
- может быть доступна для просмотра;
- не должна автоматически удаляться.

Не использовать завершение Task как memory reset.

---

# 9. Memory Layer 1 — SHORT_TERM

SHORT_TERM представляет текущий диалог активной Task.

Содержит:

```text
USER messages
ASSISTANT messages
```

Пример:

```text
USER:
Какую БД используем?

ASSISTANT:
PostgreSQL.

USER:
Добавим JWT.
```

SHORT_TERM основана на существующей Conversation infrastructure.

Не создавать второй дублирующий storage сообщений.

---

# 10. SHORT_TERM и Context Strategies

SHORT_TERM storage и effective context — не одно и то же.

Например:

```text
Task содержит 50 сообщений.

Sliding Window:
N = 10
```

В SQLite остаются:

```text
50 messages
```

Но в LLM context попадают:

```text
last 10 messages
```

Таким образом:

```text
SHORT_TERM STORAGE
       ↓
Context Strategy
       ↓
EFFECTIVE SHORT_TERM CONTEXT
```

---

# 11. SHORT_TERM и Branching

Если используется Branching:

```text
Task A
 ├── Main
 └── Branch 1
```

каждая branch имеет собственную effective SHORT_TERM history после checkpoint.

При переключении branch Memory Inspector должен показывать SHORT_TERM активной branch.

---

# 12. Memory Layer 2 — WORKING

WORKING Memory содержит структурированное состояние текущей Task.

Пример:

```text
Task: Сервис бронирования

goal       = реализовать сервис бронирования
language   = Kotlin
framework  = Spring Boot
database   = PostgreSQL
auth       = JWT
```

Working Memory не является копией Conversation.

Она представляет компактное состояние задачи.

---

# 13. Что сохранять в WORKING

Типичные данные:

- цель текущей Task;
- ограничения;
- технические параметры;
- промежуточные решения;
- договорённости в рамках задачи;
- выбранные технологии;
- текущее состояние работы;
- параметры, необходимые для дальнейшей работы над Task.

Пример:

```text
Для этой задачи используем Java 21 и PostgreSQL.
```

Должно потенциально дать:

```text
WORKING:

java_version = 21
database = PostgreSQL
```

---

# 14. WORKING принадлежит Task

Каждая Task имеет собственную Working Memory.

Например:

```text
Task A: Booking

language = Kotlin
database = PostgreSQL
```

и:

```text
Task B: Shop

language = Java
database = MySQL
```

Эти данные не должны смешиваться.

---

# 15. Переключение Task

При:

```text
Task A → Task B
```

Agent должен использовать Working Memory Task B.

При:

```text
Task B → Task A
```

должна восстановиться Working Memory Task A.

---

# 16. Memory Layer 3 — LONG_TERM

LONG_TERM Memory содержит устойчивую информацию, которая может использоваться между разными Tasks.

Например:

```text
preferred_answer_language = Russian
preferred_code_language = Kotlin
preferred_answer_style = concise
```

Типичные данные:

- устойчивые предпочтения;
- профиль;
- долгосрочные решения;
- долгоживущие знания;
- общие договорённости, не относящиеся только к одной Task.

---

# 17. LONG_TERM является глобальной

Long-Term Memory не принадлежит конкретной Task.

Например:

```text
LONG_TERM:

preferred_answer_language = Russian
```

должна быть доступна:

```text
Task A
Task B
Task C
```

Переключение Task не должно менять Long-Term Memory.

---

# 18. MemoryEntry

WORKING и LONG_TERM использовать как структурированную key-value memory.

Например:

```kotlin
data class MemoryEntry(
    val key: String,
    val value: String
)
```

Допускается metadata:

```text
createdAt
updatedAt
sourceMessageId
```

Не усложнять модель без необходимости.

---

# 19. Явное определение слоя

После сообщения пользователя необходимо явно определить:

```text
что сохранить
```

и:

```text
в какой memory layer сохранить
```

Не использовать правило:

```text
каждое сообщение → все memory layers
```

---

# 20. SHORT_TERM запись

Каждое USER/ASSISTANT сообщение сохраняется в существующую Conversation и тем самым становится SHORT_TERM memory.

Для SHORT_TERM не нужен отдельный LLM classifier.

---

# 21. Memory Extractor

Для WORKING и LONG_TERM добавить отдельный компонент.

Например:

```kotlin
interface MemoryExtractor {

    fun extract(
        userMessage: ChatMessage,
        task: Task,
        currentWorkingMemory: List<MemoryEntry>,
        currentLongTermMemory: List<MemoryEntry>
    ): MemoryUpdate
}
```

Точный API адаптировать к существующей архитектуре.

---

# 22. LLM-based extraction

Memory Extractor должен использовать LLM.

Архитектура:

```text
User Message
     ↓
MemoryExtractor
     ↓
LlmClientResolver
     ↓
configured provider
     ↓
configured model
     ↓
MemoryUpdate
```

Не привязывать extractor непосредственно к OpenAI или OpenRouter.

---

# 23. Конфигурация Memory Extractor

Provider и model задаются через `application.yml`.

Например:

```yaml
memory:
  enabled: true

  extractor:
    provider: OPENAI
    model: gpt-5-nano
```

или:

```yaml
memory:
  enabled: true

  extractor:
    provider: OPENROUTER
    model: <model-id>
```

Main chat model и Memory Extractor model должны быть независимыми.

---

# 24. MemoryUpdate

Extractor должен возвращать структурированный результат.

Например:

```kotlin
data class MemoryUpdate(
    val working: MemoryLayerUpdate,
    val longTerm: MemoryLayerUpdate
)

data class MemoryLayerUpdate(
    val upsert: List<MemoryEntry>,
    val delete: List<String>
)
```

---

# 25. Structured Output

Предпочтительный JSON:

```json
{
  "working": {
    "upsert": [
      {
        "key": "database",
        "value": "PostgreSQL"
      }
    ],
    "delete": []
  },
  "longTerm": {
    "upsert": [
      {
        "key": "preferred_code_language",
        "value": "Kotlin"
      }
    ],
    "delete": []
  }
}
```

Не использовать свободный текст, если provider/model позволяет надёжно получить JSON.

---

# 26. Prompt Memory Extractor

Использовать отдельную system instruction примерно следующего смысла:

```text
Проанализируй новое сообщение пользователя.

WORKING MEMORY:
Сохраняй информацию, относящуюся к текущей Task:
цели, ограничения, параметры, решения,
технологии и договорённости текущей задачи.

LONG-TERM MEMORY:
Сохраняй устойчивую информацию,
которая может быть полезна в других Tasks:
предпочтения, профиль, долгосрочные решения
и устойчивые знания о пользователе.

Не сохраняй случайные фразы, приветствия
и временные детали.

Не придумывай информацию.

Если существующее значение изменилось —
обнови его.

Если пользователь явно отменил информацию —
удали соответствующий key.

Верни только структурированный MemoryUpdate.
```

Prompt вынести в configuration/resource.

---

# 27. Пример классификации

User:

```text
Для этого проекта используем PostgreSQL,
а вообще примеры кода я предпочитаю на Kotlin.
```

Ожидаемый MemoryUpdate:

```text
WORKING
+ database = PostgreSQL

LONG_TERM
+ preferred_code_language = Kotlin
```

SHORT_TERM при этом содержит исходное сообщение полностью.

---

# 28. Обновление значения

Existing Working:

```text
database = PostgreSQL
```

User:

```text
Нет, давай всё-таки использовать MySQL.
```

Ожидается:

```text
WORKING
~ database: PostgreSQL → MySQL
```

Не создавать:

```text
database_1 = PostgreSQL
database_2 = MySQL
```

---

# 29. Persistence

Все memory layers должны переживать restart.

SHORT_TERM:

```text
существующая Conversation persistence
```

WORKING:

```text
persistent, scoped by taskId
```

LONG_TERM:

```text
persistent, global
```

---

# 30. Database model

Предпочтительно использовать существующую SQLite infrastructure.

Например:

```text
task

id
name
status
created_at
completed_at
```

```text
working_memory

id
task_id
key
value
source_message_id
created_at
updated_at
```

```text
long_term_memory

id
key
value
source_message_id
created_at
updated_at
```

Схема может отличаться, если существующая persistence architecture предлагает более подходящий вариант.

---

# 31. Repository

Не работать с SQLite непосредственно из Agent.

Предусмотреть abstractions примерно:

```text
TaskRepository
WorkingMemoryRepository
LongTermMemoryRepository
```

или более общий MemoryRepository.

---

# 32. Формирование LLM Context

Memory должна реально влиять на основной LLM request.

Conceptual context:

```text
SYSTEM PROMPT

LONG-TERM MEMORY
preferred_answer_language = Russian
preferred_code_language = Kotlin

WORKING MEMORY
task = Сервис бронирования
language = Java
database = PostgreSQL

SHORT-TERM
USER: ...
ASSISTANT: ...
USER: ...

NEW USER MESSAGE
...
```

---

# 33. Приоритет информации

Использовать следующий conceptual priority:

```text
CURRENT USER MESSAGE
        >
WORKING MEMORY
        >
LONG-TERM MEMORY
```

Например:

LONG_TERM:

```text
preferred_code_language = Kotlin
```

WORKING Task A:

```text
language = Java
```

Если пользователь спрашивает код для Task A, использовать Java.

Предпочтение Kotlin не должно переопределять явное решение текущей Task.

---

# 34. Context Strategy integration

Context Strategy определяет только SHORT_TERM часть.

Например:

```text
LONG_TERM
+
WORKING
+
Sliding Window(last N SHORT_TERM messages)
+
NEW MESSAGE
```

Для Branching:

```text
LONG_TERM
+
WORKING(active Task)
+
SHORT_TERM(active branch)
+
NEW MESSAGE
```

Не заставлять Working/Long-Term Memory случайно выпадать из Sliding Window.

---

# 35. Sticky Facts

В существующем проекте уже есть Sticky Facts.

Перед реализацией изучить этот механизм.

Sticky Facts и Memory Layers имеют похожую extraction infrastructure.

Переиспользовать:

- LLM extraction;
- structured output;
- key-value models;
- repositories;
- mapping;

если это разумно.

Не создавать две полностью независимые реализации одинаковой задачи.

При необходимости выполнить аккуратный refactoring в более общую Memory infrastructure без изменения поведения существующей стратегии.

---

# 36. Memory lifecycle

## SHORT_TERM

Принадлежит:

```text
Task + Conversation/Branch
```

## WORKING

Принадлежит:

```text
Task
```

## LONG_TERM

Принадлежит:

```text
Agent globally
```

---

# 37. Reset Chat

Существующая кнопка:

```text
Сбросить чат
```

должна очищать SHORT_TERM активной Task.

Она НЕ должна автоматически удалять:

```text
WORKING
LONG_TERM
```

---

# 38. Очистка Working Memory

Добавить отдельное действие:

```text
Очистить рабочую память
```

Оно очищает Working Memory только активной Task.

Не затрагивает:

```text
другие Tasks
LONG_TERM
```

---

# 39. Очистка Long-Term Memory

Добавить отдельное действие:

```text
Очистить долговременную память
```

Оно очищает только LONG_TERM.

Не удаляет:

```text
Tasks
Conversation
Working Memory
```

Добавить confirmation в UI.

---

# 40. Memory Inspector

Добавить в web UI диагностическую панель:

```text
MEMORY
```

с тремя разделами/tabs:

```text
Short-Term
Working
Long-Term
```

Цель панели — сделать memory model наблюдаемой и проверяемой.

---

# 41. Short-Term Inspector

Показывать effective short-term context активной Task/branch.

Например:

```text
SHORT-TERM MEMORY

Strategy: Sliding Window
Window: 4

USER       Какую БД используем?
ASSISTANT  PostgreSQL.
USER       Добавим JWT.
ASSISTANT  Хорошо...

4 messages in effective context
```

Не обязательно показывать всю persistent Conversation.

Важно показать именно сообщения, которые текущая Context Strategy считает effective short-term context.

---

# 42. Working Memory Inspector

Для активной Task показывать:

```text
WORKING MEMORY

Task: Сервис бронирования

goal        Сервис бронирования
language    Kotlin
framework   Spring Boot
database    PostgreSQL
auth        JWT
```

При переключении Task содержимое должно сразу меняться.

---

# 43. Long-Term Memory Inspector

Показывать глобальную память:

```text
LONG-TERM MEMORY

preferred_answer_language  Russian
preferred_code_language    Kotlin
answer_style               concise
```

При переключении Task этот блок не меняется.

---

# 44. Last Memory Update

Добавить диагностический блок:

```text
LAST MEMORY UPDATE
```

Он должен показывать результат последней классификации пользовательского сообщения.

Например:

```text
SHORT-TERM

+ USER:
  "Для этого проекта используем PostgreSQL,
   а примеры я предпочитаю на Kotlin."


WORKING

+ database = PostgreSQL


LONG-TERM

+ preferred_code_language = Kotlin
```

---

# 45. Типы изменений

В Last Memory Update использовать обозначения:

```text
+ added