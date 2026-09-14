package com.example.aiagent.context

import com.example.aiagent.agent.ChatMessage
import com.example.aiagent.agent.Conversation
import com.example.aiagent.agent.Role
import com.example.aiagent.config.ContextStrategiesProperties
import com.example.aiagent.config.SlidingWindowStrategyProperties
import com.example.aiagent.config.StickyFactsStrategyProperties
import com.example.aiagent.context.branch.ConversationBranchService
import com.example.aiagent.context.facts.FactsExtractor
import com.example.aiagent.context.facts.FactsUpdate
import com.example.aiagent.context.facts.MemoryFact
import com.example.aiagent.context.facts.MemoryFactRepository
import com.example.aiagent.context.strategy.BranchingContextStrategy
import com.example.aiagent.context.strategy.ContextPlan
import com.example.aiagent.context.strategy.ContextStrategy
import com.example.aiagent.context.strategy.ContextStrategyType
import com.example.aiagent.context.strategy.DefaultContextStrategyResolver
import com.example.aiagent.context.strategy.SlidingWindowContextStrategy
import com.example.aiagent.context.strategy.StickyFactsContextStrategy
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ContextStrategyTest {
    @Test
    fun `resolver returns exactly the configured implementation for every strategy type`() {
        val strategies = ContextStrategyType.entries.map(::FixedStrategy)
        val resolver = DefaultContextStrategyResolver(strategies)

        ContextStrategyType.entries.forEachIndexed { index, type ->
            assertSame(strategies[index], resolver.resolve(type))
        }
        assertEquals(ContextStrategyType.entries, resolver.availableTypes())
    }

    @Test
    fun `resolver rejects a missing strategy implementation`() {
        val strategies = listOf(
            FixedStrategy(ContextStrategyType.SLIDING_WINDOW),
            FixedStrategy(ContextStrategyType.STICKY_FACTS),
        )

        assertThrows(IllegalArgumentException::class.java) {
            DefaultContextStrategyResolver(strategies)
        }
    }

    @Test
    fun `sliding window sends only the last four of six existing messages`() {
        val conversation = conversationWithSixMessages()
        val strategy = SlidingWindowContextStrategy(
            ContextStrategiesProperties(slidingWindow = SlidingWindowStrategyProperties(size = 4)),
        )

        val context = strategy.buildContext(conversation).contextMessages

        assertEquals(conversation.messages().takeLast(4), context)
    }

    @Test
    fun `sticky facts prepends persistent facts and keeps only its recent window`() {
        val factsRepository = mockk<MemoryFactRepository> {
            every { findAll() } returns listOf(
                MemoryFact("language", "Kotlin"),
                MemoryFact("database", "SQLite"),
            )
        }
        val strategy = StickyFactsContextStrategy(
            ContextStrategiesProperties(
                stickyFacts = StickyFactsStrategyProperties(windowSize = 2),
            ),
            factsRepository,
            mockk(relaxed = true),
        )
        val conversation = conversationWithSixMessages()

        val context = strategy.buildContext(conversation).contextMessages

        assertEquals(
            listOf(
                ChatMessage(
                    Role.SYSTEM,
                    "Persistent facts from the conversation:\n- language: Kotlin\n- database: SQLite",
                ),
            ) + conversation.messages().takeLast(2),
            context,
        )
    }

    @Test
    fun `sticky facts applies extractor changes only after a successful exchange`() {
        val update = FactsUpdate(
            upsert = listOf(MemoryFact("language", "Kotlin")),
            deleteKeys = listOf("obsolete"),
        )
        val factsRepository = mockk<MemoryFactRepository>(relaxed = true) {
            every { findAll() } returns emptyList()
        }
        val extractor = mockk<FactsExtractor> {
            every { extract(emptyList(), ChatMessage(Role.USER, "I use Kotlin")) } returns update
        }
        val strategy = StickyFactsContextStrategy(
            ContextStrategiesProperties(),
            factsRepository,
            extractor,
        )

        strategy.afterSuccessfulExchange(
            ChatMessage(Role.USER, "I use Kotlin"),
            ChatMessage(Role.ASSISTANT, "Noted"),
        )

        verify(exactly = 1) { factsRepository.apply(update) }
    }

    @Test
    fun `sticky facts extraction failure preserves the existing facts`() {
        val existing = listOf(MemoryFact("language", "Kotlin"))
        val factsRepository = mockk<MemoryFactRepository>(relaxed = true) {
            every { findAll() } returns existing
        }
        val extractor = mockk<FactsExtractor> {
            every { extract(existing, any()) } throws IllegalStateException("extractor unavailable")
        }
        val strategy = StickyFactsContextStrategy(
            ContextStrategiesProperties(),
            factsRepository,
            extractor,
        )

        strategy.afterSuccessfulExchange(
            ChatMessage(Role.USER, "New preference"),
            ChatMessage(Role.ASSISTANT, "Noted"),
        )

        verify(exactly = 0) { factsRepository.apply(any()) }
    }

    @Test
    fun `branching strategy uses active effective history and persists only the completed exchange`() {
        val seedConversation = conversationWithSixMessages()
        val activeHistory = seedConversation.messages().take(4)
        val branchService = mockk<ConversationBranchService>(relaxed = true) {
            every { activeHistory(seedConversation.messages()) } returns activeHistory
        }
        val strategy = BranchingContextStrategy(branchService)
        val user = ChatMessage(Role.USER, "Branch question")
        val assistant = ChatMessage(Role.ASSISTANT, "Branch answer")

        val context = strategy.buildContext(seedConversation)
        strategy.afterSuccessfulExchange(user, assistant)

        assertEquals(activeHistory, context.contextMessages)
        verify(exactly = 1) { branchService.appendToActive(listOf(user, assistant)) }
    }

    private fun conversationWithSixMessages() = Conversation().apply {
        addAll(
            (1..6).map { number ->
                ChatMessage(
                    role = if (number % 2 == 1) Role.USER else Role.ASSISTANT,
                    content = "Message $number",
                )
            },
        )
    }

    private class FixedStrategy(
        override val type: ContextStrategyType,
    ) : ContextStrategy {
        override fun buildContext(conversation: Conversation) = ContextPlan(emptyList())
    }
}
