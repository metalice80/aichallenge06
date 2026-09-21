package com.example.aiagent.web

import com.example.aiagent.invariant.InvalidTaskInvariantException
import com.example.aiagent.invariant.TaskInvariantConflictException
import com.example.aiagent.invariant.TaskInvariantNotFoundException
import com.example.aiagent.agent.InvalidMessageException
import com.example.aiagent.llm.InvalidLlmModelException
import com.example.aiagent.llm.InvalidLlmResponseException
import com.example.aiagent.llm.LlmAuthenticationException
import com.example.aiagent.llm.LlmClientException
import com.example.aiagent.llm.LlmNetworkException
import com.example.aiagent.llm.LlmRateLimitException
import com.example.aiagent.llm.LlmProvider
import com.example.aiagent.llm.LlmRequestException
import com.example.aiagent.llm.LlmServerException
import com.example.aiagent.llm.LlmTimeoutException
import com.example.aiagent.llm.MissingApiKeyException
import com.example.aiagent.profile.InvalidUserProfileException
import com.example.aiagent.task.InvalidTaskStateException
import com.example.aiagent.task.InvalidTaskTransitionException
import com.example.aiagent.task.TaskNotFoundException
import com.example.aiagent.task.TaskStateConflictException
import com.example.aiagent.web.dto.ApiError
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class ApiExceptionHandler {
    private val logger = LoggerFactory.getLogger(ApiExceptionHandler::class.java)

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(exception: MethodArgumentNotValidException): ResponseEntity<ApiError> {
        val message = exception.bindingResult.fieldErrors.firstOrNull()?.defaultMessage
            ?: "Некорректное сообщение."
        return error(HttpStatus.BAD_REQUEST, message)
    }

    @ExceptionHandler(InvalidUserProfileException::class)
    fun handleInvalidProfile(exception: InvalidUserProfileException): ResponseEntity<ApiError> =
        error(HttpStatus.BAD_REQUEST, exception.message ?: "Некорректный User Profile.")

    @ExceptionHandler(InvalidTaskInvariantException::class)
    fun handleInvalidInvariant(exception: InvalidTaskInvariantException): ResponseEntity<ApiError> =
        error(HttpStatus.BAD_REQUEST, exception.message ?: "Некорректный Task Invariant.")

    @ExceptionHandler(TaskInvariantConflictException::class)
    fun handleInvariantConflict(exception: TaskInvariantConflictException): ResponseEntity<ApiError> =
        error(HttpStatus.CONFLICT, exception.message ?: "Конфликт активных Task Invariants.")

    @ExceptionHandler(TaskInvariantNotFoundException::class)
    fun handleInvariantNotFound(exception: TaskInvariantNotFoundException): ResponseEntity<ApiError> =
        error(HttpStatus.NOT_FOUND, exception.message ?: "Task Invariant не найден.")

    @ExceptionHandler(InvalidTaskTransitionException::class)
    fun handleInvalidTaskTransition(
        exception: InvalidTaskTransitionException,
    ): ResponseEntity<ApiError> = error(
        HttpStatus.CONFLICT,
        exception.message ?: "Недопустимый переход Task State.",
        code = "INVALID_TASK_TRANSITION",
        currentStage = exception.currentStage,
        event = exception.event,
    )

    @ExceptionHandler(TaskNotFoundException::class)
    fun handleTaskNotFound(exception: TaskNotFoundException): ResponseEntity<ApiError> =
        error(HttpStatus.NOT_FOUND, exception.message ?: "Task не найдена.", exception.code)

    @ExceptionHandler(TaskStateConflictException::class)
    fun handleTaskStateConflict(exception: TaskStateConflictException): ResponseEntity<ApiError> =
        error(
            HttpStatus.CONFLICT,
            exception.message ?: "Task State изменился конкурентно.",
            exception.code,
            version = exception.actualVersion,
        )

    @ExceptionHandler(InvalidTaskStateException::class)
    fun handleInvalidTaskState(exception: InvalidTaskStateException): ResponseEntity<ApiError> =
        error(HttpStatus.CONFLICT, exception.message ?: "Недопустимое состояние Task.", exception.code)

    @ExceptionHandler(
        HttpMessageNotReadableException::class,
        InvalidMessageException::class,
        IllegalArgumentException::class,
    )
    fun handleBadRequest(exception: Exception): ResponseEntity<ApiError> {
        logger.debug("Rejected chat request with {}", exception.javaClass.simpleName)
        return error(HttpStatus.BAD_REQUEST, "Некорректные параметры запроса.")
    }

    @ExceptionHandler(MissingApiKeyException::class)
    fun handleMissingApiKey(exception: MissingApiKeyException): ResponseEntity<ApiError> {
        logger.warn("LLM request rejected: {}", exception.javaClass.simpleName)
        val environmentVariable = when (exception.provider) {
            LlmProvider.OPENAI -> "OPENAI_API_KEY"
            LlmProvider.OPENROUTER -> "OPENROUTER_API_KEY"
        }
        return error(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Сервис ${exception.provider.displayName} не настроен. Укажите $environmentVariable.",
        )
    }

    @ExceptionHandler(LlmAuthenticationException::class)
    fun handleAuthentication(exception: LlmAuthenticationException): ResponseEntity<ApiError> =
        loggedError(
            HttpStatus.BAD_GATEWAY,
            "Не удалось авторизоваться в ${exception.provider.displayName}.",
            exception,
        )

    @ExceptionHandler(LlmRateLimitException::class)
    fun handleRateLimit(exception: LlmRateLimitException): ResponseEntity<ApiError> =
        loggedError(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Лимит запросов ${exception.provider.displayName} исчерпан. Попробуйте позже.",
            exception,
        )

    @ExceptionHandler(LlmTimeoutException::class)
    fun handleTimeout(exception: LlmTimeoutException): ResponseEntity<ApiError> =
        loggedError(
            HttpStatus.GATEWAY_TIMEOUT,
            "${exception.provider.displayName} не ответил вовремя. Попробуйте ещё раз.",
            exception,
        )

    @ExceptionHandler(InvalidLlmModelException::class)
    fun handleInvalidModel(exception: InvalidLlmModelException): ResponseEntity<ApiError> =
        loggedError(
            HttpStatus.BAD_REQUEST,
            "Модель недоступна для ${exception.provider.displayName}. Проверьте выбранный model id.",
            exception,
        )

    @ExceptionHandler(
        LlmNetworkException::class,
        LlmServerException::class,
        LlmRequestException::class,
        InvalidLlmResponseException::class,
    )
    fun handleLlmFailure(exception: LlmClientException): ResponseEntity<ApiError> =
        loggedError(
            HttpStatus.BAD_GATEWAY,
            "Не удалось получить ответ от ${exception.provider.displayName}. Попробуйте ещё раз.",
            exception,
        )

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(exception: Exception): ResponseEntity<ApiError> =
        loggedError(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "Произошла внутренняя ошибка. Попробуйте ещё раз.",
            exception,
        )

    private fun loggedError(
        status: HttpStatus,
        message: String,
        exception: Exception,
    ): ResponseEntity<ApiError> {
        logger.error("Chat request failed with {}", exception.javaClass.simpleName, exception)
        return error(status, message)
    }

    private fun error(
        status: HttpStatus,
        message: String,
        code: String = status.name,
        currentStage: com.example.aiagent.task.TaskStage? = null,
        event: com.example.aiagent.task.TaskEvent? = null,
        version: Long? = null,
    ): ResponseEntity<ApiError> =
        ResponseEntity.status(status).body(ApiError(code, message, currentStage, event, version))
}
