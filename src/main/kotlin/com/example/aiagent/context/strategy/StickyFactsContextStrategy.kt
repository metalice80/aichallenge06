package com.example.aiagent.context.strategy

import com.example.aiagent.agent.ChatMessage
import com.example.aiagent.agent.Conversation
import com.example.aiagent.agent.Role
import com.example.aiagent.config.ContextStrategiesProperties
import com.example.aiagent.context.facts.FactsExtractor
import com.example.aiagent.context.facts.MemoryFact
import com.example.aiagent.context.facts.MemoryFactRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class StickyFactsContextStrategy(
    properties: ContextStrategiesProperties,
    private val factsRepository: MemoryFactRepository,
    private val factsExtractor: FactsExtractor,
) : ContextStrategy {
    override val type = ContextStrategyType.STICKY_FACTS
    private val windowSize = properties.stickyFacts.windowSize

    override fun buildContext(conversation: Conversation): ContextPlan {
        val history = conversation.messages()
        val facts = factsRepository.findAll()
        return ContextPlan(
            contextMessages = buildList {
                if (facts.isNotEmpty()) {
                    add(ChatMessage(Role.SYSTEM, formatFacts(facts)))
                }
                addAll(history.takeLast(windowSize))
            },
        )
    }

    override fun afterSuccessfulExchange(
        userMessage: ChatMessage,
        assistantMessage: ChatMessage,
    ) {
        try {
            val update = factsExtractor.extract(factsRepository.findAll(), userMessage)
            factsRepository.apply(update)
        } catch (exception: RuntimeException) {
            logger.warn("Sticky Facts extraction failed; existing facts remain unchanged", exception)
        }
    }

    private fun formatFacts(facts: List<MemoryFact>): String = buildString {
        appendLine(FACTS_CONTEXT_PREFIX)
        facts.forEach { fact -> appendLine("- ${fact.key}: ${fact.value}") }
    }.trimEnd()

    companion object {
        const val FACTS_CONTEXT_PREFIX = "Persistent facts from the conversation:"
        private val logger = LoggerFactory.getLogger(StickyFactsContextStrategy::class.java)
    }
}
