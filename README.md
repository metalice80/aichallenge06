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
                                ├─► ContextStrategyResolver
                                │     ├─► SlidingWindowContextStrategy
                                │     ├─► StickyFactsContextStrategy ─► FactsExtractor
                                │     └─► BranchingContextStrategy ─► branch graph
                                │
                                └─► repositories ─► SQLite
```

- `ChatController` отвечает за REST-контракт, validation и вызов provider-neutral `Agent`.
- `ChatAgent` не знает URL, API keys или HTTP headers providers. Для каждого запроса он независимо выбирает `LlmClient` и `ContextStrategy`.
- `DefaultLlmClientResolver` выбирает OpenAI/OpenRouter client по `LlmProvider`; `DefaultContextStrategyResolver` выбирает одну из трёх context strategies по `ContextStrategyType`.
- Strategy возвращает только сообщения контекста для основного LLM-вызова. Новый `USER` message и общий system prompt добавляет `ChatAgent`.
- Полная хронологическая история и provider-reported token usage сохраняются независимо от выбранной strategy. Переключение strategy не удаляет facts, branches, messages или statistics.
- `SqliteConversationRepository` атомарно сохраняет завершённые пары `USER + ASSISTANT` и usage каждого успешного основного LLM-вызова.
- При ошибке основного LLM память и SQLite не изменяются. При ошибке сохранения in-memory messages и накопленная статистика откатываются.

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

### Rolling Context Compression

Существующая реализация Rolling Summary, её configuration properties и persisted `conversation_summary` сохранены отдельно. Она намеренно не зарегистрирована как одна из трёх selectable strategies и не добавляется к их LLM contexts: Sliding Window, Sticky Facts и Branching никогда не смешиваются с summary/cursor.

## Persistent context

При первом запуске приложение создаёт родительскую директорию, SQLite-файл и шесть таблиц:

- `chat_message` — полная хронологическая история сообщений;
- `llm_request_usage` — usage каждого успешного основного запроса;
- `conversation_summary` — сохранённое состояние отдельной legacy Rolling Summary реализации;
- `conversation_fact` — Sticky Facts с уникальным key, value и временем обновления;
- `conversation_branch` — branch graph, parent, checkpoint и active marker;
- `branch_message` — только собственные сообщения каждой ветки и их порядок.

System prompt в базу не записывается и добавляется Agent при каждом LLM-запросе. Накопленные totals вычисляются как суммы persisted usage: смена provider, model, strategy или branch не сбрасывает и не дублирует статистику.

Чтобы проверить восстановление:

1. запустить приложение и выполнить несколько успешных обменов;
2. остановить и снова запустить приложение с тем же `AGENT_DB_PATH`;
3. открыть UI — сохранённые сообщения и накопленная статистика будут загружены через `GET /api/chat/state`;
4. задать вопрос, зависящий от предыдущего контекста: токены нового запроса добавятся к восстановленным totals.

Для отдельной базы:

```bash
export AGENT_DB_PATH=\"./data/local-agent.db\"
```

Очищать SQLite-файл вручную не требуется: кнопка `Сбросить чат` удаляет messages, usage, rolling summary, Sticky Facts и весь branch graph, затем создаёт пустую активную ветку `Main`.

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

Возвращает видимые сообщения и `conversationUsage`. UI использует этот endpoint при открытии страницы, поэтому накопленные input/output/total tokens восстанавливаются после restart. Статистика последнего запроса после restart не восстанавливается.

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

Ответ: `204 No Content`. Reset очищает in-memory `Conversation`, все persisted messages/usage/summary/facts/branches и создаёт свежую пустую `Main`; после restart старое состояние не возвращается.

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

Unit-тесты проверяют выбор OpenAI/OpenRouter, plugins, provider-reported usage, resolver, точные Sliding/Sticky/Branching contexts, facts extractor и независимые provider/model. SQLite integration tests проверяют restart, upsert/delete facts, parent checkpoints, две расходящиеся ветки без копирования inherited messages, token usage и полный reset.
