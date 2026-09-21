package com.example.aiagent.config

import com.example.aiagent.llm.LlmProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.context.properties.bind.validation.BindValidationException
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration

class InvariantPropertiesTest {
    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(TestConfiguration::class.java)

    @Test
    fun `guard defaults are enabled and independent from main model`() {
        contextRunner.run { context ->
            assertThat(context).hasNotFailed()
            val guard = context.getBean(InvariantProperties::class.java).guard
            assertThat(guard.enabled).isTrue()
            assertThat(guard.provider).isEqualTo(LlmProvider.OPENAI)
            assertThat(guard.model).isEqualTo("gpt-5-nano")
            assertThat(guard.maxCorrectiveRetries).isEqualTo(1)
        }
    }

    @Test
    fun `guard provider model and retry count bind independently`() {
        contextRunner.withPropertyValues(
            "invariants.guard.provider=OPENROUTER",
            "invariants.guard.model=openai/guard-model",
            "invariants.guard.max-corrective-retries=0",
        ).run { context ->
            assertThat(context).hasNotFailed()
            val guard = context.getBean(InvariantProperties::class.java).guard
            assertThat(guard.provider).isEqualTo(LlmProvider.OPENROUTER)
            assertThat(guard.model).isEqualTo("openai/guard-model")
            assertThat(guard.maxCorrectiveRetries).isZero()
        }
    }

    @Test
    fun `more than one corrective retry is rejected`() {
        contextRunner.withPropertyValues("invariants.guard.max-corrective-retries=2")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure)
                    .hasRootCauseInstanceOf(BindValidationException::class.java)
            }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(InvariantProperties::class)
    private class TestConfiguration
}
