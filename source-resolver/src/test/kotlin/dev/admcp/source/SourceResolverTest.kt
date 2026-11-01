package dev.admcp.source

import dev.admcp.core.model.Availability
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceResolverTest {
    @Test
    fun resolvesExistingSourceInsideRoot() {
        val root = Files.createTempDirectory("admcp-source-")
        root.resolve("app/Main.kt").also { Files.createDirectories(it.parent) }.writeText("a\nb\nc\n")
        val result = SourceResolver(listOf(root), "build-1").resolve("app/Main.kt", 2, "build-1")
        assertEquals(Availability.AVAILABLE, result.status)
        assertEquals(2, result.location?.line)
    }

    @Test
    fun rejectsBuildIdentityMismatch() {
        val root = Files.createTempDirectory("admcp-source-")
        root.resolve("Main.kt").writeText("a\n")
        val result = SourceResolver(listOf(root), "build-1").resolve("Main.kt", 1, "build-2")
        assertNull(result.location)
        assertTrue(result.reason!!.contains("mismatch"))
    }

    @Test
    fun rejectsPathTraversal() {
        val root = Files.createTempDirectory("admcp-source-")
        val outside = Files.createTempFile("outside", ".kt")
        outside.writeText("secret")
        val result = SourceResolver(listOf(root), null).resolve("../${outside.fileName}", 1)
        assertNull(result.location)
    }

    @Test
    fun clampsOutOfRangeLineWithLimitedStatus() {
        val root = Files.createTempDirectory("admcp-source-")
        root.resolve("Main.kt").writeText("a\nb\n")
        val result = SourceResolver(listOf(root), null).resolve("Main.kt", 99)
        assertEquals(Availability.LIMITED, result.status)
        assertEquals(2, result.location?.line)
    }

    @Test
    fun matchesNativeSymbolOnlyWhenBuildIdsMatch() {
        val root = Files.createTempDirectory("admcp-source-")
        val symbol = root.resolve("libnative.so")
        symbol.writeText("elf")
        val resolver = SourceResolver(listOf(root), null)
        assertTrue(resolver.matchNativeSymbol(symbol, "b1", "b1").matched)
        assertFalse(resolver.matchNativeSymbol(symbol, "b1", "b2").matched)
        assertFalse(resolver.matchNativeSymbol(symbol, null, null).matched)
    }
}
