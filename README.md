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
- `SqliteConversationRepository` сохраняет только завершённые пары `USER + ASSISTANT` и восстанавливает их после перезапуска.
- При ошибке LLM память и SQLite не изменяются. При ошибке сохранения in-memory состояние откатывается.

Текущая учебная версия обслуживает один общий активный диалог. История переживает перезапуск приложения.

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

## Persistent context

При первом запуске приложение создаёт родительскую директорию, SQLite-файл и таблицу `chat_message`. Каждое сообщение хранится отдельной строкой с `id`, `role`, `content` и `created_at`. System prompt в базу не записывается и добавляется Agent при каждом LLM-запросе.

Чтобы проверить восстановление:

1. запустить приложение и выполнить несколько успешных обменов;
2. остановить и снова запустить приложение с тем же `AGENT_DB_PATH`;
3. открыть UI — сохранённые сообщения будут загружены через `GET /api/chat/history`;
4. задать вопрос, зависящий от предыдущего контекста.

Для отдельной базы:

```bash
export AGENT_DB_PATH=\"./data/local-agent.db\"
```

Очищать SQLite-файл вручную не требуется: кнопка `Сбросить чат` удаляет историю и из памяти, и из persistent storage.

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

Возвращает сохранённые сообщения `USER` и `ASSISTANT` в порядке диалога. UI вызывает endpoint при открытии страницы.

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
  "inputTokens": 120,
  "outputTokens": 84,
  "totalTokens": 204,
  "responseTimeMs": 1420
}
```

Token usage может быть `null`, если выбранный provider не вернул соответствующее значение. Статистика UI показывает provider, фактическую модель ответа, токены и backend response time.

### Сброс диалога

```http
POST /api/chat/reset
```

Ответ: `204 No Content`. Reset очищает in-memory `Conversation` и SQLite; после перезапуска старый чат не возвращается. Следующий запрос содержит только system prompt и новое сообщение пользователя.

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

Unit-тесты проверяют выбор OpenAI/OpenRouter, forwarding модели, смену provider внутри общей Conversation, статистику, восстановление, reset и rollback. HTTP client tests без реальных API keys проверяют отдельные Authorization headers, endpoints, request body, messages и usage mapping обоих providers. SQLite integration-тест использует отдельный временный файл и проверяет `save → load` и persisted reset.
