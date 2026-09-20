# AI Agent

Учебное web-приложение на Kotlin и Spring Boot: AI-агент с OpenAI/OpenRouter, общим контекстом диалога, SQLite persistence и статистикой запросов.

## Стек

- Java 21
- Kotlin 2.3.21
- Spring Boot 4.1.1
- Gradle 9.7.1, Kotlin DSL
- Spring MVC, Spring JDBC, Jackson 3, Bean Validation
- SQLite
- JUnit 5, MockK
- HTML, CSS, vanilla JavaScript

Spring AI, LangChain и LangChain4j не используются.

## Архитектура

```text
Browser ─► ChatController ─► ChatAgent ─► LlmClientResolver ─► OpenAI/OpenRouter
                                │
                                ├─► TaskService ─► TaskRepository
                                ├─► MemoryService ─► MemoryExtractor ─► LlmClientResolver
                                ├─► ContextStrategyResolver
                                │     ├─► SlidingWindowContextStrategy
                                │     ├─► StickyFactsContextStrategy ─► FactsExtractor
                                │     └─► BranchingContextStrategy ─► branch graph
                                │
                                └─► repositories ─► SQLite
```

- `ChatController` отвечает за REST-контракт, validation и вызов provider-neutral `Agent`.
- `ChatAgent` не знает URL, API keys или HTTP headers providers. Для каждого запроса он независимо выбирает `LlmClient` и `ContextStrategy`.
- Persistent `Task` определяет текущие `Conversation`, branch graph, token usage, Sticky Facts и Working Memory. Long-Term Memory глобальна и не переключается вместе с Task.
- `MemoryExtractor` использует тот же `LlmClientResolver`, но отдельные provider/model/system prompt. Один structured result явно разделяет изменения `WORKING` и `LONG_TERM`.
- Context strategy формирует только effective Short-Term. Main request строится в порядке `system prompt → Long-Term → Working → effective Short-Term → current USER`; конфликт разрешается как `current USER > Working > Long-Term`.
- `SqliteConversationRepository` атомарно сохраняет завершённые пары `USER + ASSISTANT` и usage в scope активной Task.
- Ошибка Memory Extractor не отменяет успешный основной ответ и не изменяет существующую memory. При ошибке сохранения Conversation in-memory messages и накопленная статистика откатываются.

## Настройка

OpenAI:

```bash
export OPENAI_API_KEY="sk-..."
export OPENAI_MODEL="gpt-4.1-mini"
```

OpenRouter:

```bash
export OPENROUTER_API_KEY="..."
export OPENROUTER_MODEL="openai/gpt-4o-mini"
```

Все параметры:

| Переменная | Значение по умолчанию | Назначение |
|---|---|---|
| `OPENAI_API_KEY` | пусто | OpenAI API key |
| `OPENAI_MODEL` | `gpt-4.1-mini` | модель OpenAI по умолчанию |
| `OPENAI_BASE_URL` | `https://api.openai.com` | базовый URL OpenAI |
| `OPENROUTER_API_KEY` | пусто | OpenRouter API key |
| `OPENROUTER_MODEL` | `openai/gpt-4o-mini` | модель OpenRouter по умолчанию |
| `OPENROUTER_BASE_URL` | `https://openrouter.ai/api/v1` | базовый URL OpenRouter |
| `LLM_CONNECT_TIMEOUT` | `10s` | timeout соединения обоих clients |
| `LLM_REQUEST_TIMEOUT` | `60s` | timeout запроса обоих clients |
| `CONTEXT_SLIDING_WINDOW_SIZE` | `2` | количество existing messages в Sliding Window |
| `CONTEXT_STICKY_FACTS_WINDOW_SIZE` | `20` | количество recent messages рядом с Sticky Facts |
| `CONTEXT_FACTS_PROVIDER` | `OPENAI` | отдельный provider facts extractor |
| `CONTEXT_FACTS_MODEL` | `gpt-4o-mini` | отдельная model facts extractor |
| `MEMORY_ENABLED` | `true` | включает extraction и добавление memory layers в main context |
| `MEMORY_EXTRACTOR_PROVIDER` | `OPENAI` | provider отдельного Memory Extractor |
| `MEMORY_EXTRACTOR_MODEL` | `gpt-4o-mini` | model отдельного Memory Extractor |
| `AGENT_DB_PATH` | `./data/agent.db` | путь к SQLite database |

API keys не включаются в frontend, REST responses или логи. System prompt находится в `src/main/resources/application.yml`.

### OpenRouter plugins

Plugins настраиваются только для OpenRouter в `application.yml` и передаются в JSON request body этого provider. Один отключённый plugin:

```yaml
llm:
  openrouter:
    plugins:
      - id: context-compression
        enabled: false
```

Несколько plugins:

```yaml
llm:
  openrouter:
    plugins:
      - id: context-compression
        enabled: false
      - id: web
        enabled: true
```

Порядок сохраняется. `enabled: false` передаётся provider без удаления элемента. При отсутствии настройки или при `plugins: []` поле `plugins` не добавляется в request. Plugin IDs не ограничены списком в приложении; пустой `id` останавливает запуск из-за ошибки configuration validation. OpenAI request не содержит OpenRouter plugins.

### Context strategies

UI позволяет выбрать ровно одну strategy для каждого основного запроса:

- `SLIDING_WINDOW` — последние $N$ existing messages плюс новый `USER` message. При $N=4$ и шести сохранённых сообщениях модель получает Messages 3–6 и новый вопрос.
- `STICKY_FACTS` — отдельный `SYSTEM` block с persistent facts, последние $N$ existing messages и новый `USER` message.
- `BRANCHING` — effective history активной ветки и новый `USER` message. Branch controls появляются в UI только для этой strategy.

```yaml
context:
  strategies:
    sliding-window:
      size: 2
    sticky-facts:
      window-size: 20
      extractor:
        provider: OPENAI
        model: gpt-4o-mini
        system-prompt: |-
          Проанализируй новое сообщение пользователя...
```

Оба window size валидируются при запуске и должны быть больше нуля. Main provider/model выбираются в UI. Facts extractor имеет независимые provider/model/system prompt, но выбирает client через тот же `LlmClientResolver`.

После каждого успешного сообщения со `STICKY_FACTS` extractor получает existing facts и новое пользовательское сообщение. Он возвращает:

```json
{"upsert":[{"key":"language","value":"Kotlin"}],"delete":["old-key"]}
```

Upsert заменяет value по стабильному key, delete удаляет key. Facts сохраняются в SQLite и входят уже в следующий основной запрос. Ошибка сети или невалидный JSON extractor не отменяет успешный основной ответ, не меняет существующие facts и не удаляет историю.

Branching всегда начинается с `Main`. Новая ветка получает `parentBranchId` активной ветки и `checkpointMessageCount`, равный длине effective parent history в момент создания, после чего автоматически становится активной. В SQLite дочерняя ветка хранит только собственные messages:

```text
Main:     A ─ B ─ C ─ D
                    ├─ Branch 1: E ─ F
                    └─ Branch 2: G ─ H
```

Effective history `Branch 1` равна `A…D + E…F`, а `Branch 2` — `A…D + G…H`; сообщения одной ветки не попадают в контекст другой. Parent, checkpoint, active branch и собственные messages переживают restart.

### Memory Layers и Tasks

UI позволяет создать, выбрать и завершить Task. Завершённая Task остаётся в SQLite вместе с Conversation, Working Memory и branches, но становится read-only.

- `SHORT_TERM` — существующая `Conversation` активной Task; selected Context Strategy определяет только её effective часть.
- `WORKING` — task-scoped key-value state: цель, ограничения, технологии и решения текущей Task.
- `LONG_TERM` — глобальные key-value предпочтения и знания между Tasks.

```yaml
memory:
  enabled: true
  extractor:
    provider: OPENAI
    model: gpt-4o-mini
    system-prompt: |-
      Проанализируй новое сообщение пользователя...
```

Extractor возвращает один JSON:

```json
{
  "working": {
    "upsert": [{"key": "database", "value": "PostgreSQL"}],
    "delete": []
  },
  "longTerm": {
    "upsert": [{"key": "preferred_code_language", "value": "Kotlin"}],
    "delete": []
  }
}
```

Upsert заменяет значение по стабильному key; delete удаляет key. Изменения двух слоёв применяются одной SQLite transaction. Memory Inspector показывает effective Short-Term, Working и Long-Term; `Last Memory Update` показывает added/updated/deleted; `Effective Context` показывает логические секции последнего main request. Похожие JSON parsing и `LlmClientResolver` infrastructure разделяются со Sticky Facts. Секреты в Effective Context маскируются.

### Rolling Context Compression

Существующая реализация Rolling Summary и её configuration properties сохранены отдельно; persisted state теперь task-scoped в `task_conversation_summary`. Rolling Summary намеренно не зарегистрирована как одна из трёх selectable strategies и не добавляется к их LLM contexts: Sliding Window, Sticky Facts и Branching никогда не смешиваются с summary/cursor.

## Persistent context

SQLite schema содержит:

- `agent_task`, `active_task_state` — Task lifecycle и выбранная Task;
- `chat_message`, `llm_request_usage` — task-scoped Conversation и token usage;
- `task_conversation_summary` — task-scoped Rolling Summary state;
- `task_conversation_fact` — task-scoped Sticky Facts;
- `conversation_branch`, `branch_message` — task-scoped branch graph и собственные branch messages;
- `working_memory` — key-value entries с составным ключом `(task_id, key)`;
- `long_term_memory` — глобальные key-value entries;
- `last_memory_update` — последняя классификация сообщения по Task;
- `effective_context` — sanitized logical snapshot последнего main request по Task.

Legacy `conversation_summary` и `conversation_fact` сохраняются только для безопасной migration существующей базы. При первом запуске их данные копируются в task-scoped таблицы default Task. Если legacy summary cursor превышает число мигрированных сообщений, повреждённый summary удаляется, а Conversation и usage сохраняются.

System prompt в базу Conversation не записывается и добавляется Agent при каждом LLM-запросе. Накопленные totals вычисляются по Task как суммы persisted usage.

Чтобы проверить восстановление:

1. создать две Tasks и выполнить несколько успешных обменов;
2. остановить и снова запустить приложение с тем же `AGENT_DB_PATH`;
3. открыть UI — выбранная Task, её Conversation, Working Memory, branches и token usage восстановятся;
4. переключить Task — Long-Term останется общей, а Conversation и Working Memory сменятся.

Для отдельной базы:

```bash
export AGENT_DB_PATH="./data/local-agent.db"
```

`Сбросить чат` очищает только Short-Term state активной Task: messages, usage, rolling summary, Sticky Facts и её branch graph. Working и Long-Term не удаляются. Для них в UI есть отдельные действия; очистка Long-Term требует confirmation.

## Запуск

```bash
./gradlew bootRun
```

После запуска открыть <http://localhost:8080>.

Без API keys приложение запускается: UI, история и reset доступны, а запрос к выбранному provider вернёт понятную ошибку его конфигурации.

## REST API

### Доступные providers

```http
GET /api/chat/providers
```

Возвращает `OPENAI`, `OPENROUTER` и настроенные default models. UI использует endpoint для selector и позволяет вручную изменить model id.

### Tasks и Memory

```http
GET  /api/chat/tasks
POST /api/chat/tasks
POST /api/chat/tasks/{taskId}/activate
POST /api/chat/tasks/{taskId}/complete

GET  /api/chat/memory?contextStrategy=SLIDING_WINDOW
POST /api/chat/memory/working/clear
POST /api/chat/memory/long-term/clear
```

`GET /memory` возвращает три слоя, Last Memory Update и sanitized Effective Context. Working clear действует только на выбранную Task; Long-Term clear глобален.

### Context strategies и branches

```http
GET /api/chat/context-strategies
GET /api/chat/branches
POST /api/chat/branches
POST /api/chat/branches/{branchId}/activate
```

Первый endpoint возвращает `SLIDING_WINDOW`, `STICKY_FACTS`, `BRANCHING`. Branch list содержит `id`, `name`, `parentBranchId`, `checkpointMessageCount`, `active`. Создание использует checkpoint текущей активной ветки и возвращает новый active child. Activation сохраняет active marker и возвращает effective branch messages вместе с общей token usage.

### История диалога

```http
GET /api/chat/history
```

Возвращает сохранённые сообщения `USER` и `ASSISTANT` в порядке диалога. Endpoint сохранён для клиентов, которым нужна только история.

### Состояние диалога

```http
GET /api/chat/state
```

Возвращает видимые сообщения и `conversationUsage` выбранной Task. UI использует endpoint при открытии страницы и после переключения Task.

### Отправка сообщения

```http
POST /api/chat
Content-Type: application/json

{"message":"Что такое JVM?","provider":"OPENAI","model":"gpt-4.1-mini","contextStrategy":"SLIDING_WINDOW"}
```

OpenRouter использует тот же контракт с provider `OPENROUTER` и model id формата `provider/model`, например `anthropic/claude-...`.

Успешный ответ:

```json
{
  "provider": "OPENAI",
  "content": "JVM — это виртуальная машина Java...",
  "model": "gpt-4.1-mini",
  "currentUsage": {
    "inputTokens": 120,
    "outputTokens": 84,
    "totalTokens": 204
  },
  "conversationUsage": {
    "inputTokens": 620,
    "outputTokens": 184,
    "totalTokens": 804
  },
  "responseTimeMs": 1420
}
```

`currentUsage` относится только к последнему успешному вызову. `conversationUsage` — сумма provider-reported usage всех успешных вызовов с момента последнего reset, включая повторно отправленные модели токены истории. Значения current usage могут быть `null`, если provider их не вернул; приложение не выполняет приблизительный локальный подсчёт.

### Сброс диалога

```http
POST /api/chat/reset
```

Ответ: `204 No Content`. Reset очищает Short-Term state только выбранной Task и не затрагивает Working/Long-Term или другие Tasks.

## Ошибки

API возвращает JSON вида:

```json
{"message":"Не удалось получить ответ от OpenRouter. Попробуйте ещё раз."}
```

Обрабатываются отсутствующие provider-specific API keys, authentication, rate limit, timeout, недоступность provider, invalid model, неверный OpenRouter model id и некорректный API response. Техническая причина логируется только на backend; stack trace и API keys клиенту не возвращаются.

## Тесты и сборка

```bash
./gradlew test
./gradlew build
```

Unit tests проверяют providers/plugins, token usage, Sliding/Sticky/Branching contexts, оба LLM extractor и Effective Context ordering/redaction. SQLite integration tests проверяют Task switch/restart, Working isolation, global Long-Term, stable-key upsert/delete, независимые clears/reset и task-scoped branches/conversations.
