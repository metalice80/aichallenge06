package com.example.aiagent.config

import com.example.aiagent.llm.LlmProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.context.properties.bind.validation.BindValidationException
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration

class ContextStrategiesPropertiesTest {
    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(TestConfiguration::class.java)

    @Test
    fun `strategy windows and facts extractor bind independently`() {
        contextRunner
            .withPropertyValues(
                "context.strategies.sliding-window.size=4",
                "context.strategies.sticky-facts.window-size=7",
                "context.strategies.sticky-facts.extractor.provider=OPENROUTER",
                "context.strategies.sticky-facts.extractor.model=openai/facts-model",
                "context.strategies.sticky-facts.extractor.system-prompt=Extract facts",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean(ContextStrategiesProperties::class.java))
                    .isEqualTo(
                        ContextStrategiesProperties(
                            slidingWindow = SlidingWindowStrategyProperties(size = 4),
                            stickyFacts = StickyFactsStrategyProperties(
                                windowSize = 7,
                                extractor = FactsExtractorProperties(
                                    provider = LlmProvider.OPENROUTER,
                                    model = "openai/facts-model",
                                    systemPrompt = "Extract facts",
                                ),
                            ),
                        ),
                    )
            }
    }

    @Test
    fun `sliding window size must be positive`() {
        contextRunner
            .withPropertyValues("context.strategies.sliding-window.size=0")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure)
                    .hasRootCauseInstanceOf(BindValidationException::class.java)
            }
    }

    @Test
    fun `sticky facts window size must be positive`() {
        contextRunner
            .withPropertyValues("context.strategies.sticky-facts.window-size=0")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure)
                    .hasRootCauseInstanceOf(BindValidationException::class.java)
            }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ContextStrategiesProperties::class)
    private class TestConfiguration
}
