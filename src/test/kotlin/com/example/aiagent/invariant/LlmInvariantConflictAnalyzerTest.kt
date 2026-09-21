package com.example.aiagent.invariant

import com.example.aiagent.config.InvariantGuardProperties
import com.example.aiagent.config.InvariantProperties
import com.example.aiagent.llm.LlmClient
import com.example.aiagent.llm.LlmClientResolver
import com.example.aiagent.llm.LlmNetworkException
import com.example.aiagent.llm.LlmProvider
import com.example.aiagent.llm.LlmResponse
import com.example.aiagent.llm.StructuredOutputParser
import com.example.aiagent.llm.TokenUsage
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper

class LlmInvariantConflictAnalyzerTest {
    private val client = mockk<LlmClient>()
    private val resolver = mockk<LlmClientResolver> {
        every { resolve(LlmProvider.OPENAI) } returns client
    }
    private val analyzer = LlmInvariantConflictAnalyzer(
        resolver,
        InvariantProperties(
            InvariantGuardProperties(provider = LlmProvider.OPENAI, model = "guard-model"),
        ),
        StructuredOutputParser(jacksonObjectMapper()),
    )
    private val invariantSet = TaskInvariantSet(
        taskId = 7,
        taskName = "Booking Service",
        invariants = listOf(
            TaskInvariantSnapshot(
                id = 13,
                taskId = 7,
                taskName = "Booking Service",
                type = InvariantType.STACK_CONSTRAINT,
                key = "backend_language",
                value = "Kotlin",
                description = null,
            ),
        ),
    )

    @Test
    fun `valid educational ALLOWED result is accepted`() {
        every { client.chat(any()) } returns response(
            """{"decision":"ALLOWED","direction":"INPUT","violations":[]}""",
        )

        val analysis = analyzer.analyze(
            InvariantCheckDirection.INPUT,
            "Чем Kotlin отличается от Java без изменения проекта?",
            null,
            invariantSet,
        )

        assertEquals(InvariantCheckDecision.ALLOWED, analysis.result.decision)
        assertTrue(analysis.result.violations.isEmpty())
        assertEquals(TokenUsage(11, 4, 15), analysis.usage)
    }

    @Test
    fun `valid project-changing BLOCKED result names enabled invariant`() {
        every { client.chat(any()) } returns response(
            """{"decision":"BLOCKED","direction":"INPUT","violations":[{"invariantId":13,"reason":"Requests Java for the active backend."}]}""",
        )

        val result = analyzer.analyze(
            InvariantCheckDirection.INPUT,
            "Перепиши backend на Java",
            null,
            invariantSet,
        ).result

        assertEquals(InvariantCheckDecision.BLOCKED, result.decision)
        assertEquals(13, result.violations.single().invariantId)
    }

    @Test
    fun `invalid contradictory unknown and empty results fail closed`() {
        val invalidResults = listOf(
            "not-json",
            """{"decision":"ALLOWED","direction":"INPUT","violations":[{"invariantId":13,"reason":"conflict"}]}""",
            """{"decision":"BLOCKED","direction":"INPUT","violations":[]}""",
            """{"decision":"BLOCKED","direction":"INPUT","violations":[{"invariantId":999,"reason":"conflict"}]}""",
            """{"decision":"BLOCKED","direction":"INPUT","violations":[{"invariantId":13,"reason":"   "}]}""",
            """{"decision":"ALLOWED","direction":"OUTPUT","violations":[]}""",
            """{"decision":"ALLOWED","direction":"INPUT","violations":[],"unknown":true}""",
        )

        invalidResults.forEach { content ->
            every { client.chat(any()) } returns response(content)
            assertThrows(InvariantGuardException::class.java) {
                analyzer.analyze(InvariantCheckDirection.INPUT, "request", null, invariantSet)
            }
        }
    }

    @Test
    fun `provider failure is never converted to allowed`() {
        every { client.chat(any()) } throws LlmNetworkException(LlmProvider.OPENAI, java.io.IOException("offline"))

        assertThrows(LlmNetworkException::class.java) {
            analyzer.analyze(InvariantCheckDirection.INPUT, "request", null, invariantSet)
        }
    }

    private fun response(content: String) = LlmResponse(
        content = content,
        model = "guard-model",
        usage = TokenUsage(11, 4, 15),
    )
}
