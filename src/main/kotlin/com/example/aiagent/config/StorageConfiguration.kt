package com.example.aiagent.config

import org.sqlite.SQLiteDataSource
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Files
import java.nio.file.Path
import javax.sql.DataSource

@Configuration
class StorageConfiguration {
    @Bean
    fun dataSource(properties: StorageProperties): DataSource {
        require(properties.databasePath.isNotBlank()) {
            "storage.database-path must not be blank"
        }

        val databasePath = Path.of(properties.databasePath).toAbsolutePath().normalize()
        databasePath.parent?.let(Files::createDirectories)

        return SQLiteDataSource().apply {
            url = "jdbc:sqlite:$databasePath"
        }
    }
}
