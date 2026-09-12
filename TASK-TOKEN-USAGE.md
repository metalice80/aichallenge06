# Расширенный подсчёт токенов

Необходимо расширить существующую статистику использования токенов.

Приложение должно отображать статистику двух уровней:

1. токены текущего, последнего запроса к LLM;
2. накопленные токены всей текущей истории диалога с момента последнего reset.

---

# 1. Статистика текущего запроса

Для каждого успешного обращения к LLM необходимо сохранять usage, возвращённый соответствующим provider.

Минимально:

```text
currentInputTokens
currentOutputTokens
currentTotalTokens
```

Где:

```text
currentTotalTokens = currentInputTokens + currentOutputTokens
```

Если provider возвращает `totalTokens`, использовать его значение.

Пример:

```text
Current request

Input tokens:   350
Output tokens:   80
Total tokens:   430
```

Эти значения относятся только к **последнему вызову LLM**.

---

# 2. Статистика всего диалога

Дополнительно необходимо вести накопленную статистику с начала текущего Conversation.

Хранить:

```text
conversationInputTokens
conversationOutputTokens
conversationTotalTokens
```

Значения должны представлять собой сумму usage всех успешных LLM-вызовов текущего диалога:

```text
conversationInputTokens =
    Σ inputTokens всех успешных запросов

conversationOutputTokens =
    Σ outputTokens всех успешных запросов

conversationTotalTokens =
    conversationInputTokens + conversationOutputTokens
```

Если provider возвращает собственное `totalTokens`, допускается также суммировать фактически возвращённые `totalTokens`, однако итоговая модель приложения должна оставаться согласованной.

---

# 3. Важное определение

`conversationInputTokens` — это **не количество токенов уникального текста пользователя**.

Это сумма входных токенов, фактически обработанных LLM во всех запросах.

Например, если история диалога повторно передаётся модели:

```text
Request #1:
input = 100
output = 30

Request #2:
input = 180
output = 40
```

то статистика диалога должна быть:

```text
Conversation input tokens:  280
Conversation output tokens:  70
Conversation total tokens:  350
```

Даже если часть второго input состоит из сообщений, которые уже передавались при первом запросе.

Это отражает фактическое потребление токенов API и потенциальную стоимость диалога.

---

# 4. Не пересчитывать usage локально

Основным источником данных о токенах должен быть response конкретного provider:

```text
OpenAI usage
OpenRouter usage
```

Не использовать приблизительный локальный token counter для основных метрик, если provider уже возвращает usage.

Это необходимо потому, что:

- разные модели могут использовать разные tokenizer;
- OpenAI и OpenRouter могут возвращать provider-specific usage;
- локальный расчёт может отличаться от фактического биллинга.

Если usage отсутствует в provider response, соответствующие значения могут быть `null`.

Приложение не должно падать.

---

# 5. Модель статистики

Выделить статистику в отдельную внутреннюю модель.

Например:

```kotlin
data class TokenUsage(
    val inputTokens: Long?,
    val outputTokens: Long?,
    val totalTokens: Long?
)
```

Накопленная статистика может быть представлена отдельной моделью:

```kotlin
data class ConversationTokenUsage(
    val inputTokens: Long,
    val outputTokens: Long,
    val totalTokens: Long
)
```

Не обязательно использовать именно эти названия, но current usage и conversation usage должны быть явно разделены.

Для накопительных значений использовать `Long`, а не `Int`.

---

# 6. AgentResponse

Расширить response агента.

Например:

```kotlin
data class AgentResponse(
    val content: String,
    val provider: LlmProvider,
    val model: String,

    val currentUsage: TokenUsage,
    val conversationUsage: ConversationTokenUsage,

    val responseTimeMs: Long
)
```

Допускается плоская структура DTO:

```text
currentInputTokens
currentOutputTokens
currentTotalTokens

conversationInputTokens
conversationOutputTokens
conversationTotalTokens
```

Однако во внутренней domain/application модели предпочтительно сгруппировать связанные данные.

---

# 7. Когда увеличивать счётчики

Conversation statistics должна изменяться только после **успешно завершённого LLM-вызова**.

Алгоритм:

```text
User message
     ↓
LLM request
     ↓
successful response
     ↓
получить usage
     ↓
добавить current usage
к conversation usage
     ↓
сохранить conversation
```

Если LLM-вызов завершился ошибкой:

```text
timeout
401
429
5xx
network error
invalid response
```

накопленные счётчики не должны изменяться.

---

# 8. Работа с несколькими providers

Статистика должна быть общей для Conversation и не сбрасываться при смене provider или model.

Пример:

```text
Request #1
OpenAI
input: 100
output: 30

Request #2
OpenRouter
input: 180
output: 50
```

Результат:

```text
Current request:
Provider: OpenRouter
Input:    180
Output:    50
Total:    230

Conversation:
Input:     280
Output:     80
Total:     360
```

То есть статистика Conversation отражает использование LLM независимо от того, через какого provider выполнялись запросы.

---

# 9. Persistence статистики

Так как Conversation уже сохраняется в SQLite и восстанавливается после рестарта приложения, накопленная token statistics также должна сохраняться между перезапусками.

Сценарий:

```text
Request #1
conversationTotalTokens = 200

Request #2
conversationTotalTokens = 450

Application stop

Application start

Request #3
```

До выполнения Request #3 приложение должно восстановить:

```text
conversationTotalTokens = 450
```

После успешного Request #3 новое значение должно продолжить накапливаться, а не начинаться с нуля.

---

# 10. Предпочтительный способ persistence

Предпочтительно сохранять usage **для каждого успешного LLM-вызова**, а не только один агрегированный счётчик.

Например, отдельная таблица:

```sql
CREATE TABLE llm_request_usage (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    provider TEXT NOT NULL,
    model TEXT NOT NULL,
    input_tokens INTEGER,
    output_tokens INTEGER,
    total_tokens INTEGER,
    response_time_ms INTEGER NOT NULL,
    created_at TEXT NOT NULL
);
```

Conversation totals при необходимости могут вычисляться:

```text
SUM(input_tokens)
SUM(output_tokens)
SUM(total_tokens)
```

Такой подход позволяет:

- восстановить totals после restart;
- сохранить статистику по каждому запросу;
- анализировать использование разных моделей;
- в будущем рассчитывать стоимость;
- не терять историческую статистику.

Допускается хранить агрегированные значения непосредственно в Conversation, если это существенно проще для текущей архитектуры, но данные должны корректно переживать restart.

---

# 11. Reset

При нажатии:

```text
Сбросить чат
```

необходимо сбросить не только messages, но и накопленную token statistics.

После reset:

```text
conversationInputTokens = 0
conversationOutputTokens = 0
conversationTotalTokens = 0
```

А также очистить persisted usage текущего Conversation.

Следующий запрос должен начинать новую статистику с нуля.

---

# 12. Web UI

Раздел статистики необходимо разделить визуально на две части.

Пример:

```text
─────────────────────────────────────
Последний запрос

Provider:       OpenRouter
Model:          openai/gpt-4o-mini
Response time:  1.24 sec

Input tokens:   215
Output tokens:   74
Total tokens:   289

─────────────────────────────────────
Весь диалог

Input tokens:   1 420
Output tokens:    537
Total tokens:   1 957
─────────────────────────────────────
```

Пользователь должен сразу понимать разницу между:

```text
Последний запрос
```

и:

```text
Весь диалог
```

---

# 13. Восстановление UI после restart

После перезапуска приложения frontend должен получать не только сохранённую историю сообщений, но и накопленную conversation statistics.

Можно расширить существующий:

```http
GET /api/chat/history
```

либо добавить endpoint состояния:

```http
GET /api/chat/state
```

Например:

```json
{
  "messages": [
    {
      "role": "USER",
      "content": "Меня зовут Алексей"
    },
    {
      "role": "ASSISTANT",
      "content": "Приятно познакомиться, Алексей."
    }
  ],
  "conversationUsage": {
    "inputTokens": 420,
    "outputTokens": 105,
    "totalTokens": 525
  }
}
```

После открытия страницы UI должен восстановить:

- messages;
- accumulated token statistics.

Статистику последнего запроса после restart можно не восстанавливать, если это усложняет реализацию.

Накопленная статистика Conversation должна восстанавливаться обязательно.

---

# 14. Тесты

Добавить unit-тесты минимум для следующих сценариев.

## Первый запрос

LLM возвращает:

```text
inputTokens = 100
outputTokens = 20
totalTokens = 120
```

Ожидается:

```text
current:
100 / 20 / 120

conversation:
100 / 20 / 120
```

---

## Второй запрос

Следующий response:

```text
inputTokens = 180
outputTokens = 40
totalTokens = 220
```

Ожидается:

```text
current:
180 / 40 / 220

conversation:
280 / 60 / 340
```

---

## Смена provider

Первый запрос выполнить через OpenAI, второй через OpenRouter.

Проверить, что conversation totals являются суммой обоих вызовов.

---

## Ошибка LLM

После существующей accumulated statistics LLM выбрасывает exception.

Проверить, что totals не изменились.

---

## Restart

Сохранить usage в repository.

Создать новый экземпляр Agent / восстановить application state.

Проверить, что accumulated statistics восстановлена.

---

## Reset

После нескольких запросов выполнить:

```text
agent.reset()
```

Проверить:

```text
messages = empty
conversationInputTokens = 0
conversationOutputTokens = 0
conversationTotalTokens = 0
```

и persisted usage очищена.

---

# 15. Обновлённые критерии готовности

Задача считается выполненной, если:

- отображаются input/output/total tokens последнего запроса;
- отображаются input/output/total tokens всего текущего диалога;
- totals накапливаются после каждого успешного LLM-вызова;
- повторно переданные history tokens учитываются как часть фактического input usage;
- статистика общая для OpenAI и OpenRouter;
- смена модели не сбрасывает статистику;
- смена provider не сбрасывает статистику;
- ошибка LLM не увеличивает totals;
- conversation statistics переживает restart приложения;
- reset обнуляет messages и accumulated token statistics;
- UI чётко разделяет статистику последнего запроса и всего диалога;
- unit/integration tests проходят.