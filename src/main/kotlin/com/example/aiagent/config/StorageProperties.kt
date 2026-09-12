package com.example.aiagent.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("storage")
data class StorageProperties(
    val databasePath: String = "./data/agent.db",
)
