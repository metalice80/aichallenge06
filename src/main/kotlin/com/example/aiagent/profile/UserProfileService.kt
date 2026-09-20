package com.example.aiagent.profile

import com.example.aiagent.memory.SecretRedactor
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class UserProfileService(
    private val repository: UserProfileRepository,
    private val secretRedactor: SecretRedactor,
) {
    fun profiles(): List<UserProfile> = repository.findAll()

    fun activeProfile(): UserProfile? = try {
        repository.active()
    } catch (exception: RuntimeException) {
        logger.warn("Could not load active User Profile; continuing without personalization", exception)
        null
    }

    fun create(input: UserProfileInput): UserProfile = repository.create(normalize(input))

    fun update(profileId: Long, input: UserProfileInput): UserProfile =
        repository.update(profileId, normalize(input))

    fun activate(profileId: Long): UserProfile = repository.activate(profileId)

    private fun normalize(input: UserProfileInput): UserProfileInput {
        val name = input.name.trim()
        val customInstructions = input.customInstructions.trim()
        if (name.isEmpty()) {
            throw InvalidUserProfileException("Название профиля не должно быть пустым.")
        }
        if (name.length > MAX_NAME_LENGTH) {
            throw InvalidUserProfileException("Название профиля не должно превышать $MAX_NAME_LENGTH символов.")
        }
        if (customInstructions.length > MAX_CUSTOM_INSTRUCTIONS_LENGTH) {
            throw InvalidUserProfileException(
                "Custom instructions не должны превышать $MAX_CUSTOM_INSTRUCTIONS_LENGTH символов.",
            )
        }
        if (secretRedactor.containsSecret(name) || secretRedactor.containsSecret(customInstructions)) {
            throw InvalidUserProfileException("User Profile не должен содержать API keys или другие секреты.")
        }
        return input.copy(name = name, customInstructions = customInstructions)
    }

    companion object {
        const val MAX_NAME_LENGTH = 120
        const val MAX_CUSTOM_INSTRUCTIONS_LENGTH = 8_000
        private val logger = LoggerFactory.getLogger(UserProfileService::class.java)
    }
}

class InvalidUserProfileException(message: String) : IllegalArgumentException(message)
