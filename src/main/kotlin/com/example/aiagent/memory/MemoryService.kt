package com.example.aiagent.memory

import com.example.aiagent.invariant.TaskInvariantSnapshot
import com.example.aiagent.agent.ChatMessage
import com.example.aiagent.config.MemoryProperties
import com.example.aiagent.profile.UserProfileSnapshot
import com.example.aiagent.task.AgentTask
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

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
            return MemoryContext(emptyList(), emptyList())
        }
        return MemoryContext(
            longTerm = repository.findLongTerm(),
            working = repository.findWorking(task.id),
        )
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

    fun recordEffectiveContext(context: EffectiveContext) {
        repository.saveEffectiveContext(
            context.copy(
                systemPrompt = secretRedactor.redact(context.systemPrompt),
                longTermMemory = context.longTermMemory.map(::redact),
                userProfile = context.userProfile?.let(::redact),
                workingMemory = context.workingMemory.map(::redact),
                taskInvariants = context.taskInvariants.map(::redact),
                shortTerm = context.shortTerm.map(::redact),
                currentUserMessage = redact(context.currentUserMessage),
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

    private fun redact(profile: UserProfileSnapshot) = profile.copy(
        name = secretRedactor.redact(profile.name),
        customInstructions = secretRedactor.redact(profile.customInstructions),
    )

    private fun redact(invariant: TaskInvariantSnapshot) = invariant.copy(
        taskName = secretRedactor.redact(invariant.taskName),
        key = secretRedactor.redact(invariant.key),
        value = secretRedactor.redact(invariant.value),
        description = invariant.description?.let(secretRedactor::redact),
    )

    private fun redact(message: ChatMessage) = message.copy(content = secretRedactor.redact(message.content))

    companion object {
        private val logger = LoggerFactory.getLogger(MemoryService::class.java)
    }
}

data class MemoryContext(
    val longTerm: List<MemoryEntry>,
    val working: List<MemoryEntry>,
)
