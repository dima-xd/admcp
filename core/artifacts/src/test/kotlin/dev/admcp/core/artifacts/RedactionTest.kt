package dev.admcp.core.artifacts

import dev.admcp.core.model.RedactionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RedactionTest {
    @Test
    fun redactsCredentialsAndPreservesContext() {
        val result = Redactor().redact("Authorization: Bearer abcdefghijklmnop password=hunter2 user@example.com")
        assertTrue(result.value.contains("Bearer [REDACTED]"))
        assertTrue(result.value.contains("password=[REDACTED]"))
        assertTrue(result.value.contains("[REDACTED_EMAIL]"))
        assertEquals(RedactionState.REDACTED_VIEW, result.redaction)
        assertTrue(result.replacements >= 3)
    }

    @Test
    fun rawStateWhenNothingSensitive() {
        val result = Redactor().redact("ordinary log message")
        assertEquals(RedactionState.RAW, result.redaction)
        assertEquals("ordinary log message", result.value)
    }

    @Test
    fun quotasArePerArtifactAndPerSession() {
        val quota = ArtifactQuota(RedactionPolicy(maxArtifactBytes = 10, maxSessionArtifactBytes = 15))
        assertTrue(quota.reserve("s1", 8).allowed)
        assertFalse(quota.reserve("s1", 8).allowed)
        assertTrue(quota.reserve("s2", 8).allowed)
        assertFalse(quota.reserve("s2", 11).allowed)
        quota.release("s1", 8)
        assertTrue(quota.reserve("s1", 8).allowed)
    }
}
