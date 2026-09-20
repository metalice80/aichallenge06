package com.example.aiagent.profile

import java.time.Instant

enum class ResponseLanguage(
    val displayName: String,
    val instruction: String,
) {
    RUSSIAN("Russian", "Respond in Russian unless the current user message explicitly requests another language."),
    ENGLISH("English", "Respond in English unless the current user message explicitly requests another language."),
}

enum class ExpertiseLevel(
    val displayName: String,
    val instruction: String,
) {
    BEGINNER(
        "Beginner",
        "Explain terminology, assume no deep prior knowledge, and use simple examples.",
    ),
    INTERMEDIATE(
        "Intermediate",
        "Use standard technical terminology and explain complex or non-obvious points.",
    ),
    ADVANCED(
        "Advanced",
        "Skip basic explanations unless needed and focus on architecture, trade-offs, and implementation details.",
    ),
}

enum class ResponseStyle(
    val displayName: String,
    val instruction: String,
) {
    CONCISE("Concise", "Answer concisely and directly; avoid unnecessary explanation."),
    DETAILED("Detailed", "Give a thorough answer with relevant context and important edge cases."),
    EDUCATIONAL("Educational", "Teach the reasoning clearly and connect new concepts to simple examples."),
    TECHNICAL("Technical", "Use precise professional terminology and emphasize technical details."),
}

enum class ResponseFormat(
    val displayName: String,
    val instruction: String,
) {
    TEXT("Text", "Use a natural prose response."),
    STRUCTURED("Structured", "Use headings, short sections, and lists when they improve clarity."),
    CODE_FIRST("Code-first", "For programming questions, show the main code example before its explanation."),
    STEP_BY_STEP("Step-by-step", "Explain the solution as an ordered sequence of steps."),
}

data class UserProfile(
    val id: Long,
    val name: String,
    val responseLanguage: ResponseLanguage,
    val expertiseLevel: ExpertiseLevel,
    val responseStyle: ResponseStyle,
    val responseFormat: ResponseFormat,
    val customInstructions: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val active: Boolean,
) {
    fun snapshot() = UserProfileSnapshot(
        id = id,
        name = name,
        responseLanguage = responseLanguage,
        expertiseLevel = expertiseLevel,
        responseStyle = responseStyle,
        responseFormat = responseFormat,
        customInstructions = customInstructions,
    )
}

data class UserProfileInput(
    val name: String,
    val responseLanguage: ResponseLanguage,
    val expertiseLevel: ExpertiseLevel,
    val responseStyle: ResponseStyle,
    val responseFormat: ResponseFormat,
    val customInstructions: String = "",
)

data class UserProfileSnapshot(
    val id: Long,
    val name: String,
    val responseLanguage: ResponseLanguage,
    val expertiseLevel: ExpertiseLevel,
    val responseStyle: ResponseStyle,
    val responseFormat: ResponseFormat,
    val customInstructions: String,
)
