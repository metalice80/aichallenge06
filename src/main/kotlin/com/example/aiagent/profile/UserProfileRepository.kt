package com.example.aiagent.profile

interface UserProfileRepository {
    fun findAll(): List<UserProfile>
    fun findById(profileId: Long): UserProfile?
    fun active(): UserProfile?
    fun create(input: UserProfileInput): UserProfile
    fun update(profileId: Long, input: UserProfileInput): UserProfile
    fun activate(profileId: Long): UserProfile
}
