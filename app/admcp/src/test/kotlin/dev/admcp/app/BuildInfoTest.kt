package dev.admcp.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BuildInfoTest {

    @Test
    fun versionIsPresent() {
        assertTrue(BuildInfo.version.isNotBlank(), "BuildInfo.version must not be blank")
    }

    @Test
    fun versionIsSemverLike() {
        assertTrue(
            Regex("""^\d+\.\d+\.\d+([-+][0-9A-Za-z.-]+)?$""").matches(BuildInfo.version),
            "BuildInfo.version must be semver-like but was '${BuildInfo.version}'",
        )
    }

    @Test
    fun nameIsPresent() {
        assertEquals("admcp", BuildInfo.NAME)
    }
}
