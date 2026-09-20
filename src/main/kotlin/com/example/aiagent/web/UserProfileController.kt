package com.example.aiagent.web

import com.example.aiagent.profile.UserProfileService
import com.example.aiagent.web.dto.UserProfileRequest
import com.example.aiagent.web.dto.UserProfileResponse
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/chat/profiles")
class UserProfileController(
    private val service: UserProfileService,
) {
    @GetMapping
    fun profiles(): List<UserProfileResponse> = service.profiles().map(UserProfileResponse::from)

    @GetMapping("/active")
    fun activeProfile(): ResponseEntity<UserProfileResponse> {
        val profile = service.activeProfile() ?: return ResponseEntity.noContent().build()
        return ResponseEntity.ok(UserProfileResponse.from(profile))
    }

    @PostMapping
    fun create(@Valid @RequestBody request: UserProfileRequest): UserProfileResponse =
        UserProfileResponse.from(service.create(request.toInput()))

    @PutMapping("/{profileId}")
    fun update(
        @PathVariable profileId: Long,
        @Valid @RequestBody request: UserProfileRequest,
    ): UserProfileResponse = UserProfileResponse.from(service.update(profileId, request.toInput()))

    @PostMapping("/{profileId}/activate")
    fun activate(@PathVariable profileId: Long): UserProfileResponse =
        UserProfileResponse.from(service.activate(profileId))
}
