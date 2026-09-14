package com.example.aiagent.context.strategy

import com.example.aiagent.agent.Conversation
import com.example.aiagent.config.ContextStrategiesProperties
import org.springframework.stereotype.Component

@Component
class SlidingWindowContextStrategy(
    properties: ContextStrategiesProperties,
) : ContextStrategy {
    override val type = ContextStrategyType.SLIDING_WINDOW
    private val windowSize = properties.slidingWindow.size

    override fun buildContext(conversation: Conversation): ContextPlan =
        ContextPlan(
            contextMessages = conversation.messages().takeLast(windowSize),
        )
}
