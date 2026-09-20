package com.example.aiagent.context

import com.example.aiagent.agent.ChatMessage
import com.example.aiagent.agent.Conversation
import com.example.aiagent.agent.ConversationSummary
import com.example.aiagent.agent.Role
import com.example.aiagent.config.ContextCompressionProperties
import com.example.aiagent.persistence.ConversationRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

interface ConversationContextManager {
    fun contextMessages(conversation: Conversation): List<ChatMessage>
    fun compressIfNeeded(conversation: Conversation)
}

@Component
class RollingConversationContextManager(
    private val properties: ContextCompressionProperties,
    private val summarizer: ConversationSummarizer,
    private val conversationRepository: ConversationRepository,
) : ConversationContextManager {
    override fun contextMessages(conversation: Conversation): List<ChatMessage> {
        val messages = conversation.messages()
        if (!properties.enabled) {
            return messages
        }
        val summary = conversation.summary() ?: return messages

        return buildList {
            add(
                ChatMessage(
                    Role.SYSTEM,
                    "$SUMMARY_CONTEXT_PREFIX\n${summary.content}",
                ),
            )
            addAll(messages.drop(summary.summarizedMessageCount))
        }
    }

    override fun compressIfNeeded(conversation: Conversation) {
        if (!properties.enabled) {
            return
        }

        val messages = conversation.messages()
        val currentSummary = conversation.summary()
        val range = compressionRange(messages.size, currentSummary) ?: return
        val messagesToSummarize = messages.subList(range.first, range.last + 1)
        if (currentSummary == null) {
            logger.info("Creating initial conversation summary for {} messages", messagesToSummarize.size)
        } else {
            logger.info("Updating conversation summary with {} new messages", messagesToSummarize.size)
        }

        val updatedContent = try {
            summarizer.summarize(currentSummary?.content, messagesToSummarize)
        } catch (exception: RuntimeException) {
            logger.warn(
                "Conversation summarization failed; summary and compression cursor remain unchanged",
                exception,
            )
            return
        }
        val updatedSummary = ConversationSummary(
            content = updatedContent,
            summarizedMessageCount = range.last + 1,
        )
        try {
            conversationRepository.saveSummary(conversation.taskId, updatedSummary)
        } catch (exception: RuntimeException) {
            logger.warn(
                "Conversation summary persistence failed; summary and compression cursor remain unchanged",
                exception,
            )
            return
        }
        conversation.updateSummary(updatedSummary)
        logger.info(
            "Conversation summary updated, summarizedMessageCount={}, summary={}",
            updatedSummary.summarizedMessageCount,
            updatedSummary.content,
        )
    }

    private fun compressionRange(
        messageCount: Int,
        currentSummary: ConversationSummary?,
    ): IntRange? {
        if (currentSummary == null) {
            if (messageCount < properties.summarizeAfterMessages) {
                return null
            }
            return 0 until (messageCount - properties.summarizeEveryMessages)
        }

        val unsummarizedCount = messageCount - currentSummary.summarizedMessageCount
        if (unsummarizedCount.toLong() < properties.summarizeEveryMessages.toLong() * 2) {
            return null
        }
        return currentSummary.summarizedMessageCount until
            (currentSummary.summarizedMessageCount + properties.summarizeEveryMessages)
    }

    companion object {
        const val SUMMARY_CONTEXT_PREFIX = "Краткое содержание предыдущего разговора:"
        private val logger = LoggerFactory.getLogger(RollingConversationContextManager::class.java)
    }
}
