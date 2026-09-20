package com.example.aiagent.memory

import org.springframework.stereotype.Component

@Component
class SecretRedactor {
    fun redact(value: String): String = patterns.fold(value) { redacted, pattern ->
        pattern.replace(redacted) { match ->
            when (match.groupValues.size) {
                4 -> "${match.groupValues[1]}${match.groupValues[2]}[REDACTED]"
                else -> "[REDACTED]"
            }
        }
    }

    fun containsSecret(value: String): Boolean = redact(value) != value

    private companion object {
        val patterns = listOf(
            Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/-]{8,}=*"),
            Regex("\\bsk-(?:or-v1-)?[A-Za-z0-9_-]{8,}\\b"),
            Regex("(?i)\\b(api[_-]?key|authorization|access[_-]?token|secret)(\\s*[:=]\\s*)([^\\s,;]+)"),
        )
    }
}
