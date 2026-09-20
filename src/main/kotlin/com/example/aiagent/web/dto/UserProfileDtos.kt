package com.example.aiagent.web.dto

import com.example.aiagent.profile.ExpertiseLevel
import com.example.aiagent.profile.ResponseFormat
import com.example.aiagent.profile.ResponseLanguage
import com.example.aiagent.profile.ResponseStyle
import com.example.aiagent.profile.UserProfile
import com.example.aiagent.profile.UserProfileInput
import com.example.aiagent.profile.UserProfileService
import com.example.aiagent.profile.UserProfileSnapshot
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant

data class UserProfileRequest(
    @field:NotBlank(message = "Название профиля не должно быть пустым.")
    @field:Size(
        max = UserProfileService.MAX_NAME_LENGTH,
        message = "Название профиля не должно превышать ${UserProfileService.MAX_NAME_LENGTH} символов.",
    )
    val name: String,
    val responseLanguage: ResponseLanguage,
    val expertiseLevel: ExpertiseLevel,
    val responseStyle: ResponseStyle,
    val responseFormat: ResponseFormat,
    @field:Size(
        max = UserProfileService.MAX_CUSTOM_INSTRUCTIONS_LENGTH,
        message = "Custom instructions не должны превышать ${UserProfileService.MAX_CUSTOM_INSTRUCTIONS_LENGTH} символов.",
    )
    val customInstructions: String = "",
) {
    fun toInput() = UserProfileInput(
        name = name,
        responseLanguage = responseLanguage,
        expertiseLevel = expertiseLevel,
        responseStyle = responseStyle,
        responseFormat = responseFormat,
        customInstructions = customInstructions,
    )
}

data class UserProfileResponse(
    val id: Long,
    val name: String,
    val responseLanguage: ResponseLanguage,
    val expertiseLevel: ExpertiseLevel,
    val responseStyle: ResponseStyle,
    val responseFormat: ResponseFormat,
    val customInstructions: String,
    val createdAt: Instant?,
    val updatedAt: Instant?,
    val active: Boolean,
) {
    companion object {
        fun from(profile: UserProfile) = UserProfileResponse(
            id = profile.id,
            name = profile.name,
            responseLanguage = profile.responseLanguage,
            expertiseLevel = profile.expertiseLevel,
            responseStyle = profile.responseStyle,
            responseFormat = profile.responseFormat,
            customInstructions = profile.customInstructions,
            createdAt = profile.createdAt,
            updatedAt = profile.updatedAt,
            active = profile.active,
        )

        fun from(snapshot: UserProfileSnapshot) = UserProfileResponse(
            id = snapshot.id,
            name = snapshot.name,
            responseLanguage = snapshot.responseLanguage,
            expertiseLevel = snapshot.expertiseLevel,
            responseStyle = snapshot.responseStyle,
            responseFormat = snapshot.responseFormat,
            customInstructions = snapshot.customInstructions,
            createdAt = null,
            updatedAt = null,
            active = true,
        )
    }
}
