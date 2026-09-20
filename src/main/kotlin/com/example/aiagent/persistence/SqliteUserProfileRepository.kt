package com.example.aiagent.persistence

import com.example.aiagent.profile.ExpertiseLevel
import com.example.aiagent.profile.ResponseFormat
import com.example.aiagent.profile.ResponseLanguage
import com.example.aiagent.profile.ResponseStyle
import com.example.aiagent.profile.UserProfile
import com.example.aiagent.profile.UserProfileInput
import com.example.aiagent.profile.UserProfileRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Repository
class SqliteUserProfileRepository(
    private val jdbcTemplate: JdbcTemplate,
) : UserProfileRepository {
    init {
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS user_profile (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                response_language TEXT NOT NULL,
                expertise_level TEXT NOT NULL,
                response_style TEXT NOT NULL,
                response_format TEXT NOT NULL,
                custom_instructions TEXT NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS active_profile_state (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                profile_id INTEGER
            )
            """.trimIndent(),
        )
        jdbcTemplate.update(
            "INSERT OR IGNORE INTO active_profile_state(id, profile_id) VALUES (1, NULL)",
        )
    }

    override fun findAll(): List<UserProfile> = jdbcTemplate.query(
        """
        SELECT p.*, CASE WHEN s.profile_id = p.id THEN 1 ELSE 0 END AS active
        FROM user_profile p
        CROSS JOIN active_profile_state s
        WHERE s.id = 1
        ORDER BY p.created_at, p.id
        """.trimIndent(),
    ) { resultSet, _ ->
        UserProfile(
            id = resultSet.getLong("id"),
            name = resultSet.getString("name"),
            responseLanguage = ResponseLanguage.valueOf(resultSet.getString("response_language")),
            expertiseLevel = ExpertiseLevel.valueOf(resultSet.getString("expertise_level")),
            responseStyle = ResponseStyle.valueOf(resultSet.getString("response_style")),
            responseFormat = ResponseFormat.valueOf(resultSet.getString("response_format")),
            customInstructions = resultSet.getString("custom_instructions"),
            createdAt = Instant.parse(resultSet.getString("created_at")),
            updatedAt = Instant.parse(resultSet.getString("updated_at")),
            active = resultSet.getBoolean("active"),
        )
    }

    override fun findById(profileId: Long): UserProfile? = findAll().firstOrNull { it.id == profileId }

    override fun active(): UserProfile? = findAll().singleOrNull(UserProfile::active)

    @Transactional
    override fun create(input: UserProfileInput): UserProfile {
        val now = Instant.now().toString()
        jdbcTemplate.update(
            """
            INSERT INTO user_profile(
                name, response_language, expertise_level, response_style,
                response_format, custom_instructions, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            input.name,
            input.responseLanguage.name,
            input.expertiseLevel.name,
            input.responseStyle.name,
            input.responseFormat.name,
            input.customInstructions,
            now,
            now,
        )
        val profileId = checkNotNull(
            jdbcTemplate.queryForObject("SELECT MAX(id) FROM user_profile", Long::class.java),
        )
        if (active() == null) {
            jdbcTemplate.update("UPDATE active_profile_state SET profile_id = ? WHERE id = 1", profileId)
        }
        return checkNotNull(findById(profileId))
    }

    @Transactional
    override fun update(profileId: Long, input: UserProfileInput): UserProfile {
        require(findById(profileId) != null) { "User Profile $profileId does not exist" }
        jdbcTemplate.update(
            """
            UPDATE user_profile
            SET name = ?, response_language = ?, expertise_level = ?, response_style = ?,
                response_format = ?, custom_instructions = ?, updated_at = ?
            WHERE id = ?
            """.trimIndent(),
            input.name,
            input.responseLanguage.name,
            input.expertiseLevel.name,
            input.responseStyle.name,
            input.responseFormat.name,
            input.customInstructions,
            Instant.now().toString(),
            profileId,
        )
        return checkNotNull(findById(profileId))
    }

    @Transactional
    override fun activate(profileId: Long): UserProfile {
        require(findById(profileId) != null) { "User Profile $profileId does not exist" }
        jdbcTemplate.update("UPDATE active_profile_state SET profile_id = ? WHERE id = 1", profileId)
        return checkNotNull(findById(profileId))
    }
}
