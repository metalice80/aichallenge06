# AI Agent

Учебное web-приложение на Kotlin и Spring Boot: простой AI-агент с контекстом диалога, прямой интеграцией с OpenAI API, статистикой токенов и времени ответа.

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
Browser (HTML/CSS/JS)
        │ HTTP / JSON
        ▼
ChatController
        │
        ▼
Agent / ChatAgent
        ├── LlmClient / OpenAiClient ──► OpenAI Chat Completions API
        └── ConversationRepository / SqliteConversationRepository ──► SQLite
```

- `ChatController` отвечает только за REST-контракт, validation и вызов `Agent`.
- `ChatAgent` добавляет system prompt, формирует контекст, измеряет LLM-вызов и сохраняет только завершённые пары `USER + ASSISTANT`.
- `Conversation` содержит восстановленную историю одного активного чата.
- `ConversationRepository` изолирует Agent от деталей persistence.
- `SqliteConversationRepository` создаёт схему, сохраняет сообщения и восстанавливает их в порядке добавления.
- `OpenAiClient` выполняет HTTP-запрос, аутентификацию, JSON-преобразование и переводит OpenAI DTO во внутренние модели.
- При ошибке LLM память и SQLite не изменяются. При ошибке сохранения in-memory состояние откатывается.

Текущая учебная версия обслуживает один общий активный диалог. История переживает перезапуск приложения.

## Настройка

API key передаётся только через переменную окружения и не включается в frontend или исходный код:

```bash
export OPENAI_API_KEY="sk-..."
```

Доступные параметры:

| Переменная | Значение по умолчанию | Назначение |
|---|---|---|
| `OPENAI_API_KEY` | пусто | OpenAI API key |
| `OPENAI_MODEL` | `gpt-4.1-mini` | модель Chat Completions API |
| `OPENAI_BASE_URL` | `https://api.openai.com` | базовый URL API |
| `OPENAI_CONNECT_TIMEOUT` | `10s` | timeout соединения |
| `OPENAI_REQUEST_TIMEOUT` | `60s` | timeout запроса |
| `AGENT_DB_PATH` | `./data/agent.db` | путь к SQLite database |

System prompt находится в `src/main/resources/application.yml` и меняется без правки `ChatAgent` или `OpenAiClient`.

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

Без `OPENAI_API_KEY` приложение запускается, но запрос чата вернёт понятную ошибку конфигурации. Это позволяет открыть UI и проверить reset без секрета.

## REST API

### История диалога

```http
GET /api/chat/history
```

Возвращает сохранённые сообщения `USER` и `ASSISTANT` в порядке диалога. UI вызывает endpoint при открытии страницы.

### Отправка сообщения

```http
POST /api/chat
Content-Type: application/json

{"message":"Что такое JVM?"}
```

Успешный ответ:

```json
{
  "content": "JVM — это виртуальная машина Java...",
  "model": "gpt-4.1-mini",
  "inputTokens": 120,
  "outputTokens": 84,
  "totalTokens": 204,
  "responseTimeMs": 1420
}
```

Token usage может быть `null`, если OpenAI не вернул соответствующее значение.

### Сброс диалога

```http
POST /api/chat/reset
```

Ответ: `204 No Content`. Reset очищает in-memory `Conversation` и SQLite; после перезапуска старый чат не возвращается. Следующий запрос содержит только system prompt и новое сообщение пользователя.

## Ошибки

API возвращает JSON вида:

```json
{"message":"Не удалось получить ответ от модели. Попробуйте ещё раз."}
```

Обрабатываются пустые и слишком длинные сообщения, отсутствующий ключ, ответы OpenAI `401`, `429`, `5xx`, timeout, network error и некорректный JSON/response. Техническая причина логируется только на backend; stack trace и API key клиенту не возвращаются.

## Тесты и сборка

```bash
./gradlew test
./gradlew build
```

Unit-тесты не обращаются к OpenAI. `LlmClient` мокируется при проверке system prompt, восстановленной истории, сохранения, reset и rollback после ошибки. Integration-тест использует отдельный временный SQLite-файл и проверяет `save → load`, повторное создание repository и persisted reset. Отдельно проверяются HTTP/status/JSON-преобразования `OpenAiClient` и делегирование controller.
