package com.example.aiagent.invariant

import com.example.aiagent.agent.LlmRequestPurpose
import com.example.aiagent.config.InvariantGuardProperties
import com.example.aiagent.config.InvariantProperties
import com.example.aiagent.llm.LlmProvider
import com.example.aiagent.llm.TokenUsage
import com.example.aiagent.memory.SecretRedactor
import com.example.aiagent.persistence.ConversationRepository
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class InvariantGuardServiceTest {
    private val analyzer = mockk<InvariantConflictAnalyzer>()
    private val invariantService = mockk<TaskInvariantService>(relaxed = true)
    private val conversationRepository = mockk<ConversationRepository>(relaxed = true)
    private val enabledProperties = InvariantProperties(
        InvariantGuardProperties(
            enabled = true,
            provider = LlmProvider.OPENROUTER,
            model = "guard-test",
            maxCorrectiveRetries = 1,
        ),
    )
    private val protectedSet = TaskInvariantSet(
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
                description = "Backend remains Kotlin",
            ),
        ),
    )

    @Test
    fun `no active invariants bypass semantic analyzer and internal usage`() {
        val service = service(enabledProperties)
        val emptySet = TaskInvariantSet.empty(7, "Booking Service")

        val evaluation = service.evaluateInput("Continue", emptySet)

        assertEquals(InvariantCheckDecision.ALLOWED, evaluation.result.decision)
        assertEquals(InvariantCheckStatus.NO_ACTIVE_INVARIANTS, evaluation.status)
        assertNull(evaluation.analysis)
        verify(exactly = 0) { analyzer.analyze(any(), any(), any(), any()) }
        verify(exactly = 0) { conversationRepository.recordUsage(any(), any()) }
    }

    @Test
    fun `disabled guard preserves context constraints but bypasses semantic analyzer`() {
        val service = service(
            InvariantProperties(enabledProperties.guard.copy(enabled = false)),
        )

        val evaluation = service.evaluateOutput("Request", "Candidate", protectedSet)

        assertEquals(InvariantCheckDecision.ALLOWED, evaluation.result.decision)
        assertEquals(InvariantCheckStatus.GUARD_DISABLED_BY_CONFIGURATION, evaluation.status)
        assertEquals(protectedSet, evaluation.invariantSet)
        verify(exactly = 0) { analyzer.analyze(any(), any(), any(), any()) }
        verify(exactly = 0) { conversationRepository.recordUsage(any(), any()) }
    }

    @Test
    fun `input and output analyzer usage is stored under separate purposes`() {
        every {
            analyzer.analyze(InvariantCheckDirection.INPUT, "Request", null, protectedSet)
        } returns analysis(InvariantCheckDirection.INPUT)
        every {
            analyzer.analyze(InvariantCheckDirection.OUTPUT, "Request", "Candidate", protectedSet)
        } returns analysis(InvariantCheckDirection.OUTPUT)
        val service = service(enabledProperties)

        service.evaluateInput("Request", protectedSet)
        service.evaluateOutput("Request", "Candidate", protectedSet)

        verify(exactly = 1) {
            conversationRepository.recordUsage(
                7,
                match { it.purpose == LlmRequestPurpose.INVARIANT_INPUT_GUARD && it.tokenUsage.totalTokens == 15L },
            )
        }
        verify(exactly = 1) {
            conversationRepository.recordUsage(
                7,
                match { it.purpose == LlmRequestPurpose.INVARIANT_OUTPUT_GUARD && it.tokenUsage.totalTokens == 15L },
            )
        }
    }

    @Test
    fun `unexpected analyzer failure is typed and fail closed`() {
        every { analyzer.analyze(any(), any(), any(), any()) } throws IllegalStateException("unavailable")
        val service = service(enabledProperties)

        val exception = assertThrows(InvariantGuardException::class.java) {
            service.evaluateInput("Request", protectedSet)
        }

        assertEquals("IllegalStateException", exception.code)
        verify(exactly = 0) { conversationRepository.recordUsage(any(), any()) }
    }

    @Test
    fun `invalid analyzed response retains available internal usage in failure diagnostics`() {
        val failure = InvariantGuardException(
            code = "INVALID_STRUCTURED_RESULT",
            provider = LlmProvider.OPENROUTER,
            model = "guard-test",
            usage = TokenUsage(12, 3, 15),
            responseTimeMs = 45,
        )
        every { analyzer.analyze(any(), any(), any(), any()) } throws failure
        val captured = slot<LastInvariantCheck>()
        every { invariantService.recordCheck(capture(captured)) } just runs
        val service = service(enabledProperties)

        val thrown = assertThrows(InvariantGuardException::class.java) {
            service.evaluateInput("Request", protectedSet)
        }
        service.recordFailure(
            protectedSet,
            InvariantCheckDirection.INPUT,
            "Request",
            correctiveRetries = 0,
            outcome = InvariantCheckOutcome.REFUSED,
            failure = thrown,
        )

        verify(exactly = 1) {
            conversationRepository.recordUsage(
                7,
                match {
                    it.purpose == LlmRequestPurpose.INVARIANT_INPUT_GUARD &&
                        it.tokenUsage == TokenUsage(12, 3, 15)
                },
            )
        }
        assertEquals("INVALID_STRUCTURED_RESULT", captured.captured.errorCode)
        assertEquals(TokenUsage(12, 3, 15), captured.captured.usage)
        assertEquals(45, captured.captured.responseTimeMs)
    }

    @Test
    fun `last check keeps immutable violated snapshot while redacting request secrets`() {
        val captured = slot<LastInvariantCheck>()
        every { invariantService.recordCheck(capture(captured)) } just runs
        val service = service(enabledProperties)
        val evaluation = InvariantGuardEvaluation(
            invariantSet = protectedSet,
            result = InvariantCheckResult(
                InvariantCheckDecision.BLOCKED,
                InvariantCheckDirection.INPUT,
                listOf(InvariantViolation(13, "Java conflicts with Kotlin")),
            ),
            analysis = analysis(
                direction = InvariantCheckDirection.INPUT,
                decision = InvariantCheckDecision.BLOCKED,
            ),
            status = InvariantCheckStatus.SEMANTIC_CONFLICT,
        )

        service.record(
            evaluation,
            "api_key=secret-value-123456 switch to Java",
            correctiveRetries = 0,
            outcome = InvariantCheckOutcome.REFUSED,
        )

        assertEquals(protectedSet.invariants, captured.captured.invariantSnapshot)
        assertEquals(protectedSet.invariants.single(), captured.captured.violations.single().invariant)
        assertEquals("api_key=[REDACTED] switch to Java", captured.captured.requestExcerpt)
        assertNull(captured.captured.errorCode)
    }

    private fun service(properties: InvariantProperties) = InvariantGuardService(
        properties,
        analyzer,
        invariantService,
        conversationRepository,
        SecretRedactor(),
    )

    private fun analysis(
        direction: InvariantCheckDirection,
        decision: InvariantCheckDecision = InvariantCheckDecision.ALLOWED,
    ) = InvariantAnalysis(
        result = InvariantCheckResult(
            decision,
            direction,
            if (decision == InvariantCheckDecision.BLOCKED) {
                listOf(InvariantViolation(13, "Java conflicts with Kotlin"))
            } else {
                emptyList()
            },
        ),
        provider = LlmProvider.OPENROUTER,
        model = "guard-test",
        usage = TokenUsage(10, 5, 15),
        responseTimeMs = 30,
    )
}
