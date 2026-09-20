package com.example.aiagent.web

import com.example.aiagent.profile.ExpertiseLevel
import com.example.aiagent.profile.ResponseFormat
import com.example.aiagent.profile.ResponseLanguage
import com.example.aiagent.profile.ResponseStyle
import com.example.aiagent.profile.UserProfile
import com.example.aiagent.profile.UserProfileInput
import com.example.aiagent.profile.UserProfileService
import com.example.aiagent.web.dto.UserProfileRequest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.time.Instant

class UserProfileControllerTest {
    private val service = mockk<UserProfileService>()
    private val controller = UserProfileController(service)

    @Test
    fun `Profile endpoints list create update activate and expose active Profile`() {
        val developer = profile(1, "Developer", ResponseStyle.CONCISE, active = true)
        val detailed = developer.copy(responseStyle = ResponseStyle.DETAILED)
        val request = request("Developer", ResponseStyle.CONCISE)
        val updatedRequest = request("Developer", ResponseStyle.DETAILED)
        every { service.profiles() } returns listOf(developer)
        every { service.activeProfile() } returns developer
        every { service.create(request.toInput()) } returns developer
        every { service.update(1, updatedRequest.toInput()) } returns detailed
        every { service.activate(1) } returns developer

        assertEquals("Developer", controller.profiles().single().name)
        assertEquals("Developer", controller.activeProfile().body?.name)
        assertEquals(1, controller.create(request).id)
        assertEquals(ResponseStyle.DETAILED, controller.update(1, updatedRequest).responseStyle)
        assertEquals(true, controller.activate(1).active)
        verify(exactly = 1) { service.activate(1) }
    }

    @Test
    fun `active Profile endpoint returns no content when personalization is absent`() {
        every { service.activeProfile() } returns null

        val response = controller.activeProfile()

        assertEquals(HttpStatus.NO_CONTENT, response.statusCode)
        assertNull(response.body)
    }

    private fun request(name: String, style: ResponseStyle) = UserProfileRequest(
        name = name,
        responseLanguage = ResponseLanguage.RUSSIAN,
        expertiseLevel = ExpertiseLevel.ADVANCED,
        responseStyle = style,
        responseFormat = ResponseFormat.CODE_FIRST,
        customInstructions = "Prefer Kotlin.",
    )

    private fun profile(id: Long, name: String, style: ResponseStyle, active: Boolean) = UserProfile(
        id = id,
        name = name,
        responseLanguage = ResponseLanguage.RUSSIAN,
        expertiseLevel = ExpertiseLevel.ADVANCED,
        responseStyle = style,
        responseFormat = ResponseFormat.CODE_FIRST,
        customInstructions = "Prefer Kotlin.",
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
        active = active,
    )
}
