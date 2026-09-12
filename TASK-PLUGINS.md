# Добавление конфигурируемых plugins для LLM providers

## Цель

Доработать существующее приложение так, чтобы список plugins, передаваемый LLM provider, можно было задавать через `application.yml`.

Плагины должны:

- конфигурироваться без изменения исходного кода;
- поддерживать включение/отключение через `enabled`;
- передаваться в JSON request body соответствующего provider;
- не влиять на providers, которые не поддерживают plugins.

Основной сценарий — использование plugins OpenRouter.

---

# 1. Пример конфигурации

Добавить возможность описывать plugins в `application.yml`.

Например:

```yaml
llm:
  openrouter:
    api-key: ${OPENROUTER_API_KEY:}
    base-url: ${OPENROUTER_BASE_URL:https://openrouter.ai/api/v1}
    default-model: ${OPENROUTER_MODEL:openai/gpt-4o-mini}

    plugins:
      - id: context-compression
        enabled: false

      - id: web
        enabled: true
```

Конфигурация должна маппиться через `@ConfigurationProperties`.

Например:

```kotlin
data class PluginProperties(
    val id: String,
    val enabled: Boolean = true
)
```

Конкретная структура properties может отличаться в соответствии с существующей конфигурацией проекта.

---

# 2. JSON, отправляемый provider

Для OpenRouter конфигурация:

```yaml
plugins:
  - id: context-compression
    enabled: false
```

должна приводить к появлению в request body:

```json
{
  "model": "...",
  "messages": [
    ...
  ],
  "plugins": [
    {
      "id": "context-compression",
      "enabled": false
    }
  ]
}
```

Если настроено несколько plugins:

```yaml
plugins:
  - id: context-compression
    enabled: false

  - id: web
    enabled: true
```

request должен содержать:

```json
{
  "plugins": [
    {
      "id": "context-compression",
      "enabled": false
    },
    {
      "id": "web",
      "enabled": true
    }
  ]
}
```

Не хардкодить конкретные plugin IDs в коде.

---

# 3. Значение enabled

Поле:

```yaml
enabled: true
```

или:

```yaml
enabled: false
```

должно передаваться provider как часть plugin configuration.

Не использовать `enabled=false` как указание приложению удалить plugin из массива.

То есть:

```yaml
- id: context-compression
  enabled: false
```

должно давать:

```json
{
  "id": "context-compression",
  "enabled": false
}
```

а не отсутствие этого элемента в `plugins`.

Это важно, поскольку значение `enabled` относится к конфигурации plugin на стороне provider.

---

# 4. Отсутствие plugins

Если в `application.yml` plugins отсутствуют:

```yaml
llm:
  openrouter:
    api-key: ...
```

или указан пустой список:

```yaml
plugins: []
```

предпочтительно вообще не добавлять поле:

```json
"plugins"
```

в request body.

То есть request остаётся:

```json
{
  "model": "...",
  "messages": [...]
}
```

а не:

```json
{
  "model": "...",
  "messages": [...],
  "plugins": []
}
```

---

# 5. Provider-specific capability

Не добавлять plugins в общий domain `LlmRequest`, если это приведёт к тому, что Agent начнёт знать детали OpenRouter API.

Существующая архитектура:

```text
ChatAgent
    ↓
LlmClient
    ↓
provider implementation
```

должна сохраниться.

Plugins в данном случае являются infrastructure/provider-specific configuration.

Предпочтительная архитектура:

```text
                      ┌─────────────────────┐
                      │      ChatAgent      │
                      └──────────┬──────────┘
                                 │
                           LlmRequest
                                 │
                ┌────────────────┴────────────────┐
                ▼                                 ▼
       OpenAiLlmClient                   OpenRouterLlmClient
                                               │
                                               │
                                     OpenRouterProperties
                                               │
                                               └── plugins
```

`ChatAgent` не должен:

- читать `application.yml`;
- знать plugin IDs;
- добавлять поле `plugins`;
- знать формат OpenRouter plugin API.

---

# 6. OpenRouter request DTO

Расширить request DTO OpenRouter.

Например:

```kotlin
data class OpenRouterRequest(
    val model: String,
    val messages: List<OpenRouterMessage>,
    val plugins: List<OpenRouterPlugin>? = null
)
```

Plugin:

```kotlin
data class OpenRouterPlugin(
    val id: String,
    val enabled: Boolean
)
```

Названия классов могут отличаться в зависимости от существующей структуры проекта.

Важно отделить:

```text
configuration properties
```

от:

```text
HTTP API DTO
```

То есть желательно не отправлять `PluginProperties` напрямую через HTTP serializer.

---

# 7. Mapping

`OpenRouterLlmClient` или отдельный mapper должен преобразовывать:

```text
OpenRouterProperties.plugins
```

в:

```text
OpenRouterRequest.plugins
```

Например:

```text
application.yml

plugins:
  - id: context-compression
    enabled: false
        ↓
OpenRouterProperties
        ↓
OpenRouter request mapper
        ↓
OpenRouterRequest
        ↓
JSON
```

---

# 8. OpenAI

Существующая интеграция OpenAI не должна измениться.

OpenAI request не должен неожиданно получать:

```json
"plugins": [...]
```

только потому, что plugins настроены для OpenRouter.

Конфигурация должна быть scoped к provider:

```yaml
llm:
  openai:
    ...

  openrouter:
    ...
    plugins:
      ...
```

---

# 9. Расширяемость plugin configuration

Не проектировать модель таким образом, чтобы plugin всегда состоял только из:

```text
id
enabled
```

OpenRouter plugins могут иметь дополнительные параметры.

Например web plugin может иметь собственные настройки. OpenRouter документирует передачу plugin-specific параметров вместе с `id`. citeturn219209search1turn219209search3

Поэтому архитектура должна допускать последующее расширение:

```yaml
plugins:
  - id: web
    enabled: true
    options:
      max-results: 3
```

Реализовывать arbitrary options в рамках текущей задачи необязательно.

Но текущая реализация не должна делать расширение заведомо невозможным.

---

# 10. Валидация

При старте приложения проверить configuration plugins.

Минимально:

```text
id != blank
```

Некорректная configuration:

```yaml
plugins:
  - id: ""
    enabled: true
```

не должна молча превращаться в некорректный API request.

Использовать существующий механизм Spring configuration validation, если он уже присутствует в проекте.

---

# 11. Логирование

Не логировать API keys.

Допускается debug logging вида:

```text
OpenRouter request uses 2 configured plugins:
context-compression, web
```

Не обязательно логировать полный request body.

---

# 12. Unit tests

Добавить unit tests минимум для следующих сценариев.

## Один plugin

Configuration:

```yaml
plugins:
  - id: context-compression
    enabled: false
```

Проверить, что OpenRouter request содержит:

```json
"plugins": [
  {
    "id": "context-compression",
    "enabled": false
  }
]
```

---

## Enabled plugin

Configuration:

```yaml
plugins:
  - id: web
    enabled: true
```

Проверить сохранение:

```json
"enabled": true
```

---

## Несколько plugins

Configuration:

```yaml
plugins:
  - id: context-compression
    enabled: false

  - id: web
    enabled: true
```

Проверить:

- оба plugin присутствуют;
- порядок соответствует конфигурации;
- значения `enabled` сохранены.

---

## Plugins отсутствуют

При пустом configuration:

```yaml
plugins: []
```

проверить, что request не содержит поле `plugins` либо оно сериализуется согласно принятому контракту как `null` и исключается Jackson configuration.

Предпочтительный JSON:

```json
{
  "model": "...",
  "messages": [...]
}
```

---

## OpenAI

При наличии OpenRouter plugins выполнить запрос через OpenAI client.

Проверить, что OpenAI request не содержит plugin configuration.

---

# 13. HTTP integration test

Добавить или обновить integration test OpenRouter client с mock HTTP server.

Проверить фактический JSON request.

Например configuration:

```yaml
plugins:
  - id: context-compression
    enabled: false
```

ожидаемый fragment:

```json
{
  "plugins": [
    {
      "id": "context-compression",
      "enabled": false
    }
  ]
}
```

Тест должен проверять именно сериализованный HTTP request, а не только внутренний DTO.

---

# 14. README

Обновить README.

Добавить раздел:

```text
OpenRouter plugins
```

с примером:

```yaml
llm:
  openrouter:
    plugins:
      - id: context-compression
        enabled: false
```

и примером нескольких plugins:

```yaml
llm:
  openrouter:
    plugins:
      - id: context-compression
        enabled: false

      - id: web
        enabled: true
```

Указать, что plugins относятся к OpenRouter и передаются в API request body.

---

# 15. Не менять существующий функционал

После реализации должны продолжить работать:

- OpenAI;
- OpenRouter;
- выбор provider;
- выбор model;
- multi-turn chat;
- SQLite persistence;
- восстановление Conversation после restart;
- reset;
- current token usage;
- accumulated conversation token usage;
- статистика response time.

Добавление plugins не должно влиять на подсчёт токенов или persistence Conversation.

---

# 16. Критерии готовности

Задача считается выполненной, если:

- plugins задаются через `application.yml`;
- поддерживается произвольный `id`;
- поддерживается `enabled=true`;
- поддерживается `enabled=false`;
- все configured plugins передаются OpenRouter;
- `enabled=false` не приводит к удалению plugin из request;
- пустая конфигурация не добавляет ненужный `plugins` array;
- Agent не знает о OpenRouter plugins;
- OpenAI integration не изменена;
- API keys не попадают в лог;
- unit tests проходят;
- HTTP integration test проверяет реальный JSON;
- проект успешно собирается.