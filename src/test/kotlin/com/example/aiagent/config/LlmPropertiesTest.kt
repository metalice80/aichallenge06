package com.example.aiagent.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.validation.BindValidationException
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration

class LlmPropertiesTest {
    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(TestConfiguration::class.java)
        .withPropertyValues(
            "llm.openrouter.base-url=https://openrouter.example/api/v1",
            "llm.openrouter.default-model=openai/gpt-test",
        )

    @Test
    fun `OpenRouter plugins bind in configured order with enabled defaulting to true`() {
        contextRunner
            .withPropertyValues(
                "llm.openrouter.plugins[0].id=context-compression",
                "llm.openrouter.plugins[0].enabled=false",
                "llm.openrouter.plugins[1].id=web",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean(LlmProperties::class.java).openrouter.plugins)
                    .containsExactly(
                        OpenRouterPluginProperties("context-compression", false),
                        OpenRouterPluginProperties("web", true),
                    )
            }
    }

    @Test
    fun `blank OpenRouter plugin id fails configuration binding`() {
        contextRunner
            .withPropertyValues(
                "llm.openrouter.plugins[0].id=",
                "llm.openrouter.plugins[0].enabled=true",
            )
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure)
                    .hasRootCauseInstanceOf(BindValidationException::class.java)
            }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(LlmProperties::class)
    private class TestConfiguration
}
