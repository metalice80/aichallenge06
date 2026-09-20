package com.example.aiagent.profile

import com.example.aiagent.memory.SecretRedactor
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant

class UserProfileServiceTest {
    private val repository = mockk<UserProfileRepository>()
    private val service = UserProfileService(repository, SecretRedactor())

    @Test
    fun `create and update normalize explicit profile settings`() {
        val createdInput = slot<UserProfileInput>()
        val updatedInput = slot<UserProfileInput>()
        every { repository.create(capture(createdInput)) } answers { profile(1, firstArg()) }
        every { repository.update(1, capture(updatedInput)) } answers { profile(1, secondArg()) }

        service.create(input(name = "  Developer  ", custom = "  Prefer Kotlin.  "))
        service.update(1, input(name = "Developer Advanced", custom = "Use diagrams."))

        assertEquals("Developer", createdInput.captured.name)
        assertEquals("Prefer Kotlin.", createdInput.captured.customInstructions)
        assertEquals("Developer Advanced", updatedInput.captured.name)
        assertEquals(ResponseStyle.CONCISE, updatedInput.captured.responseStyle)
    }

    @Test
    fun `activation delegates without changing any Task or memory state`() {
        val activated = profile(2, input("Student", "Explain terms."), active = true)
        every { repository.activate(2) } returns activated

        assertEquals(activated, service.activate(2))
        verify(exactly = 1) { repository.activate(2) }
    }

    @Test
    fun `secrets and invalid names are rejected before persistence`() {
        assertThrows(InvalidUserProfileException::class.java) {
            service.create(input(" ", "Valid"))
        }
        assertThrows(InvalidUserProfileException::class.java) {
            service.create(input("Developer", "api_key=sk-secret123456"))
        }
        verify(exactly = 0) { repository.create(any()) }
    }

    @Test
    fun `active profile loading failure falls back to no personalization`() {
        every { repository.active() } throws IllegalStateException("corrupt state")

        assertNull(service.activeProfile())
    }

    private fun input(
        name: String = "Developer",
        custom: String = "Prefer Kotlin.",
    ) = UserProfileInput(
        name = name,
        responseLanguage = ResponseLanguage.RUSSIAN,
        expertiseLevel = ExpertiseLevel.ADVANCED,
        responseStyle = ResponseStyle.CONCISE,
        responseFormat = ResponseFormat.CODE_FIRST,
        customInstructions = custom,
    )

    private fun profile(id: Long, input: UserProfileInput, active: Boolean = false) = UserProfile(
        id = id,
        name = input.name,
        responseLanguage = input.responseLanguage,
        expertiseLevel = input.expertiseLevel,
        responseStyle = input.responseStyle,
        responseFormat = input.responseFormat,
        customInstructions = input.customInstructions,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
        active = active,
    )
}
