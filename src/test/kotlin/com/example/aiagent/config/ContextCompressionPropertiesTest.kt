package com.example.aiagent.config

import com.example.aiagent.llm.LlmProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.context.properties.bind.validation.BindValidationException
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration

class ContextCompressionPropertiesTest {
    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(TestConfiguration::class.java)

    @Test
    fun `compression thresholds provider model and prompt bind from configuration`() {
        contextRunner
            .withPropertyValues(
                "context.compression.enabled=true",
                "context.compression.summarize-after-messages=30",
                "context.compression.summarize-every-messages=8",
                "context.compression.provider=OPENROUTER",
                "context.compression.model=openai/gpt-summary",
                "context.compression.system-prompt=Create summary",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean(ContextCompressionProperties::class.java))
                    .isEqualTo(
                        ContextCompressionProperties(
                            enabled = true,
                            summarizeAfterMessages = 30,
                            summarizeEveryMessages = 8,
                            provider = LlmProvider.OPENROUTER,
                            model = "openai/gpt-summary",
                            systemPrompt = "Create summary",
                        ),
                    )
            }
    }

    @Test
    fun `compression can be disabled while retaining valid defaults`() {
        contextRunner
            .withPropertyValues("context.compression.enabled=false")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean(ContextCompressionProperties::class.java).enabled).isFalse()
            }
    }

    @Test
    fun `first threshold must be greater than rolling chunk size`() {
        contextRunner
            .withPropertyValues(
                "context.compression.summarize-after-messages=10",
                "context.compression.summarize-every-messages=10",
            )
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure)
                    .hasRootCauseInstanceOf(BindValidationException::class.java)
            }
    }

    @Test
    fun `summary model must not be blank`() {
        contextRunner
            .withPropertyValues("context.compression.model=")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure)
                    .hasRootCauseInstanceOf(BindValidationException::class.java)
            }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ContextCompressionProperties::class)
    private class TestConfiguration
}
