package com.example.aiagent.config

import com.example.aiagent.llm.LlmProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.context.properties.bind.validation.BindValidationException
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration

class TaskStatePropertiesTest {
    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(TestConfiguration::class.java)

    @Test
    fun `Task analyzer provider and model bind independently`() {
        contextRunner
            .withPropertyValues(
                "task.state.analyzer.enabled=true",
                "task.state.analyzer.provider=OPENROUTER",
                "task.state.analyzer.model=openai/task-model",
                "task.state.analyzer.system-prompt=Analyze task progress",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean(TaskStateProperties::class.java)).isEqualTo(
                    TaskStateProperties(
                        TaskProgressAnalyzerProperties(
                            enabled = true,
                            provider = LlmProvider.OPENROUTER,
                            model = "openai/task-model",
                            systemPrompt = "Analyze task progress",
                        ),
                    ),
                )
            }
    }

    @Test
    fun `Task analyzer is enabled by default for chat driven progress`() {
        contextRunner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.getBean(TaskStateProperties::class.java).analyzer.enabled).isTrue()
        }
    }

    @Test
    fun `Task analyzer model must not be blank`() {
        contextRunner
            .withPropertyValues("task.state.analyzer.model=   ")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure)
                    .hasRootCauseInstanceOf(BindValidationException::class.java)
            }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(TaskStateProperties::class)
    private class TestConfiguration
}
