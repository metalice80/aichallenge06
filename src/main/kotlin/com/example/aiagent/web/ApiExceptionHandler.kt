package com.example.aiagent.web

import com.example.aiagent.agent.InvalidMessageException
import com.example.aiagent.llm.InvalidLlmResponseException
import com.example.aiagent.llm.LlmAuthenticationException
import com.example.aiagent.llm.LlmNetworkException
import com.example.aiagent.llm.LlmRateLimitException
import com.example.aiagent.llm.LlmRequestException
import com.example.aiagent.llm.LlmServerException
import com.example.aiagent.llm.LlmTimeoutException
import com.example.aiagent.llm.MissingApiKeyException
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

    @ExceptionHandler(HttpMessageNotReadableException::class, InvalidMessageException::class)
    fun handleBadRequest(exception: Exception): ResponseEntity<ApiError> {
        logger.debug("Rejected chat request with {}", exception.javaClass.simpleName)
        return error(HttpStatus.BAD_REQUEST, "Сообщение не должно быть пустым.")
    }

    @ExceptionHandler(MissingApiKeyException::class)
    fun handleMissingApiKey(exception: MissingApiKeyException): ResponseEntity<ApiError> {
        logger.warn("LLM request rejected: {}", exception.javaClass.simpleName)
        return error(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Сервис модели не настроен. Укажите OPENAI_API_KEY.",
        )
    }

    @ExceptionHandler(LlmAuthenticationException::class)
    fun handleAuthentication(exception: LlmAuthenticationException): ResponseEntity<ApiError> =
        loggedError(
            HttpStatus.BAD_GATEWAY,
            "Не удалось авторизоваться в сервисе модели.",
            exception,
        )

    @ExceptionHandler(LlmRateLimitException::class)
    fun handleRateLimit(exception: LlmRateLimitException): ResponseEntity<ApiError> =
        loggedError(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Лимит запросов к модели исчерпан. Попробуйте позже.",
            exception,
        )

    @ExceptionHandler(LlmTimeoutException::class)
    fun handleTimeout(exception: LlmTimeoutException): ResponseEntity<ApiError> =
        loggedError(
            HttpStatus.GATEWAY_TIMEOUT,
            "Модель не ответила вовремя. Попробуйте ещё раз.",
            exception,
        )

    @ExceptionHandler(
        LlmNetworkException::class,
        LlmServerException::class,
        LlmRequestException::class,
        InvalidLlmResponseException::class,
    )
    fun handleLlmFailure(exception: Exception): ResponseEntity<ApiError> =
        loggedError(
            HttpStatus.BAD_GATEWAY,
            "Не удалось получить ответ от модели. Попробуйте ещё раз.",
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

    private fun error(status: HttpStatus, message: String): ResponseEntity<ApiError> =
        ResponseEntity.status(status).body(ApiError(message))
}
