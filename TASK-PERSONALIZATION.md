# Персонализация агента поверх модели памяти

## 1. Цель

Доработать существующего AI-агента и добавить явную персонализацию пользователя поверх уже реализованной модели памяти.

Необходимо:

- создать модель пользовательского профиля;
- позволить создавать несколько профилей;
- позволить выбирать активный профиль;
- хранить в профиле структурированные предпочтения пользователя;
- поддерживать custom instructions;
- автоматически подключать активный профиль к каждому основному LLM-запросу;
- учитывать профиль совместно с SHORT_TERM, WORKING и LONG_TERM memory;
- корректно разрешать конфликты между профилем и текущей Task;
- сохранять профили в SQLite;
- восстанавливать их после restart;
- сделать персонализацию наблюдаемой через существующий Effective Context Inspector.

Необходимо проверить, что одинаковый запрос при разных профилях приводит к адаптированным ответам.

---

# 2. Существующий проект

Это доработка существующего приложения.

В проекте уже реализованы:

- Kotlin;
- Spring Boot;
- web chat UI;
- Agent abstraction;
- OpenAI;
- OpenRouter;
- LlmClient/LlmClientResolver;
- выбор provider/model;
- OpenRouter plugins;
- SQLite persistence;
- token statistics;
- context strategies;
- Sliding Window;
- Sticky Facts;
- Branching;
- Rolling Summary;
- Task;
- выбор активной Task;
- SHORT_TERM memory;
- WORKING memory;
- LONG_TERM memory;
- Memory Extractor;
- Memory Inspector;
- Last Memory Update;
- Effective Context Inspector.

Перед реализацией изучить существующую архитектуру.

Не создавать:

- новый параллельный Agent;
- вторую persistence infrastructure;
- второй Context Builder;
- отдельную несовместимую систему memory.

Переиспользовать существующие abstractions.

---

# 3. Profile — не Memory Layer

User Profile не является четвёртым видом памяти.

Разделять:

```text
MEMORY
├── SHORT_TERM
├── WORKING
└── LONG_TERM

PERSONALIZATION
└── USER PROFILE
```

Profile содержит **явно заданные пользователем настройки**.

LONG_TERM содержит информацию, которую Agent автоматически извлёк из предыдущих разговоров.

Таким образом:

```text
PROFILE
────────────────────────────
Explicit personalization

Пользователь явно настроил:
"Отвечай по-русски"
"Я senior developer"
"Примеры сначала кодом"


LONG_TERM
────────────────────────────
Learned personalization

Agent автоматически узнал:
"Пользователь часто работает с Kotlin"
"Пользователь предпочитает PostgreSQL"
```

Не смешивать эти источники.

---

# 4. Несколько профилей

Один пользователь может создать несколько profiles.

Например:

```text
Developer
Student
Manager
```

В каждый момент времени активен один профиль.

Добавить selector:

```text
Profile:

[ Developer ▼ ]

[ + Новый профиль ] [ Редактировать ]
```

---

# 5. UserProfile

Добавить persistent entity/model `UserProfile`.

Минимальные поля:

```text
id
name
responseLanguage
expertiseLevel
responseStyle
responseFormat
customInstructions
createdAt
updatedAt
```

Допускается дополнительная metadata, если она действительно необходима.

---

# 6. Profile Name

Каждый профиль должен иметь понятное пользователю название.

Например:

```text
Developer
Beginner
Manager
Technical Expert
```

Название используется в UI.

---

# 7. Response Language

Добавить preference:

```text
responseLanguage
```

Минимально поддержать:

```text
RUSSIAN
ENGLISH
```

Архитектура должна позволять добавить другие языки без серьёзного изменения модели.

---

# 8. Expertise Level

Добавить:

```text
expertiseLevel
```

Минимальные значения:

```text
BEGINNER
INTERMEDIATE
ADVANCED
```

Семантика:

### BEGINNER

- объяснять термины;
- не предполагать глубоких предварительных знаний;
- использовать простые примеры.

### INTERMEDIATE

- использовать обычную техническую терминологию;
- объяснять сложные или неочевидные моменты.

### ADVANCED

- не объяснять базовые понятия без необходимости;
- использовать профессиональную терминологию;
- фокусироваться на архитектуре, trade-offs и implementation details.

---

# 9. Response Style

Добавить:

```text
responseStyle
```

Минимально:

```text
CONCISE
DETAILED
EDUCATIONAL
TECHNICAL
```

Система должна преобразовывать выбранное значение в понятные инструкции для LLM.

Не отправлять модели просто:

```text
responseStyle=CONCISE
```

если можно сформировать понятную instruction:

```text
Отвечай кратко и по существу.
Избегай избыточных пояснений.
```

---

# 10. Response Format

Добавить:

```text
responseFormat
```

Минимально:

```text
TEXT
STRUCTURED
CODE_FIRST
STEP_BY_STEP
```

Примеры:

### TEXT

Обычный связный ответ.

### STRUCTURED

Использовать заголовки, короткие блоки и списки, когда это помогает восприятию.

### CODE_FIRST

Для programming questions сначала показывать основной пример кода, затем пояснение.

### STEP_BY_STEP

Объяснять решение последовательными шагами.

---

# 11. Custom Instructions

Добавить:

```text
customInstructions
```

Это свободный многострочный текст.

Пример:

```text
Примеры кода желательно писать на Kotlin.

Не объясняй базовый синтаксис Java/Kotlin.

Для архитектурных вопросов показывай схему компонентов.

SQL примеры используй для PostgreSQL.
```

Custom Instructions должны передаваться основной модели вместе с другими параметрами Profile.

---

# 12. UI создания профиля

Добавить форму:

```text
Create Profile

Name:
[ Developer                    ]

Language:
[ Russian ▼ ]

Expertise:
[ Advanced ▼ ]

Response style:
[ Concise ▼ ]

Response format:
[ Code first ▼ ]

Custom instructions:
┌──────────────────────────────────────┐
│ Используй примеры на Kotlin.         │
│ Не объясняй базовый синтаксис.       │
└──────────────────────────────────────┘

[ Save ] [ Cancel ]
```

---

# 13. Редактирование профиля

Пользователь должен иметь возможность изменить существующий Profile.

Например:

```text
Developer

responseStyle:
CONCISE → DETAILED
```

Следующий LLM request должен использовать уже обновлённый профиль.

Не требовать restart приложения.

---

# 14. Active Profile

Приложение должно знать активный Profile.

При выборе:

```text
Profile: Developer
```

следующий запрос должен автоматически использовать его.

Пользователь не должен каждый раз отдельно передавать Profile вручную.

---

# 15. Persistence Profile

Profiles должны сохраняться в SQLite.

Например:

```text
user_profile

id
name
response_language
expertise_level
response_style
response_format
custom_instructions
created_at
updated_at
```

Также необходимо сохранять информацию об active profile либо иметь предсказуемый механизм его восстановления.

После restart:

```text
profiles → restored
active profile → restored
```

---

# 16. Profile Repository

Добавить abstraction, соответствующую существующей persistence architecture.

Например:

```kotlin
interface UserProfileRepository {

    fun findAll(): List<UserProfile>

    fun findById(id: Long): UserProfile?

    fun save(profile: UserProfile): UserProfile

    fun update(profile: UserProfile)

    fun getActive(): UserProfile?

    fun setActive(id: Long)
}
```

Конкретный API адаптировать к существующему проекту.

Agent не должен напрямую работать с SQLite.

---

# 17. Profile Service

При необходимости добавить application service:

```text
UserProfileService
```

Он отвечает за:

- создание;
- редактирование;
- получение списка;
- выбор active profile;
- получение active profile.

Не помещать CRUD logic непосредственно в Agent.

---

# 18. Подключение Profile к LLM

Active Profile должен автоматически добавляться к каждому **основному пользовательскому LLM request**.

Conceptual context:

```text
SYSTEM
────────────────────────
base system instructions


LONG-TERM MEMORY
────────────────────────
learned persistent information


USER PROFILE
────────────────────────
Language: Russian
Expertise: Advanced
Style: Concise
Format: Code-first

Custom instructions:
- examples preferably in Kotlin
- don't explain basic syntax


WORKING MEMORY
────────────────────────
Task: Payment Service
language = Java
database = PostgreSQL


SHORT-TERM
────────────────────────
...


CURRENT USER MESSAGE
────────────────────────
Покажи реализацию repository.
```

---

# 19. Profile и внутренние LLM-вызовы

Profile предназначен прежде всего для **основной модели, отвечающей пользователю**.

Не применять автоматически пользовательский Profile к:

```text
Memory Extractor
Facts Extractor
Rolling Summary
internal classifiers
```

если это не требуется их собственной логикой.

Например:

```text
responseStyle = CONCISE
```

не должен заставлять Memory Extractor менять JSON format.

Внутренние LLM operations должны использовать собственные system prompts.

---

# 20. Семантический приоритет

При конфликте информации использовать следующий приоритет:

```text
Current User Message
        >
Working Memory / Task
        >
Explicit User Profile
        >
Long-Term Memory
        >
Application Defaults
```

---

# 21. Пример конфликта Profile и Task

Profile:

```text
preferred examples = Kotlin
```

Working Memory текущей Task:

```text
language = Java
```

User:

```text
Покажи реализацию repository для текущего проекта.
```

Ожидается:

```text
Java
```

потому что конкретное решение текущей Task имеет больший приоритет, чем общее предпочтение Profile.

---

# 22. Fallback на Profile

Новая Task не содержит:

```text
language
```

Profile:

```text
customInstructions:
Используй Kotlin для примеров кода.
```

User:

```text
Покажи пример REST controller.
```

Ожидается Kotlin.

То есть Profile используется как default preference, если текущая Task не задаёт более конкретного ограничения.

---

# 23. Profile и Long-Term Memory

Если:

```text
PROFILE:
responseLanguage = Russian
```

а:

```text
LONG_TERM:
preferred_answer_language = English
```

использовать Profile:

```text
Russian
```

поскольку explicit настройка имеет приоритет над автоматически learned memory.

---

# 24. Current Message override

Если Profile:

```text
responseLanguage = Russian
```

но пользователь пишет:

```text
Ответь на этот вопрос по-английски.
```

текущий explicit request имеет максимальный приоритет.

Ответ должен быть на английском.

---

# 25. Context Builder

Интегрировать Profile в существующий механизм построения effective context.

Не создавать отдельный второй context pipeline.

Предпочтительно:

```text
EffectiveContextBuilder

    System
       +
    Long-Term
       +
    Profile
       +
    Working
       +
    Effective Short-Term
       +
    Current Message
```

При этом semantic priority должен быть явно отражён в инструкциях модели.

---

# 26. Effective Context Inspector

Расширить существующий Effective Context Inspector.

Добавить отдельный блок:

```text
USER PROFILE

Profile: Developer
Language: Russian
Expertise: Advanced
Style: Concise
Format: Code-first

Custom Instructions:
- examples preferably in Kotlin
- don't explain basic syntax
```

Inspector должен показывать именно Profile, использованный для последнего основного LLM request.

---

# 27. Profile Inspector

Дополнительно в UI можно показывать текущий active profile отдельной компактной панелью.

Например:

```text
ACTIVE PROFILE

Developer

Language: Russian
Expertise: Advanced
Style: Concise
Format: Code-first
```

Это желательно, но основной обязательный диагностический механизм — Effective Context Inspector.

---

# 28. Profile Switching

Переключение Profile не должно:

- очищать Conversation;
- очищать Working Memory;
- очищать Long-Term Memory;
- менять Task;
- делать reset.

Меняется только персонализация следующих LLM requests.

---

# 29. Profile и Tasks независимы

Profile не должен принадлежать Task.

Допускается:

```text
Task A + Developer Profile
Task A + Student Profile

Task B + Developer Profile
Task B + Manager Profile
```

То есть Profile и Task являются независимыми измерениями.

---

# 30. Несколько тестовых профилей

Для ручного тестирования создать минимум три Profiles.

## Developer

```text
Language: Russian
Expertise: Advanced
Style: Concise
Format: Code-first

Custom:
Не объясняй базовый синтаксис.
```

## Student

```text
Language: Russian
Expertise: Beginner
Style: Educational
Format: Step-by-step

Custom:
Объясняй технические термины простыми словами.
```

## Manager

```text
Language: Russian
Expertise: Intermediate
Style: Concise
Format: Structured

Custom:
Фокусируйся на назначении, рисках и последствиях.
Минимизируй implementation details.
```

Не обязательно автоматически создавать эти Profiles в production database.

Они могут использоваться как тестовые данные/documentation.

---

# 31. Тест одинакового запроса

Отправить одинаковый вопрос для каждого Profile:

```text
Объясни, зачем нужен optimistic locking
и покажи небольшой пример.
```

### Developer

Ожидается:

- технический ответ;
- краткость;
- implementation details;
- code-first.

### Student

Ожидается:

- объяснение термина;
- пошаговое объяснение;
- простой пример;
- больше контекста.

### Manager

Ожидается:

- назначение;
- проблема, которую решает механизм;
- последствия;
- минимум деталей реализации.

---

# 32. Test Profile vs Task

Profile:

```text
Developer
custom:
examples in Kotlin
```

Task:

```text
language = Java
```

Request:

```text
Покажи repository для текущего проекта.
```

Ожидается Java.

---

# 33. Test Profile fallback

Task не содержит language.

Profile:

```text
examples in Kotlin
```

Request:

```text
Покажи небольшой REST controller.
```

Ожидается Kotlin.

---

# 34. Test Profile vs Long-Term

Long-Term:

```text
preferred_answer_language = English
```

Profile:

```text
responseLanguage = Russian
```

Request:

```text
Объясни dependency injection.
```

Ожидается русский ответ.

---

# 35. Test Current Message override

Profile:

```text
responseLanguage = Russian
```

Request:

```text
Explain dependency injection in English.
```

Ожидается английский ответ.

---

# 36. Test Profile Switching

Начать Conversation с:

```text
Developer
```

Отправить вопрос.

Переключиться на:

```text
Student
```

Не очищая Conversation, повторить аналогичный вопрос.

Проверить:

- Conversation сохранилась;
- Task сохранилась;
- Memory сохранилась;
- стиль следующего ответа изменился.

---

# 37. Test Persistence

Создать несколько Profiles.

Выбрать:

```text
Developer
```

Перезапустить приложение.

Проверить:

```text
profiles restored
active profile restored
profile settings restored
```

Следующий запрос должен автоматически использовать восстановленный Profile.

---

# 38. Test Profile Editing

Profile:

```text
Style = CONCISE
```

Изменить на:

```text
Style = DETAILED
```

Следующий запрос должен использовать новое значение без restart.

Effective Context Inspector должен показать:

```text
Style: Detailed
```

---

# 39. Test Memory Isolation

Переключение Profile не должно изменять:

```text
SHORT_TERM
WORKING
LONG_TERM
```

Проверить Memory Inspector до и после переключения.

Memory должна остаться идентичной.

---

# 40. Test Task Isolation

Переключение Task не должно менять active Profile.

Например:

```text
Profile = Developer

Task A → Task B
```

После переключения:

```text
Profile = Developer
```

остаётся активным.

---

# 41. Reset Chat

Обычный:

```text
Сбросить чат
```

не должен удалять Profile.

После reset active profile должен остаться выбранным.

---

# 42. Token Statistics

Добавление Profile увеличит фактический input context основной модели.

Не менять существующую семантику token usage.

Provider usage остаётся источником фактических:

```text
inputTokens
outputTokens
totalTokens
```

Profile tokens автоматически попадут в input usage, возвращаемый provider.

Не пытаться отдельно прибавлять их вручную.

---

# 43. Error Handling

Если active Profile не удалось загрузить:

- не падать с NPE;
- использовать application defaults либо отсутствие personalization;
- залогировать проблему.

Если Profile configuration некорректна, REST API должен возвращать понятную validation error.

---

# 44. API

Добавить REST API, соответствующий существующему стилю приложения.

Минимально необходимы операции:

```text
GET profiles
POST profile
PUT profile
select active profile
GET active profile
```

Конкретные URL выбрать согласно существующему API design.

---

# 45. Validation

Минимально проверить:

```text
name != blank
customInstructions имеет разумный maximum size
enum values корректны
```

Не позволять создать очевидно некорректный Profile.

---

# 46. Security

Никогда не включать в Profile:

```text
API keys
provider credentials
секреты приложения
```

Profile является частью LLM context и потенциально отправляется внешнему provider.

Не использовать Profile как storage секретов.

---

# 47. Unit Tests

Добавить unit tests минимум для:

1. создание Profile;
2. изменение Profile;
3. выбор active Profile;
4. восстановление active Profile;
5. Profile добавляется в Effective Context;
6. Profile