package com.example.aiagent.context

import com.example.aiagent.agent.ChatMessage
import com.example.aiagent.agent.Role
import com.example.aiagent.config.LlmProperties
import com.example.aiagent.context.strategy.ContextPlan
import com.example.aiagent.context.strategy.ContextStrategyType
import com.example.aiagent.memory.EffectiveContext
import com.example.aiagent.memory.MemoryContext
import com.example.aiagent.memory.MemoryEntry
import com.example.aiagent.memory.SecretRedactor
import com.example.aiagent.profile.UserProfile
import com.example.aiagent.profile.UserProfileSnapshot
import com.example.aiagent.task.AgentTask
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class EffectiveContextBuilder(
    private val properties: LlmProperties,
    private val secretRedactor: SecretRedactor,
) {
    fun build(
        task: AgentTask,
        profile: UserProfile?,
        strategy: ContextStrategyType,
        memoryContext: MemoryContext,
        contextPlan: ContextPlan,
        currentUserMessage: ChatMessage,
    ): PreparedEffectiveContext {
        require(currentUserMessage.role == Role.USER) { "Current message must have USER role" }
        val profileSnapshot = profile?.snapshot()?.redacted()
        val systemPrompt = properties.systemPrompt.trim()
        val messages = buildList {
            add(ChatMessage(Role.SYSTEM, systemPrompt))
            if (memoryContext.longTerm.isNotEmpty()) {
                add(ChatMessage(Role.SYSTEM, formatLongTerm(memoryContext.longTerm)))
            }
            if (profileSnapshot != null) {
                add(ChatMessage(Role.SYSTEM, formatProfile(profileSnapshot)))
            }
            if (memoryContext.working.isNotEmpty()) {
                add(ChatMessage(Role.SYSTEM, formatWorking(task, memoryContext.working)))
            }
            addAll(contextPlan.contextMessages)
            add(currentUserMessage)
        }
        return PreparedEffectiveContext(
            messages = messages,
            diagnostic = EffectiveContext(
                taskId = task.id,
                strategy = strategy,
                systemPrompt = systemPrompt,
                longTermMemory = memoryContext.longTerm,
                userProfile = profileSnapshot,
                workingMemory = memoryContext.working,
                shortTerm = contextPlan.contextMessages,
                currentUserMessage = currentUserMessage,
                preparedAt = Instant.now(),
            ),
        )
    }

    private fun formatLongTerm(entries: List<MemoryEntry>): String = buildString {
        appendLine("LONG-TERM MEMORY (learned personalization; overrides application defaults only):")
        entries.forEach { entry -> appendLine("- ${entry.key} = ${entry.value}") }
        append("Explicit User Profile, Working Memory, and the current user message override conflicts here.")
    }

    private fun formatProfile(profile: UserProfileSnapshot): String = buildString {
        appendLine("USER PROFILE \"${profile.name}\" (explicit personalization; overrides Long-Term Memory):")
        appendLine("- Language: ${profile.responseLanguage.displayName}. ${profile.responseLanguage.instruction}")
        appendLine("- Expertise: ${profile.expertiseLevel.displayName}. ${profile.expertiseLevel.instruction}")
        appendLine("- Style: ${profile.responseStyle.displayName}. ${profile.responseStyle.instruction}")
        appendLine("- Format: ${profile.responseFormat.displayName}. ${profile.responseFormat.instruction}")
        if (profile.customInstructions.isNotBlank()) {
            appendLine("- Custom instructions:")
            profile.customInstructions.lineSequence().forEach { line -> appendLine("  $line") }
        }
        append(
            "Treat these as defaults. Working Memory/current Task constraints override them, " +
                "and the current user message has the highest priority.",
        )
    }

    private fun formatWorking(task: AgentTask, entries: List<MemoryEntry>): String = buildString {
        appendLine("WORKING MEMORY for Task \"${task.name}\" (overrides User Profile and Long-Term Memory):")
        entries.forEach { entry -> appendLine("- ${entry.key} = ${entry.value}") }
        append("The current user message overrides conflicting values here.")
    }

    private fun UserProfileSnapshot.redacted() = copy(
        name = secretRedactor.redact(name),
        customInstructions = secretRedactor.redact(customInstructions),
    )
}

data class PreparedEffectiveContext(
    val messages: List<ChatMessage>,
    val diagnostic: EffectiveContext,
)
