# Добавление поддержки OpenAI и OpenRouter

## Цель

Доработать существующего AI-агента так, чтобы он мог работать не только напрямую с OpenAI API, но и через OpenRouter.

Пользователь должен иметь возможность выбрать:

- AI provider;
- модель.

Поддерживаемые providers:

```text
OPENAI
OPENROUTER
```

Существующий функционал должен сохраниться:

- multi-turn chat;
- сохранение контекста;
- восстановление истории после перезапуска;
- reset;
- статистика токенов;
- статистика времени ответа;
- web UI.

---

# 1. Архитектурное требование

Agent не должен зависеть от конкретного AI provider.

Неправильно:

```text
ChatAgent
   ↓
OpenAiClient
```

Необходимо перейти к:

```text
                    ┌───────────────┐
                    │   ChatAgent   │
                    └───────┬───────┘
                            │
                            ▼
                    ┌───────────────┐
                    │   LlmClient   │
                    └───────┬───────┘
                            │
                  provider selection
                            │
             ┌──────────────┴──────────────┐
             ▼                             ▼
    ┌─────────────────┐           ┌──────────────────┐
    │ OpenAiLlmClient │           │OpenRouterClient  │
    └────────┬────────┘           └────────┬─────────┘
             │                             │
             ▼                             ▼
        OpenAI API                   OpenRouter API
```

Agent должен работать только с внутренней abstraction:

```kotlin
interface LlmClient {
    fun chat(request: LlmRequest): LlmResponse
}
```

---

# 2. Provider

Добавить внутреннюю модель provider:

```kotlin
enum class LlmProvider {
    OPENAI,
    OPENROUTER
}
```

Provider должен быть частью запроса пользователя или текущих настроек чата.

Не использовать строковые сравнения вида:

```kotlin
if (provider == "openai")
```

по всему приложению.

---

# 3. Выбор provider

На web-форме добавить selector:

```text
Provider:

[ OpenAI      ▼ ]
```

Варианты:

```text
OpenAI
OpenRouter
```

Рядом добавить выбор или ввод модели:

```text
Model:

[ gpt-4.1-mini                     ]
```

Для OpenRouter значение модели должно поддерживать формат:

```text
provider/model
```

Например:

```text
openai/gpt-4o-mini
google/gemini-...
anthropic/claude-...
```

Не хардкодить список всех OpenRouter моделей.

Разрешить ввод model id вручную.

---

# 4. REST API

Доработать существующий запрос отправки сообщения.

Было:

```json
{
  "message": "Что такое JVM?"
}
```

Стало:

```json
{
  "message": "Что такое JVM?",
  "provider": "OPENAI",
  "model": "gpt-4.1-mini"
}
```

Для OpenRouter:

```json
{
  "message": "Что такое JVM?",
  "provider": "OPENROUTER",
  "model": "openai/gpt-4o-mini"
}
```

---

# 5. Agent

Agent должен принимать provider и model как параметры запроса.

Например:

```kotlin
data class AgentRequest(
    val message: String,
    val provider: LlmProvider,
    val model: String
)
```

Agent не должен самостоятельно знать:

- OpenAI base URL;
- OpenRouter base URL;
- формат API key;
- HTTP headers конкретного provider.

Agent отвечает только за:

- conversation;
- system prompt;
- формирование LlmRequest;
- выбор соответствующего LlmClient;
- сохранение истории;
- формирование AgentResponse.

---

# 6. Выбор LlmClient

Добавить отдельный механизм выбора клиента.

Например:

```kotlin
interface LlmClientResolver {

    fun resolve(provider: LlmProvider): LlmClient
}
```

или:

```kotlin
interface LlmClient {
    val provider: LlmProvider

    fun chat(request: LlmRequest): LlmResponse
}
```

Тогда resolver может работать с:

```kotlin
Map<LlmProvider, LlmClient>
```

Предпочтительно не создавать большой `when` непосредственно внутри Agent.

---

# 7. OpenAI client

Существующую OpenAI integration сохранить как отдельную реализацию:

```text
OpenAiLlmClient
```

Она использует:

```text
OPENAI_API_KEY
OPENAI_BASE_URL
```

Default base URL:

```text
https://api.openai.com
```

Модель приходит из AgentRequest.

Не хардкодить модель внутри client.

---

# 8. OpenRouter client

Добавить:

```text
OpenRouterLlmClient
```

OpenRouter предоставляет OpenAI-compatible API.

Использовать:

```text
https://openrouter.ai/api/v1
```

Для chat completion использовать OpenAI-compatible формат messages.

Авторизация:

```http
Authorization: Bearer <OPENROUTER_API_KEY>
```

Модель передавать как OpenRouter model id:

```text
provider/model
```

Например:

```text
openai/gpt-4o-mini
```

OpenRouter-specific дополнительные headers, если используются, должны оставаться инфраструктурной деталью OpenRouter client.

---

# 9. Configuration

Расширить `application.yml`.

Пример:

```yaml
llm:
  openai:
    api-key: ${OPENAI_API_KEY:}
    base-url: ${OPENAI_BASE_URL:https://api.openai.com}
    default-model: ${OPENAI_MODEL:gpt-4.1-mini}

  openrouter:
    api-key: ${OPENROUTER_API_KEY:}
    base-url: ${OPENROUTER_BASE_URL:https://openrouter.ai/api/v1}
    default-model: ${OPENROUTER_MODEL:openai/gpt-4o-mini}

  connect-timeout: ${LLM_CONNECT_TIMEOUT:10s}
  request-timeout: ${LLM_REQUEST_TIMEOUT:60s}
```

Допускается сохранить отдельные timeout для каждого provider, если это лучше соответствует существующей конфигурации.

---

# 10. API keys

Использовать разные environment variables:

```text
OPENAI_API_KEY
OPENROUTER_API_KEY
```

Ни один ключ не должен:

- храниться в Git;
- возвращаться frontend;
- попадать в REST response;
- выводиться в логах.

Если выбран `OPENAI`, но ключ отсутствует, вернуть понятную ошибку.

Если выбран `OPENROUTER`, но отсутствует `OPENROUTER_API_KEY`, вернуть понятную ошибку.

---

# 11. Общие DTO

Не использовать OpenAI-specific DTO внутри Agent.

Сохранить provider-neutral модели:

```kotlin
data class LlmRequest(
    val model: String,
    val messages: List<ChatMessage>
)
```

и:

```kotlin
data class LlmResponse(
    val content: String,
    val model: String,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val totalTokens: Int?
)
```

Каждый provider client самостоятельно преобразует эти структуры в формат своего API.

---

# 12. Статистика

Расширить AgentResponse.

Добавить provider:

```kotlin
data class AgentResponse(
    val content: String,
    val provider: LlmProvider,
    val model: String,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val totalTokens: Int?,
    val responseTimeMs: Long
)
```

На UI после ответа показывать:

```text
Provider:      OpenRouter
Model:         openai/gpt-4o-mini
Response time: 1.24 sec
Input tokens:  215
Output tokens: 74
Total tokens:  289
```

---

# 13. История разговора

Conversation должна оставаться provider-neutral.

Необходимо сохранять:

```text
role
content
createdAt
```

История не должна содержать OpenAI-specific или OpenRouter-specific DTO.

При новом запросе текущая история передаётся выбранному provider.

Это должно позволять следующий сценарий:

```text
Message #1 → OpenAI
Message #2 → OpenAI
Message #3 → OpenRouter
```

При Message #3 OpenRouter получает предыдущую историю диалога.

То есть смена provider не должна автоматически очищать Conversation.

---

# 14. Смена модели

Пользователь должен иметь возможность изменить модель между сообщениями.

Например:

```text
1. OpenAI / gpt-4.1-mini
2. OpenAI / другая модель
3. OpenRouter / anthropic/...
```

История разговора при этом сохраняется.

Agent должен передавать одной модели ответы, ранее созданные другой моделью, как обычные сообщения ASSISTANT.

---

# 15. Persistent context

Существующая SQLite persistence должна продолжить работать.

После restart:

```text
Application start
      ↓
ConversationRepository.load()
      ↓
Conversation восстановлена
```

Provider и model не должны быть необходимы для восстановления самого текста Conversation.

Если UI должен восстановить последний выбранный provider/model, допускается отдельно сохранять chat settings.

Это необязательное улучшение.

---

# 16. Reset

`Сбросить чат` по-прежнему должен:

- очищать UI;
- очищать Conversation;
- очищать SQLite history.

Provider и выбранную модель можно оставить выбранными.

Reset не обязан сбрасывать настройки provider/model.

---

# 17. UI

Добавить примерно такую панель:

```text
┌─────────────────────────────────────┐
│ AI Agent                            │
│                                     │
│ Provider: [ OpenRouter ▼ ]          │
│ Model:    [ openai/gpt-4o-mini   ]  │
│                                     │
├─────────────────────────────────────┤
│ Chat...                             │
│                                     │
├─────────────────────────────────────┤
│ [Введите сообщение...]              │
│                                     │
│ [Отправить] [Сбросить чат]          │
├─────────────────────────────────────┤
│ Last request                        │
│ Provider: OpenRouter                │
│ Model: openai/gpt-4o-mini           │
│ Time: 1.24 sec                      │
│ Input: 215                          │
│ Output: 74                          │
│ Total: 289                          │
└─────────────────────────────────────┘
```

При выборе provider можно подставлять default model:

```text
OpenAI
→ configured OPENAI_MODEL

OpenRouter
→ configured OPENROUTER_MODEL
```

Пользователь всё равно должен иметь возможность изменить model id вручную.

---

# 18. Обработка ошибок

Корректно различать минимум:

```text
OPENAI authentication error
OPENROUTER authentication error
rate limit
timeout
provider unavailable
invalid model
invalid OpenRouter model id
invalid API response
```

Frontend не должен видеть технический stack trace.

Пример:

```text
Не удалось получить ответ от OpenRouter.
Проверьте модель и настройки API.
```

Технические детали логировать на backend.

---

# 19. Unit tests

Добавить тесты минимум для следующих сценариев.

## OpenAI selection

```text
provider = OPENAI
```

Проверить, что вызывается:

```text
OpenAiLlmClient
```

и `OpenRouterLlmClient` не вызывается.

## OpenRouter selection

```text
provider = OPENROUTER
```

Проверить, что вызывается:

```text
OpenRouterLlmClient
```

## Model forwarding

Проверить, что выбранный пользователем model передаётся client без подмены.

## Shared conversation

Начать разговор через OpenAI.

Следующее сообщение отправить через OpenRouter.

Проверить, что OpenRouter получает предыдущую Conversation.

## Statistics

Проверить, что `provider`, `model`, token usage и response time корректно попадают в AgentResponse.

## Reset

Проверить, что reset работает независимо от выбранного provider.

---

# 20. Integration tests

Добавить tests HTTP client layer без реального обращения к OpenAI/OpenRouter.

Использовать mock HTTP server либо существующий механизм тестирования проекта.

Проверить:

### OpenAI

```text
Authorization header
request body
model
messages
response mapping
usage mapping
```

### OpenRouter

```text
Authorization header
base URL
model
messages
response mapping
usage mapping
```

Не использовать реальные API keys в automated tests.

---

# 21. README

Обновить README.

Добавить настройку OpenAI:

```bash
export OPENAI_API_KEY="..."
export OPENAI_MODEL="gpt-4.1-mini"
```

Добавить настройку OpenRouter:

```bash
export OPENROUTER_API_KEY="..."
export OPENROUTER_MODEL="openai/gpt-4o-mini"
```

Описать, что provider выбирается в UI.

Добавить архитектуру:

```text
                         ┌→ OpenAiLlmClient → OpenAI
UI → Controller → Agent ─┤
                         └→ OpenRouterLlmClient → OpenRouter
               │
               └→ ConversationRepository → SQLite
```

---

# 22. Критерии готовности

Задача считается выполненной, если:

- поддерживается OpenAI;
- поддерживается OpenRouter;
- provider выбирается в UI;
- model можно изменить в UI;
- OpenAI и OpenRouter используют разные API keys;
- Agent не зависит от конкретного provider;
- существует общий LlmClient contract;
- OpenAI и OpenRouter реализованы отдельными infrastructure clients;
- история работает независимо от provider;
- можно сменить provider в середине диалога;
- SQLite persistence продолжает работать;
- reset продолжает работать;
- статистика показывает provider, model, tokens и response time;
- тесты проходят;
- проект собирается.

---

# 23. Финальная проверка

После реализации:

1. выполнить сборку;
2. запустить все unit tests;
3. запустить integration tests;
4. исправить ошибки;
5. проверить запуск через `./gradlew bootRun`;
6. вручную проверить запрос через OpenAI;
7. вручную проверить запрос через OpenRouter, если соответствующие API keys доступны;
8. проверить смену provider внутри одного диалога;
9. проверить restart и восстановление Conversation;
10. проверить reset.

Не ломать существующий функционал приложения.