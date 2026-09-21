package com.example.aiagent.agent

import com.example.aiagent.config.AgentConfiguration
import com.example.aiagent.config.ContextStrategiesProperties
import com.example.aiagent.config.LlmProperties
import com.example.aiagent.config.SlidingWindowStrategyProperties
import com.example.aiagent.context.ContextStateService
import com.example.aiagent.context.EffectiveContextBuilder
import com.example.aiagent.context.branch.ConversationBranchService
import com.example.aiagent.context.strategy.ContextPlan
import com.example.aiagent.context.strategy.ContextStrategy
import com.example.aiagent.context.strategy.ContextStrategyResolver
import com.example.aiagent.context.strategy.ContextStrategyType
import com.example.aiagent.context.strategy.SlidingWindowContextStrategy
import com.example.aiagent.llm.DefaultLlmClientResolver
import com.example.aiagent.llm.LlmClient
import com.example.aiagent.llm.LlmNetworkException
import com.example.aiagent.llm.LlmProvider
import com.example.aiagent.llm.LlmRequest
import com.example.aiagent.llm.LlmResponse
import com.example.aiagent.llm.TokenUsage
import com.example.aiagent.memory.MemoryContext
import com.example.aiagent.memory.EffectiveContext
import com.example.aiagent.memory.MemoryService
import com.example.aiagent.memory.MemoryEntry
import com.example.aiagent.memory.SecretRedactor
import com.example.aiagent.persistence.ConversationRepository
import com.example.aiagent.profile.ExpertiseLevel
import com.example.aiagent.profile.ResponseFormat
import com.example.aiagent.profile.ResponseLanguage
import com.example.aiagent.profile.ResponseStyle
import com.example.aiagent.profile.UserProfile
import com.example.aiagent.profile.UserProfileService
import com.example.aiagent.task.AgentTask
import com.example.aiagent.task.ExpectedActionType
import com.example.aiagent.task.TaskRepository
import com.example.aiagent.task.TaskService
import com.example.aiagent.task.TaskStage
import com.example.aiagent.task.TaskStateCoordinator
import com.example.aiagent.task.TaskStateService
import com.example.aiagent.task.TaskStatus
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.Instant

class ChatAgentTest {
    private val openAiClient = mockk<LlmClient> {
        every { provider } returns LlmProvider.OPENAI
        every { defaultModel } returns "openai-default"
    }
    private val openRouterClient = mockk<LlmClient> {
        every { provider } returns LlmProvider.OPENROUTER
        every { defaultModel } returns "router/default"
    }
    private val resolver = DefaultLlmClientResolver(listOf(openAiClient, openRouterClient))
    private val conversation = Conversation()
    private val conversationRepository = mockk<ConversationRepository>(relaxed = true)
    private val properties = LlmProperties(systemPrompt = "System instruction")
    private val contextStrategy = SlidingWindowContextStrategy(
        ContextStrategiesProperties(slidingWindow = SlidingWindowStrategyProperties(size = 100)),
    )
    private val contextStrategyResolver = mockk<ContextStrategyResolver> {
        every { resolve(any()) } returns contextStrategy
        every { availableTypes() } returns ContextStrategyType.entries
    }
    private val contextStateService = mockk<ContextStateService>(relaxed = true)
    private val branchService = mockk<ConversationBranchService>(relaxed = true)
    private val activeTask = AgentTask(
        id = 1,
        name = "Main Task",
        status = TaskStatus.ACTIVE,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        completedAt = null,
        selected = true,
    )
    private val taskStateMessage = ChatMessage(
        Role.SYSTEM,
        """
        TASK STATE
        Task: Main Task
        Stage: PLANNING
        Current Step: Define goals, requirements, and execution plan
        Expected Action Type: USER_INPUT
        Expected Action Description: Provide goals, requirements, and constraints
        Paused: false
        """.trimIndent(),
    )
    private val taskService = mockk<TaskService> {
        every { activeTask() } returns activeTask
    }
    private val taskStateService = mockk<TaskStateService>(relaxed = true)
    private val taskStateCoordinator = mockk<TaskStateCoordinator>(relaxed = true) {
        every { analyzeBeforeMainRequest(activeTask, any()) } returns activeTask
    }
    private val memoryService = mockk<MemoryService>(relaxed = true) {
        every { context(activeTask) } returns MemoryContext(emptyList(), emptyList())
    }
    private val effectiveContextBuilder = EffectiveContextBuilder(properties, SecretRedactor())
    private val userProfileService = mockk<UserProfileService> {
        every { activeProfile() } returns null
    }
    private val agent = ChatAgent(
        llmClientResolver = resolver,
        conversation = conversation,
        conversationRepository = conversationRepository,
        contextStrategyResolver = contextStrategyResolver,
        contextStateService = contextStateService,
        branchService = branchService,
        taskService = taskService,
        taskStateService = taskStateService,
        taskStateCoordinator = taskStateCoordinator,
        memoryService = memoryService,
        effectiveContextBuilder = effectiveContextBuilder,
        userProfileService = userProfileService,
    )

    @Test
    fun `OpenAI selection sends system prompt user message and chosen model only to OpenAI`() {
        val request = slot<LlmRequest>()
        every { openAiClient.chat(capture(request)) } returns response("Привет!", "gpt-test")

        val result = agent.sendMessage(agentRequest("  Привет  ", model = "gpt-test"))

        assertEquals("gpt-test", request.captured.model)
        assertEquals(
            listOf(
                ChatMessage(Role.SYSTEM, "System instruction"),
                taskStateMessage,
                ChatMessage(Role.USER, "Привет"),
            ),
            request.captured.messages,
        )
        assertEquals(LlmProvider.OPENAI, result.provider)
        verify(exactly = 1) { openAiClient.chat(any()) }
        verify(exactly = 0) { openRouterClient.chat(any()) }
    }

    @Test
    fun `analyzer updates Task before the main request context is built`() {
        val executionTask = activeTask.copy(
            stage = TaskStage.EXECUTION,
            currentStep = "Implement persistence layer",
            expectedActionType = ExpectedActionType.AGENT_ACTION,
            expectedActionDescription = "Implement Room and Booking repositories",
        )
        val userMessage = ChatMessage(Role.USER, "План подтверждаю. Начинай реализацию.")
        every { taskStateCoordinator.analyzeBeforeMainRequest(activeTask, userMessage) } returns executionTask
        every { memoryService.context(executionTask) } returns MemoryContext(emptyList(), emptyList())
        val request = slot<LlmRequest>()
        every { openAiClient.chat(capture(request)) } returns response("Начинаю реализацию.")

        agent.sendMessage(agentRequest(userMessage.content))

        val taskStateContext = request.captured.messages.single { it.content.startsWith("TASK STATE") }
        assertTrue(taskStateContext.content.contains("Stage: EXECUTION"))
        assertTrue(taskStateContext.content.contains("Current Step: Implement persistence layer"))
        assertTrue(taskStateContext.content.contains("Expected Action Type: AGENT_ACTION"))
        verifyOrder {
            taskStateCoordinator.analyzeBeforeMainRequest(activeTask, userMessage)
            openAiClient.chat(any())
        }
    }

    @Test
    fun `OpenRouter selection forwards the chosen model`() {
        val request = slot<LlmRequest>()
        every { openRouterClient.chat(capture(request)) } returns response(
            "Ответ OpenRouter",
            "anthropic/claude-test",
        )

        agent.sendMessage(
            agentRequest(
                message = "Привет",
                provider = LlmProvider.OPENROUTER,
                model = "anthropic/claude-test",
            ),
        )

        assertEquals("anthropic/claude-test", request.captured.model)
        verify(exactly = 1) { openRouterClient.chat(any()) }
        verify(exactly = 0) { openAiClient.chat(any()) }
    }

    @Test
    fun `successful response persists the complete exchange and request usage`() {
        every { openAiClient.chat(any()) } returns response("Привет! Чем могу помочь?")
        val persistedUsage = slot<LlmRequestUsage>()

        agent.sendMessage(agentRequest("Привет"))

        assertEquals(
            listOf(
                ChatMessage(Role.USER, "Привет"),
                ChatMessage(Role.ASSISTANT, "Привет! Чем могу помочь?"),
            ),
            conversation.messages(),
        )
        verify(exactly = 1) { conversationRepository.save(conversation, capture(persistedUsage)) }
        assertEquals(LlmProvider.OPENAI, persistedUsage.captured.provider)
        assertEquals("test-model", persistedUsage.captured.model)
        assertEquals(TokenUsage(10, 5, 15), persistedUsage.captured.tokenUsage)
    }

    @Test
    fun `usage accumulates across providers and models while current usage stays separate`() {
        every { openAiClient.chat(any()) } returns response(
            content = "JVM — это виртуальная машина Java.",
            model = "gpt-test",
            usage = TokenUsage(100, 20, 120),
        )
        val routerRequest = slot<LlmRequest>()
        every { openRouterClient.chat(capture(routerRequest)) } returns response(
            content = "Она исполняет байт-код.",
            model = "google/gemini-test",
            usage = TokenUsage(180, 40, 220),
        )

        val first = agent.sendMessage(agentRequest("Что такое JVM?"))
        val second = agent.sendMessage(
            agentRequest(
                message = "А зачем она нужна?",
                provider = LlmProvider.OPENROUTER,
                model = "google/gemini-test",
            ),
        )

        assertEquals(TokenUsage(100, 20, 120), first.currentUsage)
        assertEquals(ConversationTokenUsage(100, 20, 120), first.conversationUsage)
        assertEquals(TokenUsage(180, 40, 220), second.currentUsage)
        assertEquals(ConversationTokenUsage(280, 60, 340), second.conversationUsage)
        assertEquals(
            listOf(
                ChatMessage(Role.SYSTEM, "System instruction"),
                taskStateMessage,
                ChatMessage(Role.USER, "Что такое JVM?"),
                ChatMessage(Role.ASSISTANT, "JVM — это виртуальная машина Java."),
                ChatMessage(Role.USER, "А зачем она нужна?"),
            ),
            routerRequest.captured.messages,
        )
    }

    @Test
    fun `response statistics use provider reported token usage`() {
        every { openRouterClient.chat(any()) } returns response(
            content = "Ответ",
            model = "openai/gpt-test",
            usage = TokenUsage(21, 8, 29),
        )

        val result = agent.sendMessage(
            agentRequest(
                message = "Вопрос",
                provider = LlmProvider.OPENROUTER,
                model = "openai/gpt-test",
            ),
        )

        assertEquals(LlmProvider.OPENROUTER, result.provider)
        assertEquals("openai/gpt-test", result.model)
        assertEquals(TokenUsage(21, 8, 29), result.currentUsage)
        assertEquals(ConversationTokenUsage(21, 8, 29), result.conversationUsage)
        assertTrue(result.responseTimeMs >= 0)
    }

    @Test
    fun `reset clears messages token usage and persisted state independently of provider`() {
        every { openAiClient.chat(any()) } returns response("Первый ответ")
        agent.sendMessage(agentRequest("Первый вопрос"))

        agent.reset()

        assertTrue(conversation.messages().isEmpty())
        assertEquals(ConversationTokenUsage.ZERO, conversation.tokenUsage())
        assertEquals(ConversationTokenUsage.ZERO, agent.state().conversationUsage)
        verify(exactly = 1) { contextStateService.reset(1) }
    }

    @Test
    fun `failed LLM request leaves messages usage and persistence unchanged`() {
        every { openAiClient.chat(any()) } returns response(
            content = "Сохраненный ответ",
            usage = TokenUsage(100, 20, 120),
        )
        agent.sendMessage(agentRequest("Успешный запрос"))
        every { openRouterClient.chat(any()) } throws LlmNetworkException(
            LlmProvider.OPENROUTER,
            IOException("offline"),
        )

        assertThrows(LlmNetworkException::class.java) {
            agent.sendMessage(
                agentRequest(
                    message = "Повтори запрос",
                    provider = LlmProvider.OPENROUTER,
                    model = "openai/gpt-test",
                ),
            )
        }

        assertEquals(2, conversation.messages().size)
        assertEquals(ConversationTokenUsage(100, 20, 120), agent.state().conversationUsage)
        verify(exactly = 1) { conversationRepository.save(any(), any()) }
    }

    @Test
    fun `history and usage loaded at startup continue on a newly selected provider`() {
        val persistedConversation = Conversation().apply {
            restore(
                restoredMessages = listOf(
                    ChatMessage(Role.USER, "Меня зовут Алексей"),
                    ChatMessage(Role.ASSISTANT, "Приятно познакомиться, Алексей"),
                ),
                restoredTokenUsage = ConversationTokenUsage(90, 10, 100),
                restoredSummary = null,
            )
        }
        val loadingRepository = mockk<ConversationRepository>(relaxed = true)
        every { loadingRepository.load(1) } returns persistedConversation
        val taskRepository = mockk<TaskRepository> {
            every { active() } returns activeTask
        }
        val restoredConversation = AgentConfiguration().conversation(loadingRepository, taskRepository)
        val restoredAgent = ChatAgent(
            llmClientResolver = resolver,
            conversation = restoredConversation,
            conversationRepository = loadingRepository,
            contextStrategyResolver = contextStrategyResolver,
            contextStateService = contextStateService,
            branchService = branchService,
            taskService = taskService,
            taskStateService = taskStateService,
            taskStateCoordinator = taskStateCoordinator,
            memoryService = memoryService,
            effectiveContextBuilder = effectiveContextBuilder,
            userProfileService = userProfileService,
        )
        val request = slot<LlmRequest>()
        every { openRouterClient.chat(capture(request)) } returns response("Вы Алексей", "openai/gpt-test")

        val result = restoredAgent.sendMessage(
            agentRequest(
                message = "Как меня зовут?",
                provider = LlmProvider.OPENROUTER,
                model = "openai/gpt-test",
            ),
        )

        assertEquals(
            listOf(
                ChatMessage(Role.SYSTEM, "System instruction"),
                taskStateMessage,
                ChatMessage(Role.USER, "Меня зовут Алексей"),
                ChatMessage(Role.ASSISTANT, "Приятно познакомиться, Алексей"),
                ChatMessage(Role.USER, "Как меня зовут?"),
            ),
            request.captured.messages,
        )
        assertEquals(ConversationTokenUsage(100, 15, 115), result.conversationUsage)
        verify(exactly = 1) { loadingRepository.load(1) }
    }

    @Test
    fun `persistence failure rolls messages and token usage back`() {
        every { openAiClient.chat(any()) } returns response("Ответ")
        every { conversationRepository.save(any(), any()) } throws IllegalStateException("database unavailable")

        assertThrows(IllegalStateException::class.java) {
            agent.sendMessage(agentRequest("Вопрос"))
        }

        assertTrue(conversation.messages().isEmpty())
        assertEquals(ConversationTokenUsage.ZERO, conversation.tokenUsage())
    }

    @Test
    fun `selected strategy exclusively determines the main LLM context`() {
        val strategyConversation = Conversation().apply {
            restore(
                restoredMessages = listOf(ChatMessage(Role.USER, "Old history")),
                restoredTokenUsage = ConversationTokenUsage.ZERO,
                restoredSummary = ConversationSummary("Must not enter context", 1),
            )
        }
        val selectedStrategy = mockk<ContextStrategy>(relaxed = true) {
            every { type } returns ContextStrategyType.STICKY_FACTS
            every { buildContext(strategyConversation) } returns ContextPlan(
                listOf(
                    ChatMessage(Role.SYSTEM, "Persistent fact: language=Kotlin"),
                    ChatMessage(Role.ASSISTANT, "Recent answer"),
                ),
            )
        }
        val selectedResolver = mockk<ContextStrategyResolver> {
            every { resolve(ContextStrategyType.STICKY_FACTS) } returns selectedStrategy
        }
        val selectedAgent = ChatAgent(
            llmClientResolver = resolver,
            conversation = strategyConversation,
            conversationRepository = conversationRepository,
            contextStrategyResolver = selectedResolver,
            contextStateService = contextStateService,
            branchService = branchService,
            taskService = taskService,
            taskStateService = taskStateService,
            taskStateCoordinator = taskStateCoordinator,
            memoryService = memoryService,
            effectiveContextBuilder = effectiveContextBuilder,
            userProfileService = userProfileService,
        )
        val request = slot<LlmRequest>()
        every { openRouterClient.chat(capture(request)) } returns response("Answer")

        selectedAgent.sendMessage(
            agentRequest(
                message = "New question",
                provider = LlmProvider.OPENROUTER,
                model = "openai/main-model",
                contextStrategy = ContextStrategyType.STICKY_FACTS,
            ),
        )

        assertEquals(
            listOf(
                ChatMessage(Role.SYSTEM, "System instruction"),
                taskStateMessage,
                ChatMessage(Role.SYSTEM, "Persistent fact: language=Kotlin"),
                ChatMessage(Role.ASSISTANT, "Recent answer"),
                ChatMessage(Role.USER, "New question"),
            ),
            request.captured.messages,
        )
        verify(exactly = 1) {
            selectedStrategy.afterSuccessfulExchange(
                strategyConversation,
                ChatMessage(Role.USER, "New question"),
                ChatMessage(Role.ASSISTANT, "Answer"),
            )
        }
    }

    @Test
    fun `switching strategies preserves conversation state and resolves each request independently`() {
        val switchingConversation = Conversation()
        val sliding = mockk<ContextStrategy>(relaxed = true) {
            every { type } returns ContextStrategyType.SLIDING_WINDOW
            every { buildContext(switchingConversation) } returns ContextPlan(emptyList())
        }
        val sticky = mockk<ContextStrategy>(relaxed = true) {
            every { type } returns ContextStrategyType.STICKY_FACTS
            every { buildContext(switchingConversation) } answers {
                ContextPlan(switchingConversation.messages())
            }
        }
        val switchingResolver = mockk<ContextStrategyResolver> {
            every { resolve(ContextStrategyType.SLIDING_WINDOW) } returns sliding
            every { resolve(ContextStrategyType.STICKY_FACTS) } returns sticky
        }
        val switchingAgent = ChatAgent(
            llmClientResolver = resolver,
            conversation = switchingConversation,
            conversationRepository = conversationRepository,
            contextStrategyResolver = switchingResolver,
            contextStateService = contextStateService,
            branchService = branchService,
            taskService = taskService,
            taskStateService = taskStateService,
            taskStateCoordinator = taskStateCoordinator,
            memoryService = memoryService,
            effectiveContextBuilder = effectiveContextBuilder,
            userProfileService = userProfileService,
        )
        val requests = mutableListOf<LlmRequest>()
        every { openAiClient.chat(capture(requests)) } returnsMany listOf(
            response("First answer"),
            response("Second answer"),
        )

        switchingAgent.sendMessage(
            agentRequest("First question", contextStrategy = ContextStrategyType.SLIDING_WINDOW),
        )
        switchingAgent.sendMessage(
            agentRequest("Second question", contextStrategy = ContextStrategyType.STICKY_FACTS),
        )

        assertEquals(4, switchingConversation.messages().size)
        assertEquals(
            listOf(
                ChatMessage(Role.SYSTEM, "System instruction"),
                taskStateMessage,
                ChatMessage(Role.USER, "First question"),
                ChatMessage(Role.ASSISTANT, "First answer"),
                ChatMessage(Role.USER, "Second question"),
            ),
            requests.last().messages,
        )
        verify(exactly = 1) { switchingResolver.resolve(ContextStrategyType.SLIDING_WINDOW) }
        verify(exactly = 1) { switchingResolver.resolve(ContextStrategyType.STICKY_FACTS) }
    }

    @Test
    fun `main request keeps long-term working short-term and current message in priority order`() {
        every { memoryService.context(activeTask) } returns MemoryContext(
            longTerm = listOf(MemoryEntry("preferred_code_language", "Kotlin")),
            working = listOf(MemoryEntry("language", "Java")),
        )
        conversation.addAll(
            listOf(
                ChatMessage(Role.USER, "Old question"),
                ChatMessage(Role.ASSISTANT, "Old answer"),
            ),
        )
        val request = slot<LlmRequest>()
        every { openAiClient.chat(capture(request)) } returns response("Current answer")

        agent.sendMessage(agentRequest("Use Python for this answer"))

        assertEquals("System instruction", request.captured.messages[0].content)
        assertTrue(request.captured.messages[1].content.contains("preferred_code_language = Kotlin"))
        assertTrue(request.captured.messages[1].content.contains("Explicit User Profile"))
        assertTrue(request.captured.messages[2].content.contains("Task \"Main Task\""))
        assertTrue(request.captured.messages[2].content.contains("language = Java"))
        assertEquals(
            listOf(
                ChatMessage(Role.USER, "Old question"),
                ChatMessage(Role.ASSISTANT, "Old answer"),
                ChatMessage(Role.USER, "Use Python for this answer"),
            ),
            request.captured.messages.takeLast(3),
        )
    }

    @Test
    fun `memory layers remain outside effective Branching short-term context`() {
        val branchConversation = Conversation()
        val branching = mockk<ContextStrategy>(relaxed = true) {
            every { type } returns ContextStrategyType.BRANCHING
            every { buildContext(branchConversation) } returns ContextPlan(
                listOf(ChatMessage(Role.USER, "Active branch history")),
            )
        }
        val branchingResolver = mockk<ContextStrategyResolver> {
            every { resolve(ContextStrategyType.BRANCHING) } returns branching
        }
        every { memoryService.context(activeTask) } returns MemoryContext(
            longTerm = listOf(MemoryEntry("answer_language", "Russian")),
            working = listOf(MemoryEntry("database", "PostgreSQL")),
        )
        val branchAgent = ChatAgent(
            llmClientResolver = resolver,
            conversation = branchConversation,
            conversationRepository = conversationRepository,
            contextStrategyResolver = branchingResolver,
            contextStateService = contextStateService,
            branchService = branchService,
            taskService = taskService,
            taskStateService = taskStateService,
            taskStateCoordinator = taskStateCoordinator,
            memoryService = memoryService,
            effectiveContextBuilder = effectiveContextBuilder,
            userProfileService = userProfileService,
        )
        val request = slot<LlmRequest>()
        every { openAiClient.chat(capture(request)) } returns response("Branch answer")

        branchAgent.sendMessage(
            agentRequest("Branch question", contextStrategy = ContextStrategyType.BRANCHING),
        )

        assertEquals("System instruction", request.captured.messages[0].content)
        assertTrue(request.captured.messages[1].content.contains("answer_language = Russian"))
        assertTrue(request.captured.messages[2].content.contains("database = PostgreSQL"))
        assertEquals(
            listOf(
                ChatMessage(Role.USER, "Active branch history"),
                ChatMessage(Role.USER, "Branch question"),
            ),
            request.captured.messages.takeLast(2),
        )
    }

    @Test
    fun `switching active Profile changes next context without clearing Conversation`() {
        val developer = profile(
            id = 1,
            name = "Developer",
            expertise = ExpertiseLevel.ADVANCED,
            style = ResponseStyle.CONCISE,
            format = ResponseFormat.CODE_FIRST,
        )
        val student = profile(
            id = 2,
            name = "Student",
            expertise = ExpertiseLevel.BEGINNER,
            style = ResponseStyle.EDUCATIONAL,
            format = ResponseFormat.STEP_BY_STEP,
        )
        every { userProfileService.activeProfile() } returnsMany listOf(developer, student)
        val requests = mutableListOf<LlmRequest>()
        val diagnostics = mutableListOf<EffectiveContext>()
        every { openAiClient.chat(capture(requests)) } returnsMany listOf(
            response("Developer answer"),
            response("Student answer"),
        )
        every { memoryService.recordEffectiveContext(capture(diagnostics)) } just runs

        agent.sendMessage(agentRequest("Explain optimistic locking"))
        agent.sendMessage(agentRequest("Explain it again"))

        assertTrue(requests[0].messages[1].content.contains("USER PROFILE \"Developer\""))
        assertTrue(requests[1].messages[1].content.contains("USER PROFILE \"Student\""))
        assertEquals("Developer", diagnostics[0].userProfile?.name)
        assertEquals("Student", diagnostics[1].userProfile?.name)
        assertEquals(4, conversation.messages().size)
        verify(exactly = 0) { contextStateService.reset(any()) }
    }


    @Test
    fun `provider options expose configured defaults`() {
        assertEquals(
            listOf(
                LlmProviderOption(LlmProvider.OPENAI, "OpenAI", "openai-default"),
                LlmProviderOption(LlmProvider.OPENROUTER, "OpenRouter", "router/default"),
            ),
            agent.providers(),
        )
    }

    @Test
    fun `blank message is rejected without calling any provider`() {
        assertThrows(InvalidMessageException::class.java) {
            agent.sendMessage(agentRequest("   \n  "))
        }

        verify(exactly = 0) { openAiClient.chat(any()) }
        verify(exactly = 0) { openRouterClient.chat(any()) }
        assertTrue(conversation.messages().isEmpty())
        assertEquals(ConversationTokenUsage.ZERO, conversation.tokenUsage())
    }

    private fun profile(
        id: Long,
        name: String,
        expertise: ExpertiseLevel,
        style: ResponseStyle,
        format: ResponseFormat,
    ) = UserProfile(
        id = id,
        name = name,
        responseLanguage = ResponseLanguage.RUSSIAN,
        expertiseLevel = expertise,
        responseStyle = style,
        responseFormat = format,
        customInstructions = "Profile instructions",
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
        active = true,
    )

    private fun agentRequest(
        message: String,
        provider: LlmProvider = LlmProvider.OPENAI,
        model: String = "gpt-test",
        contextStrategy: ContextStrategyType = ContextStrategyType.SLIDING_WINDOW,
    ) = AgentRequest(message, provider, model, contextStrategy)

    private fun response(
        content: String,
        model: String = "test-model",
        usage: TokenUsage = TokenUsage(10, 5, 15),
    ) = LlmResponse(
        content = content,
        model = model,
        usage = usage,
    )
}
