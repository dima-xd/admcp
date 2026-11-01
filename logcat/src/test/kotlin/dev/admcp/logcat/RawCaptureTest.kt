package dev.admcp.logcat

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RawCaptureTest {
    private val line = "10-31 12:00:00.123  1  2 I Tag: message\n"

    @Test
    fun storesRawBytesBeforeParsing() = runBlocking {
        val session = LogcatCaptureSession(
            LogcatCaptureConfig(segmentBytes = 1024, maxSessionBytes = 10_000),
            LogcatSource { flowOf(line.toByteArray()) },
            Files.createTempDirectory("admcp-raw-"),
        )
        session.start(this)
        kotlinx.coroutines.delay(200)
        session.stop()
        val segments = session.segments()
        assertEquals(1, segments.size)
        assertEquals(line.toByteArray().size.toLong(), segments.single().bytes)
    }

    @Test
    fun rotatesSegmentsWithoutTruncation() {
        val writer = RawSegmentWriter(Files.createTempDirectory("admcp-raw-"), segmentBytes = 10, maxSessionBytes = 10_000)
        writer.append(ByteArray(8) { 'a'.code.toByte() })
        writer.append(ByteArray(8) { 'b'.code.toByte() })
        writer.append(ByteArray(8) { 'c'.code.toByte() })
        val segments = writer.finish()
        assertEquals(3, segments.size)
        assertEquals(24, segments.sumOf { it.bytes })
    }

    @Test
    fun quotaDegradesInsteadOfExceedingLimit() {
        val writer = RawSegmentWriter(Files.createTempDirectory("admcp-raw-"), segmentBytes = 10, maxSessionBytes = 12)
        assertTrue(writer.append(ByteArray(8)))
        assertFalse(writer.append(ByteArray(8)))
        val health = writer.health()
        assertTrue(health.quotaReached)
        assertEquals(8, health.droppedBytes)
        assertEquals(8, health.bytesWritten)
    }

    @Test
    fun writerFailureIsVisibleInHealth() {
        val root = Files.createTempDirectory("admcp-raw-")
        val writer = RawSegmentWriter(root.resolve("missing-parent"), segmentBytes = 10, maxSessionBytes = 100)
        Files.walk(root.resolve("missing-parent")).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        writer.append("abc".toByteArray())
        assertEquals("FAILED", writer.health().state)
    }

    @Test
    fun queryReturnsParsedRecordsFromRawStore() = runBlocking {
        val session = LogcatCaptureSession(
            LogcatCaptureConfig(segmentBytes = 1024, maxSessionBytes = 10_000),
            LogcatSource { flowOf(line.toByteArray()) },
            Files.createTempDirectory("admcp-raw-"),
        )
        session.start(this)
        kotlinx.coroutines.delay(200)
        session.stop()
        val records = session.query()
        assertEquals(1, records.size)
        assertEquals("message", records.single().message)
    }
}
