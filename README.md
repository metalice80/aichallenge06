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
                         ┌─► OpenAiLlmClient ─────► OpenAI
Browser ─► Controller ─► Agent ─► LlmClientResolver
                         └─► OpenRouterLlmClient ─► OpenRouter
              │
              └─► ConversationRepository ─► SQLite
```

- `ChatController` отвечает за REST-контракт, validation и вызов provider-neutral `Agent`.
- `ChatAgent` зависит только от `LlmClientResolver`, `Conversation` и `ConversationRepository`; URL, API keys и HTTP headers providers ему неизвестны.
- `DefaultLlmClientResolver` выбирает реализацию общего `LlmClient` по `LlmProvider`.
- `OpenAiLlmClient` и `OpenRouterLlmClient` — отдельные infrastructure clients для OpenAI-compatible Chat Completions API.
- `Conversation` остаётся provider-neutral: provider и model можно менять между сообщениями без потери контекста.
- `SqliteConversationRepository` атомарно сохраняет завершённые пары `USER + ASSISTANT` и usage каждого успешного LLM-вызова.
- При ошибке LLM память и SQLite не изменяются. При ошибке сохранения in-memory messages и накопленная статистика откатываются.

Текущая учебная версия обслуживает один общий активный диалог. История и накопленная статистика токенов переживают перезапуск приложения.

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

### Rolling Context Compression

Rolling Summary ограничивает контекст основной модели, не удаляя исходную историю. До первого порога основная LLM получает все сообщения. После compression она получает последнее summary как дополнительное `SYSTEM`-сообщение и только сообщения после persisted cursor. UI и SQLite по-прежнему содержат полную историю.

```yaml
context:
  compression:
    enabled: true
    summarize-after-messages: 20
    summarize-every-messages: 10
    provider: OPENAI
    model: gpt-5-nano
    system-prompt: |-
      Сожми предыдущий диалог в компактное самостоятельное summary.
      Сохрани важные факты, решения, ограничения и открытые вопросы.
```

При 20 сообщениях первые 10 входят в initial summary, а последние 10 остаются без изменений. После накопления следующих 10 сообщений summarizer получает только existing summary и Messages 11–20; результат полностью заменяет предыдущее summary. Main provider/model выбираются пользователем, summary provider/model задаются этой конфигурацией и используют тот же `LlmClientResolver`.

Summary и `summarized_message_count` сохраняются в SQLite и восстанавливаются после restart. Ошибка summarizer не удаляет историю и не продвигает cursor. `enabled: false` сохраняет прежнее поведение с полной Conversation без вызова summarizer.

## Persistent context

При первом запуске приложение создаёт родительскую директорию, SQLite-файл и три таблицы:

- `chat_message` — полная история сообщений с `id`, `role`, `content` и `created_at`;
- `llm_request_usage` — usage каждого успешного пользовательского запроса с provider, model, input/output/total tokens, response time и timestamp;
- `conversation_summary` — последнее rolling summary и `summarized_message_count`, атомарно определяющий compression cursor.

System prompt в базу не записывается и добавляется Agent при каждом LLM-запросе. Накопленные totals вычисляются как суммы persisted usage, поэтому смена provider или model не сбрасывает статистику.

Чтобы проверить восстановление:

1. запустить приложение и выполнить несколько успешных обменов;
2. остановить и снова запустить приложение с тем же `AGENT_DB_PATH`;
3. открыть UI — сохранённые сообщения и накопленная статистика будут загружены через `GET /api/chat/state`;
4. задать вопрос, зависящий от предыдущего контекста: токены нового запроса добавятся к восстановленным totals.

Для отдельной базы:

```bash
export AGENT_DB_PATH=\"./data/local-agent.db\"
```

Очищать SQLite-файл вручную не требуется: кнопка `Сбросить чат` удаляет сообщения, usage, rolling summary и compression cursor из памяти и persistent storage.

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

{"message":"Что такое JVM?","provider":"OPENAI","model":"gpt-4.1-mini"}
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

Ответ: `204 No Content`. Reset очищает in-memory `Conversation`, `chat_message` и `llm_request_usage`; после перезапуска старые messages и totals не возвращаются. Следующий запрос начинает новый контекст и новую статистику с нуля.

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

Unit-тесты проверяют выбор OpenAI/OpenRouter, plugins, provider-reported usage, rolling thresholds, initial/updated summary, fallback после ошибок и независимые main/summary provider и model. HTTP client tests без реальных API keys проверяют Authorization headers, endpoints, request body, messages и usage mapping обоих providers. SQLite integration tests проверяют restart, per-request usage, backward-compatible schema, summary/cursor update и полный persisted reset.
