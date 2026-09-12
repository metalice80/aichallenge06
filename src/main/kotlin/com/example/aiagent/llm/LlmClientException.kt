package com.example.aiagent.llm

sealed class LlmClientException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class MissingApiKeyException : LlmClientException("OPENAI_API_KEY is not configured")

class LlmAuthenticationException : LlmClientException("OpenAI rejected the configured API key")

class LlmRateLimitException : LlmClientException("OpenAI rate limit was exceeded")

class LlmServerException(val statusCode: Int) :
    LlmClientException("OpenAI server returned HTTP $statusCode")

class LlmTimeoutException(cause: Throwable) : LlmClientException("OpenAI request timed out", cause)

class LlmNetworkException(cause: Throwable) : LlmClientException("OpenAI network request failed", cause)

class InvalidLlmResponseException(cause: Throwable? = null) :
    LlmClientException("OpenAI returned an invalid response", cause)

class LlmRequestException(val statusCode: Int? = null, cause: Throwable? = null) :
    LlmClientException(
        statusCode?.let { "OpenAI request failed with HTTP $it" } ?: "OpenAI request could not be created",
        cause,
    )
