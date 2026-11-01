package dev.admcp.core.artifacts

import dev.admcp.core.model.ArtifactKind
import dev.admcp.core.model.RedactionState
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ArtifactStoreTest {
    @Test
    fun storesContentAddressedArtifacts() {
        val store = ArtifactStore(Files.createTempDirectory("admcp-artifacts-"))
        val record = store.putText(ArtifactKind.TEXT_LOG, "hello")
        assertEquals(64, record.sha256.length)
        assertContentEquals("hello".toByteArray(), store.read(record.id).bytes)
    }

    @Test
    fun readAllBypassesThePreviewReadLimit() {
        val store = ArtifactStore(Files.createTempDirectory("admcp-artifacts-"), maxBytes = 10, maxReadBytes = 2)
        val record = store.putText(ArtifactKind.PERFETTO_TRACE, "abcdef")
        assertTrue(store.read(record.id).truncated)
        val full = store.readAll(record.id)
        assertContentEquals("abcdef".toByteArray(), full.bytes)
        assertTrue(!full.truncated)
    }

    @Test
    fun reloadsArtifactIndexFromMetadata() {
        val root = Files.createTempDirectory("admcp-artifacts-")
        val first = ArtifactStore(root)
        val record = first.putText(ArtifactKind.PERFETTO_TRACE, "trace-bytes")
        val second = ArtifactStore(root)
        assertEquals(record.id, second.metadata(record.id)?.id)
        assertContentEquals("trace-bytes".toByteArray(), second.readAll(record.id).bytes)
    }

    @Test
    fun sameContentKeepsDistinctIdsButSameDigest() {
        val store = ArtifactStore(Files.createTempDirectory("admcp-artifacts-"))
        val a = store.putText(ArtifactKind.TEXT_LOG, "same")
        val b = store.putText(ArtifactKind.TEXT_LOG, "same")
        assertEquals(a.sha256, b.sha256)
        assertTrue(a.id != b.id)
    }

    @Test
    fun enforcesWriteAndReadLimits() {
        val store = ArtifactStore(Files.createTempDirectory("admcp-artifacts-"), maxBytes = 3, maxReadBytes = 2)
        assertFailsWith<IllegalArgumentException> { store.putText(ArtifactKind.TEXT_LOG, "toolarge") }
        val record = ArtifactStore(Files.createTempDirectory("admcp-artifacts-"), maxBytes = 10, maxReadBytes = 2)
            .putText(ArtifactKind.TEXT_LOG, "abcd")
        val read = ArtifactStore(Files.createTempDirectory("admcp-artifacts-"), maxBytes = 10, maxReadBytes = 2)
        assertEquals(true, store.read(store.putText(ArtifactKind.TEXT_LOG, "ok").id).bytes.isNotEmpty())
        assertEquals(4, record.sizeBytes)
        assertTrue(read.metadata(record.id) == null)
    }

    @Test
    fun rejectsTraversalAndMarksRedaction() {
        val store = ArtifactStore(Files.createTempDirectory("admcp-artifacts-"))
        assertFailsWith<IllegalArgumentException> { store.resolve("../../escape") }
        val record = store.putText(ArtifactKind.TEXT_LOG, "secret", RedactionState.REDACTED_VIEW)
        assertEquals(RedactionState.REDACTED_VIEW, record.redaction)
    }
}
