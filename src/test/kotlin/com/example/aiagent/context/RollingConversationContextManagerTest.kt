package com.example.aiagent.context

import com.example.aiagent.agent.ChatMessage
import com.example.aiagent.agent.Conversation
import com.example.aiagent.agent.ConversationSummary
import com.example.aiagent.agent.ConversationTokenUsage
import com.example.aiagent.agent.Role
import com.example.aiagent.config.ContextCompressionProperties
import com.example.aiagent.persistence.ConversationRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RollingConversationContextManagerTest {
    private val summarizer = mockk<ConversationSummarizer>()
    private val repository = mockk<ConversationRepository>(relaxed = true)

    @Test
    fun `disabled compression keeps the complete conversation and does not call summarizer`() {
        val conversation = conversationWithMessages(20)
        val manager = manager(properties(enabled = false))

        val context = manager.contextMessages(conversation)
        manager.compressIfNeeded(conversation)

        assertEquals(conversation.messages(), context)
        assertNull(conversation.summary())
        verify(exactly = 0) { summarizer.summarize(any(), any()) }
        verify(exactly = 0) { repository.saveSummary(any()) }
    }

    @Test
    fun `conversation below first threshold is not summarized`() {
        val conversation = conversationWithMessages(18)
        val manager = manager(properties())

        manager.compressIfNeeded(conversation)

        assertNull(conversation.summary())
        verify(exactly = 0) { summarizer.summarize(any(), any()) }
    }

    @Test
    fun `first summary compresses old messages and keeps recent messages verbatim`() {
        val conversation = conversationWithMessages(20)
        val summarizedMessages = slot<List<ChatMessage>>()
        every { summarizer.summarize(null, capture(summarizedMessages)) } returns "Summary v1"
        val manager = manager(properties())

        manager.compressIfNeeded(conversation)

        val expectedSummary = ConversationSummary("Summary v1", 10)
        assertEquals(messageContents(1..10), summarizedMessages.captured.map(ChatMessage::content))
        assertEquals(expectedSummary, conversation.summary())
        assertEquals(20, conversation.messages().size)
        verify(exactly = 1) { repository.saveSummary(expectedSummary) }
        assertEquals(
            listOf(
                ChatMessage(
                    Role.SYSTEM,
                    "${RollingConversationContextManager.SUMMARY_CONTEXT_PREFIX}\nSummary v1",
                ),
            ) + messages(11..20),
            manager.contextMessages(conversation),
        )
    }

    @Test
    fun `rolling update combines existing summary with only the next message chunk`() {
        val existingSummary = ConversationSummary("Summary v1", 10)
        val conversation = conversationWithMessages(30, existingSummary)
        val summarizedMessages = slot<List<ChatMessage>>()
        every {
            summarizer.summarize("Summary v1", capture(summarizedMessages))
        } returns "Summary v2"
        val manager = manager(properties())

        manager.compressIfNeeded(conversation)

        val expectedSummary = ConversationSummary("Summary v2", 20)
        assertEquals(messageContents(11..20), summarizedMessages.captured.map(ChatMessage::content))
        assertEquals(expectedSummary, conversation.summary())
        verify(exactly = 1) { repository.saveSummary(expectedSummary) }
        assertEquals(
            listOf(
                ChatMessage(
                    Role.SYSTEM,
                    "${RollingConversationContextManager.SUMMARY_CONTEXT_PREFIX}\nSummary v2",
                ),
            ) + messages(21..30),
            manager.contextMessages(conversation),
        )
    }

    @Test
    fun `summarizer failure preserves full history existing summary and cursor`() {
        val existingSummary = ConversationSummary("Summary v1", 10)
        val conversation = conversationWithMessages(30, existingSummary)
        every { summarizer.summarize(any(), any()) } throws IllegalStateException("provider unavailable")
        val manager = manager(properties())

        manager.compressIfNeeded(conversation)

        assertEquals(existingSummary, conversation.summary())
        assertEquals(30, conversation.messages().size)
        verify(exactly = 0) { repository.saveSummary(any()) }
    }

    @Test
    fun `summary persistence failure does not advance in-memory cursor`() {
        val existingSummary = ConversationSummary("Summary v1", 10)
        val conversation = conversationWithMessages(30, existingSummary)
        every { summarizer.summarize(any(), any()) } returns "Summary v2"
        every { repository.saveSummary(any()) } throws IllegalStateException("database unavailable")
        val manager = manager(properties())

        manager.compressIfNeeded(conversation)

        assertEquals(existingSummary, conversation.summary())
        assertEquals(30, conversation.messages().size)
    }

    private fun manager(properties: ContextCompressionProperties) =
        RollingConversationContextManager(properties, summarizer, repository)

    private fun properties(enabled: Boolean = true) = ContextCompressionProperties(
        enabled = enabled,
        summarizeAfterMessages = 20,
        summarizeEveryMessages = 10,
        model = "summary-model",
    )

    private fun conversationWithMessages(
        count: Int,
        summary: ConversationSummary? = null,
    ) = Conversation().apply {
        restore(messages(1..count), ConversationTokenUsage.ZERO, summary)
    }

    private fun messages(range: IntRange): List<ChatMessage> = range.map { number ->
        ChatMessage(
            role = if (number % 2 == 1) Role.USER else Role.ASSISTANT,
            content = "Message $number",
        )
    }

    private fun messageContents(range: IntRange): List<String> =
        range.map { number -> "Message $number" }
}
