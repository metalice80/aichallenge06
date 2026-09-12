package com.example.aiagent.llm

import org.springframework.stereotype.Component

interface LlmClientResolver {
    fun resolve(provider: LlmProvider): LlmClient

    fun availableClients(): List<LlmClient>
}

@Component
class DefaultLlmClientResolver(
    clients: List<LlmClient>,
) : LlmClientResolver {
    private val clientsByProvider = clients.associateBy(LlmClient::provider)

    init {
        require(clientsByProvider.size == clients.size) {
            "Only one LlmClient may be registered for each provider"
        }
        require(LlmProvider.entries.all(clientsByProvider::containsKey)) {
            "An LlmClient must be registered for every provider"
        }
    }

    override fun resolve(provider: LlmProvider): LlmClient =
        checkNotNull(clientsByProvider[provider]) {
            "No LlmClient is registered for $provider"
        }

    override fun availableClients(): List<LlmClient> =
        LlmProvider.entries.map(::resolve)
}
