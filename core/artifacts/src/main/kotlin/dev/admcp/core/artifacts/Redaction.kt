package dev.admcp.core.artifacts

import dev.admcp.core.model.RedactionState

data class RedactionPolicy(
    val redactBearerTokens: Boolean = true,
    val redactPasswords: Boolean = true,
    val redactEmails: Boolean = true,
    val redactLongHexSecrets: Boolean = true,
    val maxArtifactBytes: Long = 100L * 1024 * 1024,
    val maxSessionArtifactBytes: Long = 512L * 1024 * 1024,
)

data class RedactedText(
    val value: String,
    val redaction: RedactionState,
    val replacements: Int,
)

class Redactor(private val policy: RedactionPolicy = RedactionPolicy()) {
    private val patterns: List<Pair<Regex, (MatchResult) -> String>> = buildList {
        if (policy.redactBearerTokens) {
            add(Regex("""(?i)\bbearer\s+[A-Za-z0-9._\-+/=]{8,}""") to { match ->
                "Bearer " + match.value.substringAfter(' ').let { "[REDACTED]" }
            })
        }
        if (policy.redactPasswords) {
            add(Regex("""(?i)\b(password|passwd|secret|api[_-]?key|token)(\s*[=:]\s*)([^\s,;]{4,})""") to { match ->
                match.groupValues[1] + match.groupValues[2] + "[REDACTED]"
            })
        }
        if (policy.redactEmails) {
            add(Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""") to { "[REDACTED_EMAIL]" })
        }
        if (policy.redactLongHexSecrets) {
            add(Regex("""\b[0-9a-fA-F]{32,}\b""") to { "[REDACTED_HEX]" })
        }
    }

    fun redact(text: String): RedactedText {
        var result = text
        var count = 0
        patterns.forEach { (pattern, replacement) ->
            result = pattern.replace(result) { match ->
                count++
                replacement(match)
            }
        }
        return RedactedText(result, if (count == 0) RedactionState.RAW else RedactionState.REDACTED_VIEW, count)
    }
}

data class QuotaDecision(val allowed: Boolean, val reason: String?, val remainingBytes: Long)

class ArtifactQuota(private val policy: RedactionPolicy = RedactionPolicy()) {
    private val sessionBytes = mutableMapOf<String, Long>()

    @Synchronized
    fun reserve(sessionId: String, bytes: Long): QuotaDecision {
        require(bytes >= 0) { "bytes must not be negative" }
        if (bytes > policy.maxArtifactBytes) {
            return QuotaDecision(false, "artifact exceeds per-artifact quota", policy.maxArtifactBytes)
        }
        val used = sessionBytes[sessionId] ?: 0
        if (used + bytes > policy.maxSessionArtifactBytes) {
            return QuotaDecision(false, "session artifact quota exceeded", policy.maxSessionArtifactBytes - used)
        }
        sessionBytes[sessionId] = used + bytes
        return QuotaDecision(true, null, policy.maxSessionArtifactBytes - used - bytes)
    }

    @Synchronized
    fun release(sessionId: String, bytes: Long) {
        sessionBytes[sessionId] = ((sessionBytes[sessionId] ?: 0) - bytes).coerceAtLeast(0)
    }
}
