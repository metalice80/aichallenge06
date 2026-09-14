# День 10. Управление контекстом: разные стратегии без Summary

## 1. Цель

Доработать существующего AI-агента и реализовать три независимые стратегии управления контекстом:

1. `SLIDING_WINDOW`
2. `STICKY_FACTS`
3. `BRANCHING`

Пользователь должен иметь возможность выбрать активную стратегию непосредственно в web UI через выпадающий список.

Одновременно активна только одна стратегия.

Стратегии должны определять, какой контекст фактически отправляется LLM.

Существующая полная история сообщений при этом должна продолжать сохраняться в SQLite.

---

# 2. Существующее приложение

Это доработка существующего приложения.

Уже реализованы:

- Kotlin;
- Spring Boot;
- web chat UI;
- Agent abstraction;
- OpenAI;
- OpenRouter;
- LlmClient abstraction;
- выбор provider;
- выбор model;
- OpenRouter plugins;
- SQLite persistence;
- восстановление истории после restart;
- reset;
- статистика токенов;
- Rolling Summary из предыдущего задания.

Не переписывать существующие работающие компоненты без необходимости.

---

# 3. Важное требование: стратегии без Summary

Новые стратегии работают **без Rolling Summary**.

При выборе:

```text
SLIDING_WINDOW
STICKY_FACTS
BRANCHING
```

Rolling Summary не должен участвовать в формировании LLM context.

Не смешивать, например:

```text
Summary + Sliding Window
```

или:

```text
Summary + Sticky Facts
```

Новые стратегии должны быть самостоятельными вариантами управления контекстом.

Существующую реализацию Rolling Summary не удалять.

Архитектура должна позволять впоследствии иметь отдельную стратегию:

```text
ROLLING_SUMMARY
```

если она уже присутствует в UI или будет добавлена позже.

---

# 4. Context Strategy

Добавить отдельную abstraction стратегии управления контекстом.

Например:

```kotlin
interface ContextStrategy {

    val type: ContextStrategyType

    fun buildContext(
        conversation: Conversation,
        context: ContextBuildContext
    ): List<ChatMessage>
}
```

Тип:

```kotlin
enum class ContextStrategyType {
    SLIDING_WINDOW,
    STICKY_FACTS,
    BRANCHING
}
```

Конкретный API может отличаться, если существующая архитектура предлагает более удачный вариант.

Главное: не реализовывать все три алгоритма через один большой `when` внутри `ChatAgent`.

Предпочтительная архитектура:

```text
                         ChatAgent
                            │
                            ▼
                    ContextStrategyResolver
                            │
            ┌───────────────┼────────────────┐
            │               │                │
            ▼               ▼                ▼
      SlidingWindow    StickyFacts       Branching
        Strategy        Strategy          Strategy
```

---

# 5. Выбор стратегии в UI

В web UI добавить выпадающий список:

```text
Context strategy:

[ Sliding Window ▼ ]
```

Доступные варианты:

```text
Sliding Window
Sticky Facts
Branching
```

Выбранная стратегия должна передаваться backend при отправке сообщения.

Например:

```json
{
  "message": "Продолжим",
  "provider": "OPENROUTER",
  "model": "...",
  "contextStrategy": "SLIDING_WINDOW"
}
```

---

# 6. Переключение стратегии

Пользователь должен иметь возможность переключать стратегию во время работы приложения.

Например:

```text
SLIDING_WINDOW
      ↓
STICKY_FACTS
      ↓
BRANCHING
```

Смена стратегии не должна удалять исходную Conversation.

Она влияет только на то, **какая часть сохранённых данных используется для следующего LLM request**.

---

# 7. Стратегия 1 — Sliding Window

## Принцип

Передавать LLM только последние `N` сообщений текущего диалога.

Все более старые сообщения сохраняются в SQLite, но не входят в LLM context.

Пример:

```text
N = 6

Conversation:

Message 1
Message 2
Message 3
Message 4
Message 5
Message 6
Message 7
Message 8
Message 9
Message 10
```

В LLM request:

```text
System Prompt

Message 5
Message 6
Message 7
Message 8
Message 9
Message 10

New User Message
```

Messages 1–4 остаются в SQLite и UI, но не отправляются модели.

---

# 8. Конфигурация Sliding Window

Размер окна задаётся через `application.yml`.

Например:

```yaml
context:
  strategies:
    sliding-window:
      size: 10
```

`size` означает количество последних исторических сообщений, которые должны передаваться LLM.

Значение:

```text
size > 0
```

должно валидироваться при старте приложения.

Не хардкодить N.

---

# 9. Sliding Window и новый user message

Не допустить неоднозначность подсчёта.

`N` означает количество уже существующих сообщений Conversation.

Новое сообщение пользователя добавляется к context отдельно.

То есть:

```text
N = 10
```

означает:

```text
System Prompt
+
последние 10 сохранённых сообщений
+
новый User message
```

---

# 10. Sliding Window persistence

Полная история не удаляется.

SQLite:

```text
Messages 1...100
```

LLM context при:

```text
N = 10
```

содержит только:

```text
Messages 91...100
+
new message
```

UI при этом продолжает показывать всю историю.

---

# 11. Стратегия 2 — Sticky Facts / Key-Value Memory

## Принцип

Помимо последних `N` сообщений хранить отдельный persistent блок важных фактов.

Например:

```text
goal = Разработать AI-агента
language = Kotlin
framework = Spring Boot
database = SQLite
preferred_provider = OpenRouter
constraint = Не использовать Spring AI
```

В LLM request передавать:

```text
System Prompt
+
Sticky Facts
+
последние N сообщений
+
новое сообщение пользователя
```

---

# 12. Что считать fact

Facts предназначены для долгоживущей информации, которую важно помнить, даже если исходное сообщение уже вышло из context window.

Типичные категории:

```text
цель
ограничение
предпочтение
решение
договорённость
важная настройка
имя или идентификатор, сообщённый пользователем
текущий проектный контекст
```

Не сохранять каждую случайную фразу как fact.

---

# 13. Структура Facts

Минимальная внутренняя модель:

```kotlin
data class MemoryFact(
    val key: String,
    val value: String
)
```

Допускается дополнительно хранить:

```text
updatedAt
sourceMessageId
```

или другую metadata.

Ключ должен быть стабильным настолько, насколько это возможно.

Например повторное сообщение:

```text
preferred_language = Kotlin
```

должно обновить существующий key, а не бесконечно создавать дубликаты.

---

# 14. Persistent Facts

Facts должны храниться в SQLite отдельно от сообщений.

Например:

```text
conversation_fact

id
key
value
updated_at
source_message_id
```

Конкретная схема может отличаться.

Facts должны переживать:

```text
application restart
```

и восстанавливаться вместе с Conversation.

---

# 15. Обновление Facts

Facts необходимо анализировать и обновлять **после каждого сообщения пользователя**.

Использовать отдельный компонент:

```kotlin
interface FactsExtractor {

    fun updateFacts(
        existingFacts: List<MemoryFact>,
        userMessage: ChatMessage
    ): FactsUpdate
}
```

Не помещать эту логику непосредственно в Controller.

---

# 16. LLM-based Facts Extractor

Для определения важных facts использовать LLM.

Не пытаться реализовывать extraction набором regex или жёстких `if`.

Использовать существующую infrastructure:

```text
FactsExtractor
      ↓
LlmClientResolver
      ↓
configured provider
      ↓
configured model
```

Provider и model для extraction задаются через configuration.

Например:

```yaml
context:
  strategies:

    sticky-facts:
      window-size: 10

      extractor:
        provider: OPENAI
        model: gpt-5-nano
```

Допускается OpenRouter:

```yaml
extractor:
  provider: OPENROUTER
  model: ...
```

---

# 17. Независимость Facts Extractor

Provider/model, отвечающие пользователю, и provider/model facts extractor должны быть независимыми.

Например:

```text
Main model:
OPENROUTER / anthropic/...

Facts extractor:
OPENAI / gpt-5-nano
```

Смена основной модели пользователем не должна менять configured facts extractor.

---

# 18. Формат ответа Facts Extractor

Предпочтительно заставить extractor возвращать структурированный результат.

Например концептуально:

```json
{
  "upsert": [
    {
      "key": "programming_language",
      "value": "Kotlin"
    }
  ],
  "delete": []
}
```

Если пользователь позднее говорит:

```text
Я больше не использую Kotlin, теперь проект на Java.
```

Extractor должен иметь возможность обновить:

```text
programming_language
```

на новое значение.

Если факт явно перестал быть актуальным, должна существовать возможность его удалить.

---

# 19. Prompt для Facts Extractor

Использовать отдельный system prompt.

Пример смысла:

```text
Проанализируй новое сообщение пользователя.

Выделяй только долгоживущие факты, которые полезны
для продолжения будущего диалога:

- цели;
- ограничения;
- предпочтения;
- решения;
- договорённости;
- важные параметры.

Не сохраняй случайные фразы и временные детали.

Учитывай существующие facts.

Верни изменения в структурированном формате:
facts to upsert и keys to delete.

Не добавляй факты, которых пользователь не сообщал.
```

Prompt вынести в configuration либо отдельный resource.

---

# 20. Sticky Facts Window

Количество последних обычных сообщений задаётся отдельно:

```yaml
context:
  strategies:
    sticky-facts:
      window-size: 10
```

Формирование context:

```text
System Prompt

Facts:
key1 = value1
key2 = value2
key3 = value3

Last N messages

New User Message
```

---

# 21. Представление Facts в LLM request

Не создавать provider-specific role `FACT`.

Во внутренней модели facts остаются отдельной сущностью.

При построении request преобразовать их в дополнительное context/system message.

Например:

```text
SYSTEM:

Persistent facts from the conversation:

- programming_language: Kotlin
- framework: Spring Boot
- database: SQLite
```

Не смешивать facts с обычным `ASSISTANT` message.

---

# 22. Facts и полная история

Полные сообщения сохраняются независимо от Sticky Facts.

То есть:

```text
SQLite:
полная Conversation
+
Facts
```

а в LLM request:

```text
Facts
+
last N messages
```

Старые сообщения не удаляются.

---

# 23. Ошибка Facts Extractor

Если extraction завершился ошибкой:

- Conversation не должна повреждаться;
- existing facts не удалять;
- пользовательское сообщение не терять;
- залогировать ошибку.

Предпочтительно продолжить основной LLM request с существующими facts.

---

# 24. Стратегия 3 — Branching

## Принцип

Реализовать возможность создавать ветки разговора.

Пользователь должен иметь возможность:

1. вести обычный диалог;
2. в произвольный момент создать новую ветку;
3. новая ветка создаётся от текущей точки Conversation;
4. продолжить разговор независимо в новой ветке;
5. переключиться обратно на предыдущую ветку;
6. продолжить предыдущий разговор с его собственным context;
7. иметь несколько веток.

---

# 25. Пример Branching

Исходный диалог:

```text
Root:

User: Проектируем API.
Assistant: ...
User: Используем REST.
Assistant: ...
```

Пользователь нажимает:

```text
Создать ветку
```

Создаётся:

```text
Branch A
```

от текущего checkpoint.

Далее в Branch A:

```text
User: Давай использовать GraphQL.
Assistant: ...
```

Затем пользователь переключается обратно:

```text
Root
```

и пишет:

```text
User: Продолжим вариант с REST.
```

Root не должен содержать сообщения:

```text
Давай использовать GraphQL.
```

Обе ветки продолжаются независимо.

---

# 26. UI Branching

При выбранной стратегии:

```text
BRANCHING
```

показывать дополнительный UI.

Например:

```text
Context strategy: [ Branching ▼ ]

Branch:
[ Main ▼ ]

[ Создать ветку ]
```

В выпадающем списке веток отображать существующие ветки.

Например:

```text
Main
Branch 1
Branch 2
```

---

# 27. Создание ветки

Добавить кнопку:

```text
Создать ветку
```

При нажатии новая ветка создаётся от текущего состояния активной ветки.

Для первой реализации checkpoint = текущая последняя точка активного диалога.

Не требуется позволять пользователю визуально выбирать произвольное старое сообщение как checkpoint.

Но architecture желательно не делать такой вариант невозможным в будущем.

---

# 28. Имя ветки

Для минимальной реализации название можно генерировать автоматически:

```text
Main
Branch 1
Branch 2
Branch 3
```

Если легко реализуется, допускается дать пользователю возможность задать название.

Это необязательное улучшение.

---

# 29. Branch model

Ввести сущность Branch.

Например концептуально:

```kotlin
data class ConversationBranch(
    val id: Long,
    val name: String,
    val parentBranchId: Long?,
    val checkpointMessageId: Long?,
    val createdAt: Instant
)
```

Точная модель должна соответствовать существующей persistence architecture.

---

# 30. Main branch

У Conversation всегда должна существовать начальная ветка:

```text
Main
```

или эквивалент.

До создания первой дополнительной ветки пользователь работает в `Main`.

---

# 31. Branch ancestry

При создании Branch B из Branch A необходимо помнить checkpoint.

Например:

```text
Main:
M1
M2
M3
M4

Branch 1 создан после M4.

Branch 1:
M1
M2
M3
M4
B1
B2
```

Не обязательно физически копировать `M1...M4` в database.

Предпочтительно хранить parent/checkpoint relationship и строить effective history.

---

# 32. Не дублировать историю без необходимости

Предпочтительная структура данных:

```text
Branch
  parentBranchId
  checkpointMessageId
```

и сообщения, принадлежащие конкретной ветке.

Например:

```text
Main:
M1 M2 M3 M4

Branch 1:
parent = Main
checkpoint = M4

own messages:
B1 B2
```

Effective history Branch 1:

```text
M1 M2 M3 M4 B1 B2
```

Это предпочтительнее полного копирования старых messages при каждом branch.

Если существующая модель Conversation существенно упрощает копирование, допускается иной подход, но нужно избежать очевидно избыточной архитектуры.

---

# 33. Независимость веток

После divergence:

```text
Main:
M1 M2 M3 M4 M5 M6

Branch 1:
M1 M2 M3 M4 B1 B2
```

`M5/M6` не должны появляться в Branch 1.

`B1/B2` не должны появляться в Main.

---

# 34. Переключение ветки

Добавить API для выбора активной ветки.

Например:

```http
POST /api/chat/branches/{branchId}/activate
```

или другой REST design, соответствующий проекту.

После переключения:

- UI показывает историю выбранной ветки;
- новые сообщения записываются в выбранную ветку;
- LLM получает context выбранной ветки;
- другая ветка не изменяется.

---

# 35. API списка веток

Добавить возможность получить список веток.

Например:

```http
GET /api/chat/branches
```

Пример response:

```json
[
  {
    "id": 1,
    "name": "Main",
    "active": false
  },
  {
    "id": 2,
    "name": "Branch 1",
    "active": true
  }
]
```

---

# 36. API создания ветки

Например:

```http
POST /api/chat/branches
```

Создаёт branch от текущего checkpoint активной ветки.

Response может возвращать созданную ветку:

```json
{
  "id": 2,
  "name": "Branch 1"
}
```

После создания допускается автоматически сделать новую ветку активной.

Предпочтительно именно такое поведение.

---

# 37. Branching и N

Стратегия `BRANCHING` не использует параметр N.

Она получает полную effective history выбранной ветки.

То есть:

```text
System Prompt
+
effective branch history
+
new User message
```

Summary при этом не используется.

---

# 38. Branching persistence

Все данные должны сохраняться в SQLite:

```text
branches
branch relationships
checkpoint
branch messages
active branch
```

После restart должны восстановиться:

- список веток;
- Main;
- parent relationships;
- checkpoint;
- active branch;
- history каждой ветки.

---

# 39. Branching и UI history

При переключении ветки frontend должен запросить и показать только effective history выбранной ветки.

Сообщения другой ветки в chat window не показывать.

---

# 40. Статистика токенов и ветки

Существующую статистику токенов необходимо сохранить.

Для Branching предпочтительно вести статистику отдельно для каждой ветки.

То есть:

```text
Main:
conversationInputTokens = ...

Branch 1:
conversationInputTokens = ...
```

После создания новой ветки исторические API-вызовы родительской ветки не должны автоматически считаться новыми API-вызовами дочерней ветки.

Накопленная статистика branch должна отражать фактические LLM requests, выполненные в рамках этой ветки после её создания, либо следовать существующей модели статистики проекта, если там уже используется более подходящий подход.

Не удваивать usage только из-за факта создания branch.

---

# 41. Переключение стратегии и Branching state

Если пользователь:

```text
BRANCHING → SLIDING_WINDOW
```

ветки не удалять.

При возврате:

```text
SLIDING_WINDOW → BRANCHING
```

ранее созданные branches должны оставаться доступными.

Смена стратегии не является reset.

---

# 42. Reset

Reset должен полностью очистить context-related state.

После reset:

```text
messages → clear
facts → clear
branches → clear
active branch → reset
```

и создать новую чистую:

```text
Main
```

Также очистить связанную статистику согласно существующей логике.

После restart старые branches/facts не должны вернуться.

---

# 43. Strategy-specific state

Разделять state разных стратегий.

Пример:

```text
SLIDING_WINDOW
→ использует обычные messages

STICKY_FACTS
→ messages + facts

BRANCHING
→ branch graph + branch messages
```

Не делать один гигантский mutable object с несвязанными nullable-полями, если можно сохранить ясные repository abstractions.

---

# 44. Конфигурация

Пример:

```yaml
context:
  strategies:

    sliding-window:
      size: 10

    sticky-facts:
      window-size: 10

      extractor:
        provider: OPENAI
        model: gpt-5-nano
        system-prompt: >
          Extract and update only durable facts from
          the user's message. Keep goals, constraints,
          preferences, decisions and agreements.
          Do not invent information.
```

Branching дополнительных N-настроек не требует.

---

# 45. StrategyResolver

Добавить механизм выбора стратегии.

Например:

```kotlin
interface ContextStrategyResolver {

    fun resolve(
        type: ContextStrategyType
    ): ContextStrategy
}
```

Можно использовать Spring collection/map injection.

Предпочтительно не писать:

```kotlin
when (strategy) {
    SLIDING_WINDOW -> ...
    STICKY_FACTS -> ...
    BRANCHING -> ...
}
```

в нескольких разных местах приложения.

---

# 46. Основной Agent

Agent должен оставаться orchestrator.

Пример conceptual flow:

```text
User Request
     ↓
Agent
     ↓
Selected ContextStrategy
     ↓
build effective context
     ↓
LlmClient
     ↓
LLM
     ↓
save response/state
```

Agent не должен сам реализовывать алгоритмы всех стратегий.

---

# 47. Sticky Facts flow

Пример:

```text
New User Message
      ↓
FactsExtractor
      ↓
update persistent facts
      ↓
StickyFactsStrategy
      ↓
facts + last N messages
      ↓
Main LLM
```

Допускается extraction после основного ответа, если это лучше согласуется с существующей transactional logic.

Но следующий пользовательский запрос уже должен видеть обновлённые facts.

---

# 48. Sliding Window test

При:

```text
N = 4
```

и истории:

```text
M1 M2 M3 M4 M5 M6
```

проверить, что основной LLM получает:

```text
M3 M4 M5 M6
```

и не получает:

```text
M1 M2
```

---

# 49. Sticky Facts test

Existing facts:

```text
language = Kotlin
database = SQLite
```

Последние сообщения:

```text
M8 M9 M10
```

Проверить, что основной context содержит:

```text
facts
+
M8 M9 M10
```

---

# 50. Facts update test

Existing:

```text
language = Kotlin
```

Новое user message:

```text
Теперь проект пишем на Java.
```

Extractor возвращает update.

Проверить, что persistence содержит:

```text
language = Java
```

а старый value не остаётся отдельным дубликатом.

---

# 51. Facts persistence test

Сохранить facts.

Сымитировать restart.

Проверить, что facts восстановлены и передаются следующему LLM request.

---

# 52. Branch creation test

Main:

```text
M1 M2 M3 M4
```

Создать Branch 1.

Проверить:

```text
Branch 1 effective history =
M1 M2 M3 M4
```

---

# 53. Branch divergence test

После branch:

```text
Main:
M1 M2 M3 M4 M5

Branch 1:
M1 M2 M3 M4 B1
```

Проверить:

- Main не содержит B1;
- Branch 1 не содержит M5.

---

# 54. Branch switching test

Переключиться:

```text
Main → Branch 1 → Main
```

Проверить, что:

- history соответствует выбранной ветке;
- новые сообщения пишутся в правильную branch;
- никакие сообщения не переносятся между ветками случайно.

---

# 55. Branch restart test

Создать несколько branches.

Перезапустить application state.

Проверить восстановление:

```text
branches
checkpoint relationships
active branch
branch history
```

---

# 56. Strategy switching test

Создать Conversation.

Использовать:

```text
SLIDING_WINDOW
```

затем:

```text
STICKY_FACTS
```

затем:

```text
BRANCHING
```

Проверить, что:

- стратегия действительно меняет effective context;
- исходная история не удаляется;
- strategy-specific state сохраняется.

---

# 57. Reset test

После создания:

```text
messages
facts
branches
```

выполнить reset.

Проверить:

```text
messages = empty
facts = empty
branches = only new Main
```

---

# 58. UI

Пример итогового интерфейса:

```text
┌───────────────────────────────────────────┐
│ AI Agent                                  │
│                                           │
│ Provider: [ OpenRouter ▼ ]                │
│ Model:    [ ...                       ]   │
│                                           │
│ Context strategy:                         │
│ [ Sticky Facts ▼ ]                        │
│                                           │
│ Branch: [ Main ▼ ]  [ Создать ветку ]     │
│          ↑                                │
│ показывать этот блок только               │
│ для Branching                             │
├───────────────────────────────────────────┤
│                                           │
│ Chat                                      │
│                                           │
├───────────────────────────────────────────┤
│ [Введите сообщение...]                    │
│                                           │
│ [Отправить] [Сбросить чат]                │
├───────────────────────────────────────────┤
│ Statistics...                             │
└───────────────────────────────────────────┘
```

При:

```text
SLIDING_WINDOW
```

branch controls скрыты.

При:

```text
STICKY_FACTS
```

branch controls скрыты.

При:

```text
BRANCHING
```

показываются:

```text
Branch selector
Создать ветку
```

---

# 59. Не отображать Facts как сообщения

Facts являются внутренней памятью Agent.

Не добавлять их как обычные сообщения пользователя или assistant в UI.

Допускается добавить небольшой debug/info panel с facts, но это необязательное улучшение.

---

# 60. README

Обновить README.

Добавить раздел:

```text
Context Strategies
```

Описать:

### Sliding Window

```text
последние N сообщений
```

### Sticky Facts

```text
persistent facts + последние N сообщений
```

### Branching

```text
независимые ветки одного разговора
```

Добавить пример configuration.

---

# 61. Архитектурная схема

README должен отражать примерно:

```text
                        ChatAgent
                           │
                           ▼
                  ContextStrategyResolver
                           │
         ┌─────────────────┼─────────────────┐
         │                 │                 │
         ▼                 ▼                 ▼
 SlidingWindow        StickyFacts        Branching
    Strategy            Strategy           Strategy
                           │                 │
                           ▼                 ▼
                     FactsRepository     BranchRepository
                           │                 │
                           ▼                 ▼
                         SQLite            SQLite

                         ChatAgent
                            │
                            ▼
                         LlmClient
                            │
                 ┌──────────┴──────────┐
                 ▼                     ▼
              OpenAI               OpenRouter
```

---

# 62. Критерии готовности

Задача считается выполненной, если:

- в UI есть selector context strategy;
- реализовано минимум три стратегии;
- одновременно активна одна стратегия;
- Sliding Window использует последние N сообщений;
- N Sliding Window задаётся через configuration;
- Sticky Facts использует persistent key-value facts;
- facts обновляются после сообщений пользователя;
- Sticky Facts использует facts + последние N сообщений;
- N Sticky Facts задаётся через configuration;
- facts сохраняются в SQLite;
- facts переживают restart;
- Branching позволяет создать branch;
- branch создаётся от текущего checkpoint;
- каждая branch имеет независимую историю после divergence;
- можно переключаться между branches;
- выбранная branch продолжает собственный диалог;
- branches сохраняются в SQLite;
- branches переживают restart;
- switching strategy не удаляет Conversation;
- Rolling Summary не используется этими тремя стратегиями;
- reset очищает messages, facts и branches;
- OpenAI/OpenRouter продолжают работать;
- plugins продолжают работать;
- token statistics продолжают работать;
- unit tests проходят;
- integration tests проходят;
- проект собирается.

---

# 63. Финальная проверка OMP

После реализации:

1. сначала изучить существующую архитектуру;
2. не создавать второй параллельный Agent;
3. встроить стратегии через отдельную abstraction;
4. выполнить build;
5. выполнить unit tests;
6. выполнить integration tests;
7. исправить все найденные ошибки;
8. проверить Sliding Window;
9. проверить Sticky Facts;
10. проверить persistence facts;
11. проверить создание двух branches;
12. проверить независимый диалог в каждой branch;
13. проверить переключение branches;
14. проверить restart;
15. проверить reset;
16. убедиться, что старый Rolling Summary не смешивается с новыми стратегиями.

В финальном отчёте показать на одном небольшом примере:

```text
12 сообщений
N = 4
```

какой context получит модель для:

```text
SLIDING_WINDOW
```

и для:

```text
STICKY_FACTS
```

Также показать пример:

```text
Main
├── Branch 1
└── Branch 2
```

и перечислить effective history каждой ветки.