package com.example.aiagent.config

import com.example.aiagent.llm.LlmProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.context.properties.bind.validation.BindValidationException
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration

class MemoryPropertiesTest {
    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(TestConfiguration::class.java)

    @Test
    fun `memory extractor provider model and prompt bind independently from main chat`() {
        contextRunner
            .withPropertyValues(
                "memory.enabled=true",
                "memory.extractor.provider=OPENROUTER",
                "memory.extractor.model=openai/memory-model",
                "memory.extractor.system-prompt=Classify memory layers",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean(MemoryProperties::class.java)).isEqualTo(
                    MemoryProperties(
                        enabled = true,
                        extractor = MemoryExtractorProperties(
                            provider = LlmProvider.OPENROUTER,
                            model = "openai/memory-model",
                            systemPrompt = "Classify memory layers",
                        ),
                    ),
                )
            }
    }

    @Test
    fun `memory extractor model must not be blank`() {
        contextRunner
            .withPropertyValues("memory.extractor.model=   ")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure)
                    .hasRootCauseInstanceOf(BindValidationException::class.java)
            }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MemoryProperties::class)
    private class TestConfiguration
}
