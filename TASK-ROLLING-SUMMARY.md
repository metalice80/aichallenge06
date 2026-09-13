# День 9. Управление контекстом: Rolling Summary

## 1. Цель

Доработать существующего AI-агента, добавив механизм управления размером контекста с помощью **Rolling Summary**.

Сейчас агент хранит полную историю Conversation и передаёт её модели.

Необходимо изменить формирование LLM-контекста таким образом, чтобы:

- последние сообщения передавались модели полностью;
- старая часть диалога заменялась кратким summary;
- summary периодически обновлялось по мере роста разговора;
- для суммаризации использовался отдельный настраиваемый LLM provider и model;
- полная исходная история по-прежнему сохранялась в SQLite и не удалялась;
- после перезапуска приложения summary и состояние compression восстанавливались.

Механизм должен работать автоматически и быть прозрачным для пользователя.

---

# 2. Существующее приложение

Это доработка уже существующего проекта.

В приложении уже реализованы:

- Kotlin;
- Spring Boot;
- web chat UI;
- `Agent`;
- abstraction `LlmClient`;
- несколько LLM providers;
- OpenAI;
- OpenRouter;
- возможность выбора provider и model;
- OpenRouter plugins из `application.yml`;
- multi-turn conversation;
- SQLite persistence;
- восстановление истории после restart;
- reset чата;
- статистика токенов текущего запроса;
- накопленная статистика токенов всего Conversation;
- статистика response time.

Не переписывать существующую архитектуру без необходимости.

Не ломать работающий функционал.

---

# 3. Общая идея Rolling Summary

Полная Conversation продолжает храниться в SQLite.

Но в запрос основной LLM больше не обязательно передавать всю историю целиком.

Контекст запроса должен состоять из:

```text
System Prompt
+
Rolling Summary старой части Conversation
+
последние несуммаризированные сообщения
+
новое сообщение пользователя
```

Пример:

```text
SYSTEM:
Ты полезный AI-ассистент...

SYSTEM / CONTEXT:
Краткое содержание предыдущего разговора:
Пользователь разрабатывает Kotlin/Spring Boot приложение.
Для persistence используется SQLite.
Поддерживаются OpenAI и OpenRouter.
...

USER:
последнее сообщение пользователя

ASSISTANT:
ответ

USER:
ещё одно сообщение

ASSISTANT:
ответ

USER:
новый вопрос
```

Старые сообщения, информация из которых уже вошла в summary, не должны повторно передаваться основной модели как отдельные сообщения.

---

# 4. Два настраиваемых порога

Необходимо поддерживать два независимых параметра.

## 4.1. Порог первого summary

Параметр определяет количество сообщений Conversation, после которого впервые запускается summarization.

Например:

```yaml
context:
  compression:
    summarize-after-messages: 20
```

Если в Conversation меньше 20 сообщений, summary отсутствует и используется обычная история.

Когда количество сообщений достигает установленного порога, старая часть Conversation должна быть суммаризирована.

---

## 4.2. Размер следующей порции

После появления первого summary оно не должно пересоздаваться после каждого сообщения.

Следующее обновление summary происходит после накопления заданного количества новых сообщений.

Например:

```yaml
context:
  compression:
    summarize-every-messages: 10
```

Логика:

```text
20 сообщений
    ↓
создаётся первое summary

ещё 10 новых сообщений
    ↓
existing summary + новые 10 сообщений
    ↓
updated summary

ещё 10 новых сообщений
    ↓
existing summary + следующие 10 сообщений
    ↓
updated summary
```

Это и есть Rolling Summary.

---

# 5. Пример работы

Конфигурация:

```yaml
context:
  compression:
    enabled: true
    summarize-after-messages: 20
    summarize-every-messages: 10
```

Conversation развивается следующим образом.

## До 20 сообщений

```text
Message 1
Message 2
...
Message 18
```

Summary отсутствует.

Основная модель получает обычную историю.

---

## После достижения 20 сообщений

Необходимо сформировать первое summary.

Например:

```text
Messages 1–10
       ↓
Summarizer
       ↓
Summary v1
```

При этом последние сообщения остаются как есть:

```text
Summary v1
+
Messages 11–20
```

Количество сообщений, которые остаются несуммаризированными после первого сжатия, должно определяться существующей логикой и значением `summarize-every-messages`.

Предпочтительная стратегия:

```text
summarize-after-messages = 20
summarize-every-messages = 10
```

означает:

```text
Messages 1–10 → Summary v1
Messages 11–20 → recent messages
```

Таким образом после compression в prompt остаётся около `summarize-every-messages` последних сообщений без изменений.

---

## После следующих 10 сообщений

Conversation:

```text
Summary v1
Messages 11–20
Messages 21–30
```

Теперь Messages 11–20 должны быть добавлены к предыдущему summary:

```text
Summary v1
+
Messages 11–20
       ↓
Summarizer
       ↓
Summary v2
```

После этого основной LLM получает:

```text
Summary v2
+
Messages 21–30
```

---

## Следующее обновление

```text
Summary v2
+
Messages 21–30
       ↓
Summarizer
       ↓
Summary v3
```

Основной контекст:

```text
Summary v3
+
Messages 31–40
```

И так далее.

---

# 6. Важное свойство Rolling Summary

При обновлении summary НЕ нужно повторно отправлять summarizer всю исходную историю.

Использовать:

```text
existing summary
+
новая порция старых сообщений
```

а не:

```text
Messages 1...30
```

Это позволяет ограничивать стоимость и размер summarization request.

---

# 7. Конфигурация

Добавить configuration примерно следующего вида:

```yaml
context:
  compression:
    enabled: true

    summarize-after-messages: 20
    summarize-every-messages: 10

    provider: OPENAI
    model: gpt-5-nano
```

Или для OpenRouter:

```yaml
context:
  compression:
    enabled: true

    summarize-after-messages: 20
    summarize-every-messages: 10

    provider: OPENROUTER
    model: openai/gpt-4o-mini
```

Конкретное название properties-класса выбрать в соответствии с архитектурой проекта.

Например:

```kotlin
@ConfigurationProperties("context.compression")
data class ContextCompressionProperties(
    val enabled: Boolean = true,
    val summarizeAfterMessages: Int,
    val summarizeEveryMessages: Int,
    val provider: LlmProvider,
    val model: String
)
```

---

# 8. Валидация конфигурации

Проверить configuration при старте приложения.

Минимальные требования:

```text
summarize-after-messages > 0
summarize-every-messages > 0
summarize-after-messages > summarize-every-messages
provider задан
model не blank
```

Некорректная configuration должна приводить к понятной ошибке startup configuration.

---

# 9. Отключение compression

Поддержать:

```yaml
context:
  compression:
    enabled: false
```

В этом режиме приложение должно работать как раньше:

```text
System Prompt
+
полная Conversation
+
новое сообщение
```

Summarizer не должен вызываться.

---

# 10. Summarizer как отдельный компонент

Не помещать логику summarization непосредственно в Controller или OpenAI/OpenRouter clients.

Добавить отдельный application/domain component.

Например:

```kotlin
interface ConversationSummarizer {

    fun summarize(
        currentSummary: String?,
        messages: List<ChatMessage>
    ): SummaryResult
}
```

Возможная реализация:

```text
LlmConversationSummarizer
```

Она использует существующую LLM infrastructure.

Архитектура:

```text
                           ┌─────────────────────┐
                           │      ChatAgent      │
                           └──────────┬──────────┘
                                      │
                 ┌────────────────────┴────────────────────┐
                 │                                         │
                 ▼                                         ▼
       ContextManager / Compressor                  Main LlmClient
                 │                                         │
                 ▼                                         ▼
       ConversationSummarizer                      user-selected LLM
                 │
                 ▼
          LlmClientResolver
                 │
                 ▼
      configured summary provider
                 │
                 ▼
      configured summary model
```

---

# 11. Не привязывать Summarizer к OpenAI

Summarizer не должен зависеть напрямую от:

```text
OpenAiLlmClient
```

или:

```text
OpenRouterLlmClient
```

Он должен использовать существующий provider-neutral механизм выбора LLM client.

Например:

```text
LlmProvider
        ↓
LlmClientResolver
        ↓
LlmClient
```

Таким образом configuration:

```yaml
provider: OPENAI
```

вызывает OpenAI client.

А:

```yaml
provider: OPENROUTER
```

вызывает OpenRouter client.

---

# 12. Независимость основной модели и Summarizer

Модель, отвечающая пользователю, и модель summarization являются независимыми.

Например:

```text
Main request:
Provider: OPENROUTER
Model: anthropic/...

Summary:
Provider: OPENAI
Model: gpt-5-nano
```

или наоборот.

Смена provider/model пользователем в UI не должна менять configured summarizer.

---

# 13. Prompt для summarization

Использовать отдельный системный prompt для summarizer.

Его желательно также вынести в configuration.

Например:

```yaml
context:
  compression:
    system-prompt: >
      Сожми предыдущий диалог в компактное summary.
      Сохрани важные факты, решения, предпочтения пользователя,
      технический контекст, ограничения и открытые вопросы.
      Не добавляй информацию, которой не было в диалоге.
      Summary должно позволять продолжить разговор без полной истории.
```

Summarization request должен явно содержать:

1. существующее summary, если оно есть;
2. новую порцию сообщений;
3. инструкцию создать новое полное updated summary.

---

# 14. Формирование первого summary

Если summary отсутствует:

```text
currentSummary = null
```

Summarizer получает только выбранную старую порцию сообщений.

Например:

```text
Messages 1–10
```

Результат:

```text
Summary v1
```

---

# 15. Обновление existing summary

Если summary уже существует:

```text
Summary v1
+
Messages 11–20
```

Summarizer должен вернуть самостоятельное:

```text
Summary v2
```

`Summary v2` должно полностью заменять `Summary v1`.

Не хранить цепочку:

```text
Summary v1
Summary v2
Summary v3
```

как часть prompt.

Основной LLM всегда получает только последнее актуальное summary.

---

# 16. Persistence summary

Summary обязательно должно сохраняться в SQLite.

После restart:

```text
Application restart
       ↓
load full Conversation
       ↓
load latest Summary
       ↓
load compression state
       ↓
продолжить Conversation
```

Не пересоздавать summary с нуля после каждого restart.

---

# 17. Данные summary

Хранить минимум:

```text
summary text
createdAt / updatedAt
number или ID последнего сообщения,
включённого в summary
```

Например:

```text
conversation_summary

id
content
last_summarized_message_id
created_at
updated_at
```

Конкретная database schema может отличаться.

Главное — приложение должно однозначно понимать:

```text
какие сообщения уже находятся в summary
```

и:

```text
какие сообщения ещё должны передаваться как recent history
```

---

# 18. Не использовать summarized=true как единственный источник истины

Допускается добавить к сообщениям metadata, но предпочтительно хранить cursor/high-water mark:

```text
lastSummarizedMessageId
```

или эквивалент.

Это позволяет определить:

```text
message.id <= lastSummarizedMessageId
→ информация уже включена в summary
```

и избежать массового изменения старых сообщений.

---

# 19. Полная история не удаляется

Очень важно:

Rolling Summary используется только для формирования LLM context.

Исходные сообщения НЕ удалять из SQLite.

Полная Conversation должна оставаться доступной:

- для UI;
- для debugging;
- для анализа;
- для повторного построения summary в будущем;
- для статистики.

Таким образом:

```text
SQLite
────────────────────────────
полная история: сохраняется

LLM prompt
────────────────────────────
summary + recent messages
```

---

# 20. UI

Существующий UI чата должен продолжать показывать полную историю Conversation.

Пользователь не должен видеть вместо старых сообщений summary.

Например, даже если:

```text
Messages 1–20
```

уже compressed для LLM, UI продолжает показывать их как обычные сообщения.

Summary является внутренней памятью Agent.

---

# 21. Формирование основного LLM request

Если summary отсутствует:

```text
System Prompt
+
all current messages
+
new user message
```

Если summary существует:

```text
System Prompt
+
Summary
+
messages after lastSummarizedMessageId
+
new user message
```

Старые summarized messages не должны повторно добавляться.

---

# 22. Представление Summary в provider request

Не добавлять `SUMMARY` как provider-specific роль, если OpenAI/OpenRouter такой роли не поддерживают.

Во внутренней модели можно иметь отдельную сущность `ConversationSummary`.

При построении LLM request преобразовать её, например, в дополнительное system/context сообщение:

```text
SYSTEM:
The following is a summary of the earlier conversation:

<summary>
```

Не смешивать summary с обычным ответом `ASSISTANT`.

---

# 23. Когда выполнять compression

Compression должно происходить автоматически.

Предпочтительный сценарий:

```text
успешный USER + ASSISTANT exchange
        ↓
сохранить новые messages
        ↓
проверить threshold
        ↓
если накопилась необходимая порция
        ↓
выполнить summarization
        ↓
сохранить updated summary
```

Допускается выполнить compression непосредственно перед следующим основным LLM request, если это лучше соответствует существующей архитектуре.

Главное:

- поведение должно быть детерминированным;
- одни и те же сообщения не должны суммаризироваться повторно;
- threshold должен соблюдаться.

---

# 24. Ошибка summarization

Ошибка внутреннего summarizer не должна уничтожать Conversation.

Если summarization завершился ошибкой:

- старое summary сохранить;
- compression cursor не продвигать;
- исходные сообщения не изменять;
- не записывать неполный summary;
- залогировать ошибку.

Предпочтительно, чтобы основной chat оставался работоспособным.

Если безопасно сформировать prompt со старым summary и дополнительными unsummarized messages, использовать этот fallback.

Не терять пользовательскую историю из-за ошибки summarizer.

---

# 25. Atomic update

Обновление:

```text
summary
+
lastSummarizedMessageId
```

должно быть логически атомарным.

Не должно возникать состояния:

```text
новое summary записано
но cursor старый
```

или:

```text
cursor продвинут
но summary не сохранено
```

При необходимости использовать существующие Spring transactions.

---

# 26. Token usage summarizer

Summarization — это отдельный LLM API call.

Его usage не следует смешивать со статистикой **основного пользовательского запроса**.

То есть:

```text
Current request tokens
```

должны по-прежнему показывать usage основной модели, отвечавшей пользователю.

Не добавлять summarizer tokens в:

```text
currentInputTokens
currentOutputTokens
```

основного запроса.

---

# 27. Накопленная статистика

Существующую conversation token statistics основного чата сохранить без изменения семантики.

Если архитектура позволяет, дополнительно сохранить usage summarizer отдельно.

Например:

```text
summaryInputTokens
summaryOutputTokens
summaryTotalTokens
```

Реализация отдельной статистики summarization является желательной, но не должна ломать существующую статистику.

Главное требование:

```text
main conversation usage
```

и:

```text
summary/compression usage
```

не должны неявно смешиваться.

---

# 28. OpenRouter plugins

Если summarizer использует OpenRouter, использовать существующую OpenRouter infrastructure.

Не переносить plugin configuration в Summarizer.

OpenRouter client должен продолжать формировать requests согласно своей существующей configuration.

Summarizer должен знать только:

```text
provider
model
messages
```

---

# 29. Reset

При:

```text
Сбросить чат
```

необходимо очистить:

- Conversation messages;
- persistent history;
- rolling summary;
- compression cursor / last summarized message id;
- conversation token statistics согласно существующей логике;
- summary-specific usage, если оно реализовано.

Следующий запрос должен начинаться полностью с чистого состояния.

После reset + restart старое summary не должно восстанавливаться.

---

# 30. Restart

Проверить сценарий:

```text
Conversation
        ↓
создан Summary v2
        ↓
application stop
        ↓
application start
        ↓
Conversation + Summary v2 restored
        ↓
новые сообщения
        ↓
после достижения следующего порога
        ↓
Summary v3
```

После restart уже summarized сообщения не должны повторно отправляться summarizer.

---

# 31. Context Manager

Желательно выделить формирование effective context в отдельный компонент.

Например:

```kotlin
interface ConversationContextManager {

    fun buildContext(
        conversation: Conversation,
        summary: ConversationSummary?
    ): List<ChatMessage>

    fun shouldSummarize(...): Boolean
}
```

Или разделить ответственность на:

```text
ContextWindowManager
ConversationSummarizer
```

Не обязательно использовать именно эти интерфейсы.

Главное — не превращать `ChatAgent` в класс, содержащий всю persistence, compression, provider selection и prompt-building логику одновременно.

---

# 32. Unit tests

Добавить unit tests минимум для следующих сценариев.

## Compression disabled

```yaml
enabled: false
```

Проверить:

- summarizer не вызывается;
- используется обычная Conversation.

---

## Threshold ещё не достигнут

Например:

```text
summarize-after-messages = 20
current messages = 18
```

Проверить, что summary не создаётся.

---

## Создание первого summary

Configuration:

```text
summarize-after-messages = 20
summarize-every-messages = 10
```

Conversation содержит 20 сообщений.

Проверить:

```text
Messages 1–10
```

отправлены summarizer.

Проверить сохранение:

```text
Summary v1
lastSummarizedMessageId = Message 10
```

---

## Main context после первого summary

Проверить, что основная модель получает:

```text
System Prompt
Summary v1
Messages 11–20
new message
```

и не получает Messages 1–10 отдельно.

---

## Rolling update

Есть:

```text
Summary v1
lastSummarizedMessageId = 10
```

Накопились следующие 10 сообщений.

Проверить, что summarizer получает:

```text
Summary v1
Messages 11–20
```

и возвращает `Summary v2`.

---

## После Rolling update

Проверить, что основной context содержит:

```text
Summary v2
Messages после Message 20
```

---

## Независимый provider

Основной Agent использует:

```text
OPENROUTER
```

Summarizer configured:

```text
OPENAI
```

Проверить, что summarization идёт через OpenAI client.

---

## Независимая model

Проверить, что configured summary model действительно передаётся LLM client.

---

## Summarizer error

Summarizer выбрасывает exception.

Проверить:

- existing summary не поврежден;
- cursor не продвинулся;
- Conversation не потеряна.

---

## Restart

Сохранить:

```text
Summary
lastSummarizedMessageId
```

Создать новое application state / Agent.

Проверить, что compression продолжается с правильного места.

---

## Reset

Проверить удаление:

```text
messages
summary
compression cursor
```

---

# 33. Integration tests

Добавить integration tests persistence layer.

Минимально проверить:

```text
save summary
↓
restart/reload
↓
load summary
↓
content совпадает
↓
lastSummarizedMessageId совпадает
```

Также проверить update:

```text
Summary v1
↓
Summary v2
```

После reload должна возвращаться актуальная версия.

---

# 34. Тест фактического LLM context

Добавить test на формирование provider request после compression.

Например, при:

```text
Summary v2
Messages 21–30
```

HTTP/request DTO основной модели должен содержать:

```text
system prompt
summary v2
messages 21–30
new user message
```

и не содержать:

```text
messages 1–20
```

---

# 35. Логирование

Добавить полезные технические logs без содержимого пользовательских сообщений.

Например:

```text
Creating initial conversation summary for 10 messages
```

```text
Updating conversation summary with 10 new messages
```

```text
Summary updated, lastSummarizedMessageId=42
```

Не логировать полный private conversation или API keys.

---

# 36. README

Обновить README.

Добавить раздел:

```text
Rolling Context Compression
```

Описать:

- зачем нужен summary;
- что полная история остаётся в SQLite;
- что LLM получает summary + recent messages;
- что summary переживает restart;
- что reset удаляет summary.

Добавить пример configuration:

```yaml
context:
  compression:
    enabled: true
    summarize-after-messages: 20
    summarize-every-messages: 10
    provider: OPENAI
    model: gpt-5-nano
```

---

# 37. Ручной тест

Использовать небольшие значения, чтобы не создавать десятки сообщений вручную.

Например:

```yaml
context:
  compression:
    enabled: true
    summarize-after-messages: 6
    summarize-every-messages: 2
    provider: OPENAI
    model: <cheap-summary-model>
```

Провести Conversation минимум из 8–10 сообщений.

Проверить:

1. до threshold summary отсутствует;
2. после threshold появляется первое summary;
3. следующие сообщения остаются как recent history;
4. после следующей порции summary обновляется;
5. основной LLM продолжает понимать старый контекст;
6. после restart summary сохраняется;
7. после reset summary удаляется.

---

# 38. Критерии готовности

Задача считается выполненной, если:

- Rolling Summary включается через configuration;
- compression можно полностью отключить;
- первый threshold задаётся через configuration;
- размер каждой следующей порции задаётся через configuration;
- provider summarizer задаётся через configuration;
- model summarizer задаётся через configuration;
- summary создаётся только после достижения threshold;
- summary обновляется порциями, а не после каждого сообщения;
- для update используется existing summary + новая порция;
- исходные сообщения не удаляются из SQLite;
- summarized history не передаётся основной LLM повторно;
- последние сообщения передаются полностью;
- summary сохраняется в SQLite;
- summary восстанавливается после restart;
- compression cursor восстанавливается после restart;
- reset очищает summary и compression state;
- main provider и summary provider независимы;
- main model и summary model независимы;
- ошибки summarization не повреждают Conversation;
- существующие OpenAI/OpenRouter/plugins/token statistics продолжают работать;
- unit tests проходят;
- integration tests проходят;
- проект собирается.

---

# 39. Финальная проверка OMP

После реализации обязательно:

1. изучить существующую архитектуру до внесения изменений;
2. реализовать Rolling Summary без переписывания работающих компонентов без необходимости;
3. выполнить `build`;
4. выполнить все unit tests;
5. выполнить integration tests;
6. исправить ошибки;
7. проверить persistence summary;
8. проверить restart;
9. проверить reset;
10. проверить работу с разными main/summarizer providers;
11. убедиться, что основной LLM больше не получает summarized messages целиком.

В конце кратко описать:

- какие компоненты добавлены;
- где хранится summary;
- как определяется момент первого summarization;
- как определяется момент следующего update;
- как хранится compression cursor;
- какой context фактически получает основная модель;
- как отдельно вызывается summarizer.