package dev.admcp.debug.lldb

import dev.admcp.core.model.Availability
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LldbCapabilityTest {
    @Test
    fun doesNotTreatLldbAsLldbDap() {
        val lldb = Files.createTempFile("lldb", ".exe")
        val result = LldbCapabilityProbe().probe(lldb, null, null, "arm64-v8a")
        assertEquals(Availability.AVAILABLE, result.host)
        assertEquals(Availability.UNAVAILABLE, result.adapter)
        assertFalse(result.nativeDebuggingAvailable)
    }

    @Test
    fun requiresAbiMatchedServer() {
        val dir = Files.createTempDirectory("lldb-abi-")
        val server = dir.resolve("aarch64/lldb-server")
        Files.createDirectories(server.parent)
        Files.createFile(server)
        val result = LldbCapabilityProbe().probe(null, null, server, "arm64-v8a")
        assertEquals(Availability.AVAILABLE, result.server)
        assertFalse(result.nativeDebuggingAvailable)
    }

    @Test
    fun rejectsMismatchedAbi() {
        val dir = Files.createTempDirectory("lldb-abi-")
        val server = dir.resolve("x86_64/lldb-server")
        Files.createDirectories(server.parent)
        Files.createFile(server)
        val result = LldbCapabilityProbe().probe(null, null, server, "arm64-v8a")
        assertEquals(Availability.LIMITED, result.server)
    }

    @Test
    fun missingToolsAreUnavailableNotUnknown() {
        val result = LldbCapabilityProbe().probe(null, null, null, "arm64-v8a")
        assertEquals(Availability.UNAVAILABLE, result.host)
        assertEquals(Availability.UNAVAILABLE, result.adapter)
        assertEquals(Availability.UNAVAILABLE, result.server)
        assertTrue(result.toCapabilities().all { it.reason != null })
    }
}
