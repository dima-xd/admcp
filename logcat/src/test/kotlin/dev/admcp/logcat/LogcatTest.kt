package dev.admcp.logcat

import dev.admcp.core.model.CoverageGapReason
import dev.admcp.core.model.CoverageStatus
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LogcatTest {
    @Test
    fun parsesThreadtimeRecordsAndContinuations() {
        val text = """
            10-31 12:00:00.123  1234  5678 I ActivityManager: Start proc
             continuation line
            10-31 12:00:01.000  1234  5678 E AndroidRuntime: FATAL EXCEPTION: main
            at app.Main(Main.kt:1)
        """.trimIndent()
        val records = LogcatParser().parse(text)
        assertEquals(2, records.size)
        assertEquals("I", records[0].level)
        assertEquals("ActivityManager", records[0].tag)
        assertTrue(records[0].raw.contains("continuation line"))
        assertTrue(records[1].raw.contains("FATAL EXCEPTION"))
    }

    @Test
    fun handlesMalformedLinesWithoutDroppingRaw() {
        val records = LogcatParser().parse("not a logcat record\n")
        assertEquals(1, records.size)
        assertEquals(null, records.single().pid)
        assertEquals("not a logcat record", records.single().raw)
    }

    @Test
    fun preservesRawAndParsed() = runBlocking {
        val collector = LogcatCollector(
            source = { flowOf("10-31 12:00:00.123  1  2 I Tag: message\n".toByteArray()) },
            maxRecords = 10,
        )
        val batch = collector.collect().toList().single()
        assertEquals(1, batch.records.size)
        assertTrue(batch.rawText.contains("message"))
    }

    @Test
    fun disconnectCreatesExplicitCoverageGap() = runBlocking {
        val source = object : LogcatSource {
            var calls = 0
            override fun open(): kotlinx.coroutines.flow.Flow<ByteArray> {
                calls++
                return if (calls == 1) kotlinx.coroutines.flow.flow { throw java.io.IOException("adb disconnected") }
                else flowOf(ByteArray(0))
            }
        }
        val collector = LogcatCollector(source, maxRecords = 10, reconnect = false)
        val result = collector.collectWithCoverage()
        assertEquals(CoverageStatus.PARTIAL, result.status)
        assertNotNull(result.gaps.firstOrNull { it.reason == CoverageGapReason.ADB_DISCONNECTED })
    }

    @Test
    fun reconnectOverlapDeduplicatesAroundRingRisk() = runBlocking {
        val line = "10-31 12:00:00.123  1  2 I Tag: message\n"
        val collector = LogcatCollector(source = { flowOf(line.toByteArray(), line.toByteArray()) }, maxRecords = 10)
        val batches = collector.collect().toList()
        assertEquals(1, batches.flatMap { it.records }.size)
    }

    @Test
    fun doesNotIssueClearOrResizeCommands() {
        val policy = LogcatSafetyPolicy()
        assertTrue(policy.commandsAreReadOnly(listOf("logcat", "-v", "threadtime", "-T", "1")))
        assertTrue(!policy.commandsAreReadOnly(listOf("logcat", "-c")))
        assertTrue(!policy.commandsAreReadOnly(listOf("logcat", "-G", "2M")))
    }

    @Test
    fun boundedCollectionReportsOverflow() = runBlocking {
        val lines = (1..100).joinToString("") { "10-31 12:00:00.${it.toString().padStart(3, '0')}  1  2 I Tag: m$it\n" }
        val collector = LogcatCollector(source = { flowOf(lines.toByteArray()) }, maxRecords = 10)
        val batch = collector.collect().toList().single()
        assertTrue(batch.records.size <= 10)
        assertTrue(batch.overflow)
    }
}
