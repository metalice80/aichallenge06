package com.example.aiagent.config

import com.example.aiagent.agent.Conversation
import com.example.aiagent.persistence.ConversationRepository
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class AgentConfiguration {
    @Bean
    fun conversation(repository: ConversationRepository): Conversation = repository.load()
}
