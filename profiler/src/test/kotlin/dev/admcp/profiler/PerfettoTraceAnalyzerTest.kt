package dev.admcp.profiler

import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PerfettoTraceAnalyzerTest {
    @Test
    fun summarizesSlicesCountersAndProcessNames() {
        val trace = trace(
            packet(
                1_000,
                internedData(eventName(1, "renderFrame")),
                trackDescriptor(7, "main"),
            ),
            packet(
                1_500,
                internedData(eventName(1, "renderFrame")),
                trackEvent(type = 1, nameIid = 1, trackUuid = 7),
            ),
            packet(
                1_800,
                internedData(eventName(1, "renderFrame")),
                trackEvent(type = 2, nameIid = 1, trackUuid = 7),
            ),
            packet(
                1_600,
                internedData(eventName(2, "memory")),
                trackEvent(type = 4, nameIid = 2, trackUuid = 7, counter = 42),
            ),
            packet(
                2_000,
                processTree(pid = 10, name = "dev.admcp.fixture"),
            ),
        )

        val result = PerfettoTraceAnalyzer().analyze(trace)

        assertTrue(result.valid)
        assertEquals(5, result.packetCount)
        assertEquals(1_000, result.firstTimestampNanos)
        assertEquals(2_000, result.lastTimestampNanos)
        assertEquals(1_000, result.durationNanos)
        assertEquals("dev.admcp.fixture", result.processes.single().name)
        assertEquals("renderFrame", result.slices.single().name)
        assertEquals(1, result.slices.single().count)
        assertEquals(300, result.slices.single().totalDurationNanos)
        assertEquals(42.0, result.counters.single().lastValue)
    }

    @Test
    fun skipsUnknownProtobufGroupsWithoutLosingLaterPackets() {
        val trace = trace(
            packet(
                1_000,
                group(99, varintField(1, 7)),
                internedData(eventName(1, "afterGroup")),
                trackEvent(3, 1, 1),
            ),
        )
        val result = PerfettoTraceAnalyzer().analyze(trace)
        assertTrue(result.valid)
        assertEquals("afterGroup", result.slices.single().name)
    }

    @Test
    fun summarizesSchedulingSlicesFromFtrace() {
        val trace = trace(
            packet(1_000, ftrace(
                schedSwitch(1_100, "previous", "next"),
                schedSwitch(1_400, "next", "other"),
            )),
        )
        val result = PerfettoTraceAnalyzer().analyze(trace)
        assertTrue(result.valid)
        assertEquals(2, result.ftraceEventCount)
        assertEquals("next", result.slices.single().name)
        assertEquals(300, result.slices.single().totalDurationNanos)
    }

    @Test
    fun timeRangeKeepsOnlySelectedEvents() {
        val trace = trace(
            packet(1_000, internedData(eventName(1, "early")), trackEvent(3, 1, 1)),
            packet(2_000, internedData(eventName(2, "late")), trackEvent(3, 2, 1)),
        )
        val result = PerfettoTraceAnalyzer().analyze(trace, fromNanos = 1_500, toNanos = 2_500)
        assertEquals("late", result.slices.single().name)
    }

    private fun trace(vararg packets: ByteArray): ByteArray =
        packets.fold(ByteArrayOutputStream()) { output, packet ->
            output.write(field(1, packet))
            output
        }.toByteArray()

    private fun packet(timestamp: Long, vararg fields: ByteArray): ByteArray =
        concat(varintField(8, timestamp), *fields)

    private fun internedData(vararg entries: ByteArray): ByteArray = field(12, concat(*entries))

    private fun eventName(iid: Long, name: String): ByteArray =
        field(2, concat(varintField(1, iid), field(2, name.toByteArray())))

    private fun processTree(pid: Long, name: String): ByteArray =
        field(2, field(1, concat(
            varintField(1, pid),
            field(3, name.toByteArray()),
            field(3, "--fixture-argument".toByteArray()),
        )))

    private fun ftrace(vararg events: ByteArray): ByteArray =
        field(1, concat(varintField(1, 0), *events.map { field(2, it) }.toTypedArray()))

    private fun schedSwitch(timestamp: Long, previous: String, next: String): ByteArray =
        concat(
            varintField(1, timestamp),
            field(4, concat(field(5, previous.toByteArray()), field(5, next.toByteArray()))),
        )

    private fun trackDescriptor(uuid: Long, name: String): ByteArray =
        field(60, concat(varintField(1, uuid), field(2, name.toByteArray())))

    private fun trackEvent(type: Long, nameIid: Long, trackUuid: Long, counter: Long? = null): ByteArray =
        field(11, concat(
            varintField(9, type),
            varintField(10, nameIid),
            varintField(11, trackUuid),
            counter?.let { varintField(30, it) } ?: ByteArray(0),
        ))

    private fun varint(value: Long): ByteArray {
        var remaining = value
        val output = ByteArrayOutputStream()
        while (remaining and -128L != 0L) {
            output.write(((remaining and 0x7f) or 0x80).toInt())
            remaining = remaining ushr 7
        }
        output.write(remaining.toInt())
        return output.toByteArray()
    }

    private fun field(number: Int, payload: ByteArray): ByteArray =
        concat(varint((number.toLong() shl 3) or 2), varint(payload.size.toLong()), payload)

    private fun varintField(number: Int, value: Long): ByteArray =
        concat(varint(number.toLong() shl 3), varint(value))

    private fun group(number: Int, payload: ByteArray): ByteArray =
        concat(varint((number.toLong() shl 3) or 3), payload, varint((number.toLong() shl 3) or 4))

    private fun concat(vararg parts: ByteArray): ByteArray =
        parts.fold(ByteArrayOutputStream()) { output, part ->
            output.write(part)
            output
        }.toByteArray()
}

