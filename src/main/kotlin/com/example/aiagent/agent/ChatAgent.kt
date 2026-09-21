package com.example.aiagent.agent

import com.example.aiagent.context.ContextStateService
import com.example.aiagent.context.EffectiveContextBuilder
import com.example.aiagent.context.branch.ConversationBranch
import com.example.aiagent.context.branch.ConversationBranchService
import com.example.aiagent.context.strategy.ContextStrategyResolver
import com.example.aiagent.invariant.InvariantCheckDecision
import com.example.aiagent.invariant.InvariantCheckDirection
import com.example.aiagent.invariant.InvariantCheckOutcome
import com.example.aiagent.invariant.InvariantCheckStatus
import com.example.aiagent.invariant.InvariantGuardException
import com.example.aiagent.invariant.InvariantGuardService
import com.example.aiagent.invariant.TaskInvariantService
import com.example.aiagent.invariant.TaskInvariantSet
import com.example.aiagent.context.strategy.ContextStrategyType
import com.example.aiagent.llm.InvalidLlmResponseException
import com.example.aiagent.llm.LlmClient
import com.example.aiagent.llm.LlmClientResolver
import com.example.aiagent.llm.LlmRequest
import com.example.aiagent.llm.TokenUsage
import com.example.aiagent.memory.MemoryInspector
import com.example.aiagent.memory.MemoryService
import com.example.aiagent.persistence.ConversationRepository
import com.example.aiagent.profile.UserProfileService
import com.example.aiagent.task.AgentTask
import com.example.aiagent.task.ExpectedActionType
import com.example.aiagent.task.TaskEvent
import com.example.aiagent.task.TaskProgressProposal
import com.example.aiagent.task.TaskService
import com.example.aiagent.task.TaskStateCoordinator
import com.example.aiagent.task.TaskStateHistoryEntry
import com.example.aiagent.task.TaskStateService
import com.example.aiagent.task.TaskStatus
import org.springframework.stereotype.Service

@Service
class ChatAgent(
    private val llmClientResolver: LlmClientResolver,
    conversation: Conversation,
    private val conversationRepository: ConversationRepository,
    private val contextStrategyResolver: ContextStrategyResolver,
    private val contextStateService: ContextStateService,
    private val branchService: ConversationBranchService,
    private val taskService: TaskService,
    private val taskStateService: TaskStateService,
    private val taskInvariantService: TaskInvariantService,
    private val invariantGuardService: InvariantGuardService,
    private val taskStateCoordinator: TaskStateCoordinator,
    private val memoryService: MemoryService,
    private val effectiveContextBuilder: EffectiveContextBuilder,
    private val userProfileService: UserProfileService,
) : Agent {
    private var conversation = conversation

    @Synchronized
    override fun sendMessage(request: AgentRequest): AgentResponse {
        val content = request.message.trim()
        if (content.isEmpty()) {
            throw InvalidMessageException("Message must not be blank")
        }
        if (content.length > MAX_MESSAGE_LENGTH) {
            throw InvalidMessageException("Message must not exceed $MAX_MESSAGE_LENGTH characters")
        }
        val model = request.model.trim()
        if (model.isEmpty()) {
            throw InvalidMessageException("Model must not be blank")
        }
        if (model.length > MAX_MODEL_LENGTH) {
            throw InvalidMessageException("Model must not exceed $MAX_MODEL_LENGTH characters")
        }
        val initialTask = taskService.activeTask()
        if (initialTask.status == TaskStatus.COMPLETED) {
            throw InvalidMessageException("Completed Task is read-only")
        }
        if (initialTask.paused) {
            throw InvalidMessageException("Paused Task must be resumed before continuing")
        }

        val userMessage = ChatMessage(Role.USER, content)
        val invariantSet = try {
            taskInvariantService.snapshot(initialTask)
        } catch (exception: RuntimeException) {
            val unavailableSet = TaskInvariantSet.empty(initialTask.id, initialTask.name)
            invariantGuardService.recordFailure(
                unavailableSet,
                InvariantCheckDirection.INPUT,
                content,
                correctiveRetries = 0,
                outcome = InvariantCheckOutcome.REFUSED,
                errorCode = "SNAPSHOT_${exception.javaClass.simpleName}",
            )
            return guardRefusalResponse(request, invariantGuardService.technicalRefusal())
        }
        val inputCheck = try {
            invariantGuardService.evaluateInput(content, invariantSet)
        } catch (exception: InvariantGuardException) {
            invariantGuardService.recordFailure(
                invariantSet,
                InvariantCheckDirection.INPUT,
                content,
                correctiveRetries = 0,
                outcome = InvariantCheckOutcome.REFUSED,
                failure = exception,
            )
            return guardRefusalResponse(request, invariantGuardService.technicalRefusal())
        }
        if (inputCheck.result.decision == InvariantCheckDecision.BLOCKED) {
            try {
                invariantGuardService.record(
                    inputCheck,
                    content,
                    correctiveRetries = 0,
                    outcome = InvariantCheckOutcome.REFUSED,
                )
            } catch (exception: InvariantGuardException) {
                invariantGuardService.recordFailure(
                    invariantSet,
                    InvariantCheckDirection.INPUT,
                    content,
                    correctiveRetries = 0,
                    outcome = InvariantCheckOutcome.REFUSED,
                    failure = exception,
                )
                return guardRefusalResponse(request, invariantGuardService.technicalRefusal())
            }
            return guardRefusalResponse(request, invariantGuardService.semanticRefusal(inputCheck))
        }
        try {
            invariantGuardService.record(
                inputCheck,
                content,
                correctiveRetries = 0,
                outcome = InvariantCheckOutcome.NOT_SENT,
            )
        } catch (exception: InvariantGuardException) {
            invariantGuardService.recordFailure(
                invariantSet,
                InvariantCheckDirection.INPUT,
                content,
                correctiveRetries = 0,
                outcome = InvariantCheckOutcome.REFUSED,
                failure = exception,
            )
            return guardRefusalResponse(request, invariantGuardService.technicalRefusal())
        }

        val previousMessages = conversation.messages()
        val previousTokenUsage = conversation.tokenUsage()
        val previousSummary = conversation.summary()
        val task = taskStateCoordinator.analyzeBeforeMainRequest(initialTask, userMessage)
        val contextStrategy = contextStrategyResolver.resolve(request.contextStrategy)
        val contextPlan = contextStrategy.buildContext(conversation)
        val memoryContext = memoryService.context(task)
        val preparedContext = effectiveContextBuilder.build(
            task = task,
            profile = userProfileService.activeProfile(),
            strategy = request.contextStrategy,
            memoryContext = memoryContext,
            contextPlan = contextPlan,
            invariantSet = invariantSet,
            currentUserMessage = userMessage,
        )
        val llmRequest = LlmRequest(
            model = model,
            messages = preparedContext.messages,
        )
        memoryService.recordEffectiveContext(preparedContext.diagnostic)

        val startedAt = System.nanoTime()
        val llmClient = llmClientResolver.resolve(request.provider)
        val llmResponse = llmClient.chat(llmRequest)
        val responseTimeMs = (System.nanoTime() - startedAt) / NANOS_PER_MILLISECOND
        val candidate = llmResponse.content.trim()
        if (candidate.isEmpty()) {
            throw InvalidLlmResponseException(request.provider)
        }

        val deliveredContent = guardOutput(
            request = request,
            task = task,
            invariantSet = invariantSet,
            preparedMessages = preparedContext.messages,
            candidate = candidate,
            llmClient = llmClient,
        )
        val assistantMessage = ChatMessage(Role.ASSISTANT, deliveredContent)
        conversation.addAll(listOf(userMessage, assistantMessage))
        conversation.addUsage(llmResponse.usage)
        val requestUsage = LlmRequestUsage(
            provider = request.provider,
            model = llmResponse.model,
            tokenUsage = llmResponse.usage,
            responseTimeMs = responseTimeMs,
        )
        try {
            conversationRepository.save(conversation, requestUsage)
        } catch (exception: RuntimeException) {
            conversation.restore(previousMessages, previousTokenUsage, previousSummary)
            throw exception
        }
        contextStrategy.afterSuccessfulExchange(conversation, userMessage, assistantMessage)
        memoryService.extractAfterSuccessfulExchange(task, userMessage)

        return AgentResponse(
            provider = request.provider,
            content = deliveredContent,
            model = llmResponse.model,
            currentUsage = llmResponse.usage,
            conversationUsage = conversation.tokenUsage(),
            responseTimeMs = responseTimeMs,
        )
    }

    private fun guardOutput(
        request: AgentRequest,
        task: AgentTask,
        invariantSet: TaskInvariantSet,
        preparedMessages: List<ChatMessage>,
        candidate: String,
        llmClient: LlmClient,
    ): String {
        val firstCheck = try {
            invariantGuardService.evaluateOutput(request.message.trim(), candidate, invariantSet)
        } catch (exception: InvariantGuardException) {
            invariantGuardService.recordFailure(
                invariantSet,
                InvariantCheckDirection.OUTPUT,
                request.message,
                correctiveRetries = 0,
                outcome = InvariantCheckOutcome.REFUSED,
                failure = exception,
            )
            return invariantGuardService.outputRefusal(null)
        }
        if (firstCheck.result.decision == InvariantCheckDecision.ALLOWED) {
            return try {
                invariantGuardService.record(
                    firstCheck,
                    request.message,
                    correctiveRetries = 0,
                    outcome = InvariantCheckOutcome.DELIVERED,
                )
                candidate
            } catch (exception: InvariantGuardException) {
                invariantGuardService.recordFailure(
                    invariantSet,
                    InvariantCheckDirection.OUTPUT,
                    request.message,
                    correctiveRetries = 0,
                    outcome = InvariantCheckOutcome.REFUSED,
                    failure = exception,
                )
                invariantGuardService.outputRefusal(null)
            }
        }
        if (invariantGuardService.maxCorrectiveRetries == 0) {
            return recordOutputRefusal(firstCheck, request.message, correctiveRetries = 0)
        }
        try {
            invariantGuardService.record(
                firstCheck,
                request.message,
                correctiveRetries = 0,
                outcome = InvariantCheckOutcome.NOT_SENT,
            )
        } catch (exception: InvariantGuardException) {
            invariantGuardService.recordFailure(
                invariantSet,
                InvariantCheckDirection.OUTPUT,
                request.message,
                correctiveRetries = 0,
                outcome = InvariantCheckOutcome.REFUSED,
                failure = exception,
            )
            return invariantGuardService.outputRefusal(null)
        }

        val correctiveResponse = try {
            val startedAt = System.nanoTime()
            val response = llmClient.chat(
                LlmRequest(
                    model = request.model.trim(),
                    messages = preparedMessages + listOf(
                        ChatMessage(Role.ASSISTANT, candidate),
                        ChatMessage(Role.SYSTEM, invariantGuardService.correctiveInstruction(firstCheck)),
                    ),
                ),
            )
            val responseTimeMs = (System.nanoTime() - startedAt) / NANOS_PER_MILLISECOND
            if (response.content.isBlank()) {
                throw InvalidLlmResponseException(request.provider)
            }
            conversationRepository.recordUsage(
                task.id,
                LlmRequestUsage(
                    provider = request.provider,
                    purpose = LlmRequestPurpose.INVARIANT_CORRECTIVE_RETRY,
                    model = response.model,
                    tokenUsage = response.usage,
                    responseTimeMs = responseTimeMs,
                ),
            )
            response
        } catch (exception: RuntimeException) {
            invariantGuardService.recordFailure(
                invariantSet,
                InvariantCheckDirection.OUTPUT,
                request.message,
                correctiveRetries = 1,
                outcome = InvariantCheckOutcome.REFUSED,
                errorCode = "CORRECTIVE_${exception.javaClass.simpleName}",
            )
            return invariantGuardService.outputRefusal(firstCheck)
        }
        val correctedCandidate = correctiveResponse.content.trim()
        val correctedCheck = try {
            invariantGuardService.evaluateOutput(
                request.message.trim(),
                correctedCandidate,
                invariantSet,
            )
        } catch (exception: InvariantGuardException) {
            invariantGuardService.recordFailure(
                invariantSet,
                InvariantCheckDirection.OUTPUT,
                request.message,
                correctiveRetries = 1,
                outcome = InvariantCheckOutcome.REFUSED,
                failure = exception,
            )
            return invariantGuardService.outputRefusal(firstCheck)
        }
        if (correctedCheck.result.decision == InvariantCheckDecision.BLOCKED) {
            return recordOutputRefusal(correctedCheck, request.message, correctiveRetries = 1)
        }
        return try {
            invariantGuardService.record(
                correctedCheck,
                request.message,
                correctiveRetries = 1,
                outcome = InvariantCheckOutcome.DELIVERED,
                status = InvariantCheckStatus.OUTPUT_CORRECTED,
            )
            correctedCandidate
        } catch (exception: InvariantGuardException) {
            invariantGuardService.recordFailure(
                invariantSet,
                InvariantCheckDirection.OUTPUT,
                request.message,
                correctiveRetries = 1,
                outcome = InvariantCheckOutcome.REFUSED,
                failure = exception,
            )
            invariantGuardService.outputRefusal(null)
        }
    }

    private fun recordOutputRefusal(
        evaluation: com.example.aiagent.invariant.InvariantGuardEvaluation,
        request: String,
        correctiveRetries: Int,
    ): String = try {
        invariantGuardService.record(
            evaluation,
            request,
            correctiveRetries,
            InvariantCheckOutcome.REFUSED,
        )
        invariantGuardService.outputRefusal(evaluation)
    } catch (exception: InvariantGuardException) {
        invariantGuardService.recordFailure(
            evaluation.invariantSet,
            InvariantCheckDirection.OUTPUT,
            request,
            correctiveRetries,
            InvariantCheckOutcome.REFUSED,
            failure = exception,
        )
        invariantGuardService.outputRefusal(null)
    }

    private fun guardRefusalResponse(request: AgentRequest, content: String) = AgentResponse(
        provider = request.provider,
        content = content,
        model = request.model.trim(),
        currentUsage = TokenUsage(0, 0, 0),
        conversationUsage = conversation.tokenUsage(),
        responseTimeMs = 0,
    )

    @Synchronized
    override fun history(): List<ChatMessage> = conversation.messages()

    @Synchronized
    override fun state(): AgentState = currentState()

    override fun providers(): List<LlmProviderOption> =
        llmClientResolver.availableClients().map { client ->
            LlmProviderOption(
                provider = client.provider,
                displayName = client.provider.displayName,
                defaultModel = client.defaultModel,
            )
        }

    override fun contextStrategies(): List<ContextStrategyType> =
        contextStrategyResolver.availableTypes()

    override fun tasks(): List<AgentTask> = taskService.tasks()

    @Synchronized
    override fun createTask(name: String): AgentState {
        val task = taskService.create(name)
        conversation = conversationRepository.load(task.id)
        return currentState()
    }

    @Synchronized
    override fun activateTask(taskId: Long): AgentState {
        val task = taskService.activate(taskId)
        conversation = conversationRepository.load(task.id)
        return currentState()
    }

    @Synchronized
    override fun completeTask(taskId: Long): AgentTask = taskService.complete(taskId)

    @Synchronized
    override fun applyTaskEvent(
        taskId: Long,
        event: TaskEvent,
        proposal: TaskProgressProposal?,
    ): AgentTask = taskStateService.applyEvent(taskId, event, proposal)

    @Synchronized
    override fun updateTaskProgress(
        taskId: Long,
        currentStep: String,
        expectedActionType: ExpectedActionType,
        expectedActionDescription: String?,
    ): AgentTask = taskStateService.updateProgress(
        taskId,
        currentStep,
        expectedActionType,
        expectedActionDescription,
    )

    @Synchronized
    override fun pauseTask(taskId: Long): AgentTask = taskStateService.pause(taskId)

    @Synchronized
    override fun resumeTask(taskId: Long): AgentTask = taskStateService.resume(taskId)

    override fun taskStateHistory(taskId: Long): List<TaskStateHistoryEntry> =
        taskStateService.history(taskId)

    override fun branches(): List<ConversationBranch> =
        branchService.branches(conversation.taskId)

    @Synchronized
    override fun createBranch(): ConversationBranch =
        branchService.createBranch(conversation.taskId, conversation.messages())

    @Synchronized
    override fun activateBranch(branchId: Long): AgentState = AgentState(
        messages = branchService.activateBranch(
            conversation.taskId,
            branchId,
            conversation.messages(),
        ),
        conversationUsage = conversation.tokenUsage(),
    )

    @Synchronized
    override fun memory(contextStrategy: ContextStrategyType): MemoryInspector {
        val effectiveShortTerm = contextStrategyResolver.resolve(contextStrategy)
            .buildContext(conversation)
            .contextMessages
        return memoryService.inspector(conversation.taskId, effectiveShortTerm)
    }

    @Synchronized
    override fun clearWorkingMemory() {
        memoryService.clearWorking(conversation.taskId)
    }

    @Synchronized
    override fun clearLongTermMemory() {
        memoryService.clearLongTerm()
    }

    @Synchronized
    override fun reset() {
        contextStateService.reset(conversation.taskId)
        conversation.clear()
    }

    private fun currentState() = AgentState(
        messages = conversation.messages(),
        conversationUsage = conversation.tokenUsage(),
    )

    companion object {
        const val MAX_MESSAGE_LENGTH = 4_000
        const val MAX_MODEL_LENGTH = 200
        private const val NANOS_PER_MILLISECOND = 1_000_000
    }
}
