package com.example.aiagent.context.strategy

import org.springframework.stereotype.Component

interface ContextStrategyResolver {
    fun resolve(type: ContextStrategyType): ContextStrategy
    fun availableTypes(): List<ContextStrategyType>
}

@Component
class DefaultContextStrategyResolver(
    strategies: List<ContextStrategy>,
) : ContextStrategyResolver {
    private val strategiesByType = strategies.associateBy(ContextStrategy::type)

    init {
        require(strategiesByType.size == strategies.size) {
            "Only one ContextStrategy may be registered for each type"
        }
        require(ContextStrategyType.entries.all(strategiesByType::containsKey)) {
            "A ContextStrategy must be registered for every type"
        }
    }

    override fun resolve(type: ContextStrategyType): ContextStrategy =
        checkNotNull(strategiesByType[type]) {
            "No ContextStrategy is registered for $type"
        }

    override fun availableTypes(): List<ContextStrategyType> = ContextStrategyType.entries
}
