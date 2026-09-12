# AI Agent

Учебное web-приложение на Kotlin и Spring Boot: простой AI-агент с контекстом диалога, прямой интеграцией с OpenAI API, статистикой токенов и времени ответа.

## Стек

- Java 21
- Kotlin 2.3.21
- Spring Boot 4.1.1
- Gradle 9.7.1, Kotlin DSL
- Spring MVC, Jackson 3, Bean Validation
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
Agent ──► ChatAgent ──► Conversation
        │
        ▼
LlmClient ──► OpenAiClient ──► OpenAI Chat Completions API
```

- `ChatController` отвечает только за REST-контракт, validation и вызов `Agent`.
- `ChatAgent` добавляет system prompt, формирует контекст, измеряет LLM-вызов и атомарно сохраняет успешный обмен.
- `Conversation` хранит историю одного активного чата в памяти.
- `OpenAiClient` выполняет HTTP-запрос, аутентификацию, JSON-преобразование и переводит OpenAI DTO во внутренние модели.
- При ошибке LLM пользовательское сообщение не сохраняется. Повтор отправляет тот же непротиворечивый контекст.

Текущая учебная версия обслуживает один общий активный диалог. Перезапуск приложения также очищает историю.

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

System prompt находится в `src/main/resources/application.yml` и меняется без правки `ChatAgent` или `OpenAiClient`.

## Запуск

```bash
./gradlew bootRun
```

После запуска открыть <http://localhost:8080>.

Без `OPENAI_API_KEY` приложение запускается, но запрос чата вернёт понятную ошибку конфигурации. Это позволяет открыть UI и проверить reset без секрета.

## REST API

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

Ответ: `204 No Content`. Следующий запрос содержит только system prompt и новое сообщение пользователя.

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

Unit-тесты не обращаются к OpenAI. `LlmClient` мокируется при проверке system prompt, истории, reset и rollback после ошибки. Отдельно проверяются HTTP/status/JSON-преобразования `OpenAiClient` и делегирование controller.
