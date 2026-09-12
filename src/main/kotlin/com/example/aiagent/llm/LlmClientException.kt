package com.example.aiagent.llm

sealed class LlmClientException(
    val provider: LlmProvider,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class MissingApiKeyException(provider: LlmProvider) :
    LlmClientException(provider, "$provider API key is not configured")

class LlmAuthenticationException(provider: LlmProvider) :
    LlmClientException(provider, "$provider rejected the configured API key")

class LlmRateLimitException(provider: LlmProvider) :
    LlmClientException(provider, "$provider rate limit was exceeded")

class InvalidLlmModelException(provider: LlmProvider, message: String = "$provider model is invalid") :
    LlmClientException(provider, message)

class LlmServerException(provider: LlmProvider, val statusCode: Int) :
    LlmClientException(provider, "$provider server returned HTTP $statusCode")

class LlmTimeoutException(provider: LlmProvider, cause: Throwable) :
    LlmClientException(provider, "$provider request timed out", cause)

class LlmNetworkException(provider: LlmProvider, cause: Throwable) :
    LlmClientException(provider, "$provider network request failed", cause)

class InvalidLlmResponseException(provider: LlmProvider, cause: Throwable? = null) :
    LlmClientException(provider, "$provider returned an invalid response", cause)

class LlmRequestException(
    provider: LlmProvider,
    val statusCode: Int? = null,
    cause: Throwable? = null,
) : LlmClientException(
    provider,
    statusCode?.let { "$provider request failed with HTTP $it" } ?: "$provider request could not be created",
    cause,
)
