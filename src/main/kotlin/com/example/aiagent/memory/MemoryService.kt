package com.example.aiagent.memory

import com.example.aiagent.agent.ChatMessage
import com.example.aiagent.agent.Role
import com.example.aiagent.config.MemoryProperties
import com.example.aiagent.context.strategy.ContextPlan
import com.example.aiagent.context.strategy.ContextStrategyType
import com.example.aiagent.task.AgentTask
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class MemoryService(
    private val repository: MemoryRepository,
    private val extractor: MemoryExtractor,
    private val properties: MemoryProperties,
    private val secretRedactor: SecretRedactor,
) {
    fun working(taskId: Long): List<MemoryEntry> = repository.findWorking(taskId)

    fun longTerm(): List<MemoryEntry> = repository.findLongTerm()

    fun context(task: AgentTask): MemoryContext {
        if (!properties.enabled) {
            return MemoryContext(emptyList(), emptyList(), emptyList())
        }
        val longTerm = repository.findLongTerm()
        val working = repository.findWorking(task.id)
        val messages = buildList {
            if (longTerm.isNotEmpty()) {
                add(ChatMessage(Role.SYSTEM, formatLongTerm(longTerm)))
            }
            if (working.isNotEmpty()) {
                add(ChatMessage(Role.SYSTEM, formatWorking(task, working)))
            }
        }
        return MemoryContext(longTerm, working, messages)
    }

    fun extractAfterSuccessfulExchange(task: AgentTask, userMessage: ChatMessage) {
        if (!properties.enabled) {
            return
        }
        try {
            val update = extractor.extract(
                userMessage = userMessage,
                task = task,
                currentWorkingMemory = repository.findWorking(task.id),
                currentLongTermMemory = repository.findLongTerm(),
            )
            repository.apply(task.id, userMessage.content, update)
        } catch (exception: RuntimeException) {
            logger.warn("Memory extraction failed; existing memory remains unchanged", exception)
            try {
                repository.recordFailure(
                    task.id,
                    userMessage.content,
                    exception.javaClass.simpleName,
                )
            } catch (persistenceException: RuntimeException) {
                logger.warn("Could not persist failed memory extraction diagnostics", persistenceException)
            }
        }
    }

    fun recordEffectiveContext(
        task: AgentTask,
        strategy: ContextStrategyType,
        systemPrompt: String,
        memoryContext: MemoryContext,
        contextPlan: ContextPlan,
        currentUserMessage: ChatMessage,
    ) {
        repository.saveEffectiveContext(
            EffectiveContext(
                taskId = task.id,
                strategy = strategy,
                systemPrompt = secretRedactor.redact(systemPrompt),
                longTermMemory = memoryContext.longTerm.map(::redact),
                workingMemory = memoryContext.working.map(::redact),
                shortTerm = contextPlan.contextMessages.map(::redact),
                currentUserMessage = redact(currentUserMessage),
                preparedAt = Instant.now(),
            ),
        )
    }

    fun inspector(taskId: Long, effectiveShortTerm: List<ChatMessage>): MemoryInspector =
        MemoryInspector(
            shortTerm = effectiveShortTerm,
            working = repository.findWorking(taskId),
            longTerm = repository.findLongTerm(),
            lastUpdate = repository.lastUpdate(taskId),
            effectiveContext = repository.effectiveContext(taskId),
        )

    fun clearWorking(taskId: Long) = repository.clearWorking(taskId)

    fun clearLongTerm() = repository.clearLongTerm()

    private fun redact(entry: MemoryEntry) = entry.copy(value = secretRedactor.redact(entry.value))

    private fun redact(message: ChatMessage) = message.copy(content = secretRedactor.redact(message.content))

    private fun formatLongTerm(entries: List<MemoryEntry>): String = buildString {
        appendLine("LONG-TERM MEMORY (global; lowest memory priority):")
        entries.forEach { entry -> appendLine("- ${entry.key} = ${entry.value}") }
        append("Working Memory and the current user message override conflicting values here.")
    }

    private fun formatWorking(task: AgentTask, entries: List<MemoryEntry>): String = buildString {
        appendLine("WORKING MEMORY for Task \"${task.name}\" (overrides Long-Term Memory):")
        entries.forEach { entry -> appendLine("- ${entry.key} = ${entry.value}") }
        append("The current user message overrides conflicting values here.")
    }

    companion object {
        private val logger = LoggerFactory.getLogger(MemoryService::class.java)
    }
}

data class MemoryContext(
    val longTerm: List<MemoryEntry>,
    val working: List<MemoryEntry>,
    val messages: List<ChatMessage>,
)
