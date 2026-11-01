package dev.admcp.profiler

import java.nio.charset.StandardCharsets

data class TraceProcess(
    val pid: Long,
    val name: String?,
)

data class TraceThread(
    val tid: Long,
    val tgid: Long?,
    val name: String?,
)

data class TraceSlice(
    val name: String,
    val trackUuid: Long?,
    val count: Long,
    val totalDurationNanos: Long,
    val maxDurationNanos: Long,
)

data class TraceCounter(
    val name: String,
    val trackUuid: Long?,
    val samples: Long,
    val minValue: Double,
    val maxValue: Double,
    val lastValue: Double,
)

data class TraceAnalysis(
    val valid: Boolean,
    val packetCount: Long,
    val ftraceEventCount: Long,
    val schedSwitchCount: Long,
    val firstTimestampNanos: Long?,
    val lastTimestampNanos: Long?,
    val durationNanos: Long?,
    val processes: List<TraceProcess>,
    val threads: List<TraceThread>,
    val slices: List<TraceSlice>,
    val counters: List<TraceCounter>,
    val errors: List<String>,
    val limitations: List<String>,
)

/**
 * Bounded Perfetto protobuf reader.
 *
 * It intentionally extracts only stable, frequently populated fields. Full SQL
 * analysis still belongs to trace_processor; this analyzer provides useful
 * summaries without an external binary and never loads a trace into SQLite.
 */
class PerfettoTraceAnalyzer {
    fun analyze(bytes: ByteArray, fromNanos: Long? = null, toNanos: Long? = null): TraceAnalysis {
        if (bytes.isEmpty()) {
            return failed("trace is empty")
        }
        val state = State(fromNanos, toNanos)
        val reader = ProtoReader(bytes)
        try {
            while (!reader.eof) {
                val tag = reader.varint()
                val field = (tag ushr 3).toInt()
                val wire = (tag and 7L).toInt()
                if (field == 1 && wire == 2) {
                    val length = reader.length()
                    val packet = reader.subReader(length)
                    state.packetCount++
                    parsePacket(packet, state)
                } else {
                    reader.skip(wire)
                }
            }
        } catch (failure: Exception) {
            state.errors += "trace protobuf parse stopped at packet ${state.packetCount} offset ${reader.offset}: " +
                (failure.message ?: failure::class.simpleName)
        }
        val duration = if (state.firstTimestamp != null && state.lastTimestamp != null) {
            (state.lastTimestamp!! - state.firstTimestamp!!).coerceAtLeast(0)
        } else {
            null
        }
        val limitations = buildList {
            add("summary only; run trace_processor for SQL, frame lifecycle and scheduling analysis")
            if (state.internedNames.isEmpty()) add("no interned event names were found")
            if (state.slices.isEmpty()) {
                add(
                    if (state.ftraceEventCount > 0) {
                        "ftrace events are counted but not expanded into named slices in this build"
                    } else {
                        "no track_event slices were found"
                    },
                )
            }
        }
        return TraceAnalysis(
            valid = state.packetCount > 0 && state.errors.isEmpty(),
            packetCount = state.packetCount,
            ftraceEventCount = state.ftraceEventCount,
            schedSwitchCount = state.schedSwitchCount,
            firstTimestampNanos = state.firstTimestamp,
            lastTimestampNanos = state.lastTimestamp,
            durationNanos = duration,
            processes = state.processes.values.sortedBy { it.pid },
            threads = state.threads.values.sortedWith(compareBy({ it.tgid ?: Long.MAX_VALUE }, { it.tid })),
            slices = state.slices.values
                .map { aggregate ->
                    TraceSlice(
                        name = aggregate.name,
                        trackUuid = aggregate.trackUuid,
                        count = aggregate.count,
                        totalDurationNanos = aggregate.total,
                        maxDurationNanos = aggregate.max,
                    )
                }
                .sortedWith(compareByDescending<TraceSlice> { it.totalDurationNanos }.thenByDescending { it.count })
                .take(200),
            counters = state.counters.values
                .map { aggregate ->
                    TraceCounter(
                        name = aggregate.name,
                        trackUuid = aggregate.trackUuid,
                        samples = aggregate.samples,
                        minValue = aggregate.min,
                        maxValue = aggregate.max,
                        lastValue = aggregate.last,
                    )
                }
                .sortedBy { it.name }
                .take(200),
            errors = state.errors,
            limitations = limitations,
        )
    }

    private fun failed(message: String) = TraceAnalysis(
        valid = false,
        packetCount = 0,
        ftraceEventCount = 0,
        schedSwitchCount = 0,
        firstTimestampNanos = null,
        lastTimestampNanos = null,
        durationNanos = null,
        processes = emptyList(),
        threads = emptyList(),
        slices = emptyList(),
        counters = emptyList(),
        errors = listOf(message),
        limitations = listOf("no trace data was analyzed"),
    )

    private fun parsePacket(packet: ProtoReader, state: State) {
        var timestamp: Long? = null
        val timestampReader = packet.copy()
        while (!timestampReader.eof) {
            val tag = timestampReader.varint()
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7L).toInt()
            if (field == 8 && wire == 0) timestamp = timestampReader.varint()
            else timestampReader.skip(wire)
        }
        timestamp?.let {
            state.firstTimestamp = minOf(state.firstTimestamp ?: it, it)
            state.lastTimestamp = maxOf(state.lastTimestamp ?: it, it)
        }
        val content = packet.copy()
        while (!content.eof) {
            val tag = content.varint()
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7L).toInt()
            when {
                field == 1 && wire == 2 -> parseFtrace(content.message(), state)
                field == 2 && wire == 2 -> parseProcessTree(content.message(), state)
                field == 11 && wire == 2 -> parseTrackEvent(content.message(), timestamp, state)
                field == 12 && wire == 2 -> parseInternedData(content.message(), state)
                field == 60 && wire == 2 -> parseTrackDescriptor(content.message(), state)
                else -> content.skip(wire)
            }
        }
    }

    private fun parseFtrace(bundle: ProtoReader, state: State) {
        var cpu: Int? = null
        while (!bundle.eof) {
            val tag = bundle.varint()
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7L).toInt()
            when {
                field == 1 && wire == 0 -> cpu = bundle.varint().toInt()
                field == 2 && wire == 2 -> {
                    state.ftraceEventCount++
                    parseFtraceEvent(bundle.message(), cpu, state)
                }
                else -> bundle.skip(wire)
            }
        }
    }

    private fun parseFtraceEvent(event: ProtoReader, cpu: Int?, state: State) {
        var timestamp: Long? = null
        var switchNames: List<String> = emptyList()
        while (!event.eof) {
            val tag = event.varint()
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7L).toInt()
            when {
                field == 1 && wire == 0 -> timestamp = event.varint()
                field == 4 && wire == 2 -> switchNames = parseSchedSwitch(event.message())
                else -> event.skip(wire)
            }
        }
        val at = timestamp ?: return
        if (!state.inRange(at)) return
        if (switchNames.size >= 2) {
            state.schedSwitchCount++
            state.recordSchedSwitch(cpu, at, switchNames[0])
        }
    }

    private fun parseSchedSwitch(event: ProtoReader): List<String> {
        val names = mutableListOf<String>()
        while (!event.eof) {
            val tag = event.varint()
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7L).toInt()
            if (wire == 2) {
                val value = event.string(event.length())
                if (names.size < 2) names += value
            } else {
                event.skip(wire)
            }
        }
        return names
    }

    private fun parseInternedData(data: ProtoReader, state: State) {
        while (!data.eof) {
            val tag = data.varint()
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7L).toInt()
            if (field == 2 && wire == 2) {
                parseInternedName(data.message(), state)
            } else {
                data.skip(wire)
            }
        }
    }

    private fun parseInternedName(name: ProtoReader, state: State) {
        var iid: Long? = null
        var value: String? = null
        while (!name.eof) {
            val tag = name.varint()
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7L).toInt()
            when {
                field == 1 && wire == 0 -> iid = name.varint()
                field == 2 && wire == 2 -> value = name.string(name.length())
                else -> name.skip(wire)
            }
        }
        if (iid != null && value != null) state.internedNames[iid!!] = value!!
    }

    private fun parseProcessTree(tree: ProtoReader, state: State) {
        while (!tree.eof) {
            val tag = tree.varint()
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7L).toInt()
            when {
                field == 1 && wire == 2 -> parseProcess(tree.message(), state)
                field == 2 && wire == 2 -> parseThread(tree.message(), state)
                else -> tree.skip(wire)
            }
        }
    }

    private fun parseProcess(process: ProtoReader, state: State) {
        var pid: Long? = null
        var name: String? = null
        while (!process.eof) {
            val tag = process.varint()
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7L).toInt()
            when {
                field == 1 && wire == 0 -> pid = process.varint()
                field == 3 && wire == 2 -> {
                    val candidate = process.string(process.length())
                    if (name == null) name = candidate
                }
                else -> process.skip(wire)
            }
        }
        if (pid != null) state.processes[pid!!] = TraceProcess(pid!!, name)
    }

    private fun parseThread(thread: ProtoReader, state: State) {
        var tid: Long? = null
        var tgid: Long? = null
        var name: String? = null
        while (!thread.eof) {
            val tag = thread.varint()
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7L).toInt()
            when {
                field == 1 && wire == 0 -> tid = thread.varint()
                field == 2 && wire == 2 -> name = thread.string(thread.length())
                field == 3 && wire == 0 -> tgid = thread.varint()
                else -> thread.skip(wire)
            }
        }
        if (tid != null) state.threads[tid!!] = TraceThread(tid!!, tgid, name)
    }

    private fun parseTrackDescriptor(descriptor: ProtoReader, state: State) {
        var uuid: Long? = null
        var name: String? = null
        while (!descriptor.eof) {
            val tag = descriptor.varint()
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7L).toInt()
            when {
                field == 1 && wire == 0 -> uuid = descriptor.varint()
                field == 2 && wire == 2 -> name = descriptor.string(descriptor.length())
                field == 3 && wire == 2 -> name = parseProcessDescriptor(descriptor.message()) ?: name
                field == 4 && wire == 2 -> name = parseThreadDescriptor(descriptor.message()) ?: name
                else -> descriptor.skip(wire)
            }
        }
        if (uuid != null && name != null) state.trackNames[uuid!!] = name!!
    }

    private fun parseProcessDescriptor(descriptor: ProtoReader): String? {
        var name: String? = null
        while (!descriptor.eof) {
            val tag = descriptor.varint()
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7L).toInt()
            if (field == 2 && wire == 2) name = descriptor.string(descriptor.length())
            else descriptor.skip(wire)
        }
        return name
    }

    private fun parseThreadDescriptor(descriptor: ProtoReader): String? {
        var name: String? = null
        while (!descriptor.eof) {
            val tag = descriptor.varint()
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7L).toInt()
            if (field == 3 && wire == 2) name = descriptor.string(descriptor.length())
            else descriptor.skip(wire)
        }
        return name
    }

    private fun parseTrackEvent(event: ProtoReader, timestamp: Long?, state: State) {
        var type = 0L
        var nameIid: Long? = null
        var trackUuid: Long? = null
        var counter: Double? = null
        while (!event.eof) {
            val tag = event.varint()
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7L).toInt()
            when {
                field == 9 && wire == 0 -> type = event.varint()
                field == 10 && wire == 0 -> nameIid = event.varint()
                field == 11 && wire == 0 -> trackUuid = event.varint()
                field == 30 && wire == 0 -> counter = event.varint().toDouble()
                field == 44 && wire == 1 -> counter = Double.fromBits(event.fixed64())
                else -> event.skip(wire)
            }
        }
        if (timestamp == null || !state.inRange(timestamp)) return
        val name = nameIid?.let { state.internedNames[it] }
            ?: trackUuid?.let { state.trackNames[it] }
            ?: nameIid?.let { "event-$it" }
            ?: "unnamed"
        when (type) {
            1L -> state.openSlices.getOrPut(trackUuid) { mutableListOf() }.add(OpenSlice(name, timestamp))
            2L -> {
                val open = state.openSlices[trackUuid]?.removeLastOrNull() ?: return
                val duration = (timestamp - open.startNanos).coerceAtLeast(0)
                state.recordSlice(open.name, trackUuid, duration)
            }
            3L -> state.recordSlice(name, trackUuid, 0)
            4L -> counter?.let { value ->
                state.recordCounter(name, trackUuid, value, timestamp)
            }
        }
    }

    private data class OpenSlice(val name: String, val startNanos: Long)

    private class State(val fromNanos: Long?, val toNanos: Long?) {
        var packetCount = 0L
        var ftraceEventCount = 0L
        var schedSwitchCount = 0L
        var firstTimestamp: Long? = null
        var lastTimestamp: Long? = null
        val internedNames = mutableMapOf<Long, String>()
        val trackNames = mutableMapOf<Long, String>()
        val processes = mutableMapOf<Long, TraceProcess>()
        val threads = mutableMapOf<Long, TraceThread>()
        val openSlices = mutableMapOf<Long?, MutableList<OpenSlice>>()
        val lastSwitchNanos = mutableMapOf<Int, Long>()
        val slices = mutableMapOf<String, SliceAggregate>()
        val counters = mutableMapOf<String, CounterAggregate>()
        val errors = mutableListOf<String>()

        fun inRange(timestamp: Long): Boolean =
            (fromNanos == null || timestamp >= fromNanos) && (toNanos == null || timestamp <= toNanos)

        fun recordSlice(name: String, trackUuid: Long?, duration: Long) {
            val key = "$name|${trackUuid ?: -1}"
            val aggregate = slices.getOrPut(key) { SliceAggregate(name, trackUuid) }
            aggregate.count++
            aggregate.total += duration
            aggregate.max = maxOf(aggregate.max, duration)
        }

        fun recordSchedSwitch(cpu: Int?, timestamp: Long, previousName: String) {
            val key = cpu ?: -1
            val previous = lastSwitchNanos.put(key, timestamp)
            if (previous != null && timestamp > previous) {
                recordSlice(previousName, null, timestamp - previous)
            }
        }

        fun recordCounter(name: String, trackUuid: Long?, value: Double, timestamp: Long) {
            val key = "$name|${trackUuid ?: -1}"
            val aggregate = counters.getOrPut(key) { CounterAggregate(name, trackUuid) }
            if (aggregate.samples == 0L) {
                aggregate.min = value
                aggregate.max = value
                aggregate.last = value
                aggregate.lastTimestamp = timestamp
            } else {
                aggregate.min = minOf(aggregate.min, value)
                aggregate.max = maxOf(aggregate.max, value)
                if (timestamp >= aggregate.lastTimestamp) {
                    aggregate.last = value
                    aggregate.lastTimestamp = timestamp
                }
            }
            aggregate.samples++
        }
    }

    private class SliceAggregate(val name: String, val trackUuid: Long?) {
        var count = 0L
        var total = 0L
        var max = 0L
    }

    private class CounterAggregate(val name: String, val trackUuid: Long?) {
        var samples = 0L
        var min = 0.0
        var max = 0.0
        var last = 0.0
        var lastTimestamp = Long.MIN_VALUE
    }
}

private class ProtoReader(private val bytes: ByteArray, private var position: Int = 0, private val limit: Int = bytes.size) {
    val eof: Boolean get() = position >= limit
    val offset: Int get() = position

    fun copy(): ProtoReader = ProtoReader(bytes, position, limit)

    fun subReader(length: Int): ProtoReader {
        val end = position + length
        require(length >= 0 && end <= limit) { "truncated protobuf field" }
        val child = ProtoReader(bytes, position, end)
        position = end
        return child
    }

    fun message(): ProtoReader {
        val length = length()
        return subReader(length)
    }

    fun varint(): Long {
        var result = 0L
        var shift = 0
        while (shift < 64) {
            val value = readByte()
            result = result or ((value and 0x7f).toLong() shl shift)
            if (value and 0x80 == 0) return result
            shift += 7
        }
        throw IllegalArgumentException("malformed protobuf varint")
    }

    fun fixed64(): Long {
        var result = 0L
        for (index in 0 until 8) {
            result = result or (readByte().toLong() shl (index * 8))
        }
        return result
    }

    fun length(): Int {
        val value = varint()
        require(value in 0..Int.MAX_VALUE) { "protobuf field is too large" }
        return value.toInt()
    }

    fun string(length: Int): String {
        val slice = bytes.copyOfRange(position, position + length)
        position += length
        require(position <= limit) { "truncated protobuf string" }
        return String(slice, StandardCharsets.UTF_8)
    }

    fun skip(wire: Int) {
        when (wire) {
            0 -> varint()
            1 -> position = (position + 8).also { require(it <= limit) { "truncated fixed64" } }
            2 -> {
                val length = length()
                position = (position + length).also { require(it <= limit) { "truncated length-delimited field" } }
            }
            3 -> {
                // Deprecated protobuf groups still appear in exported traces.
                while (!eof) {
                    val tag = varint()
                    val nestedWire = (tag and 7L).toInt()
                    if (nestedWire == 4) return
                    skip(nestedWire)
                }
            }
            4 -> return
            5 -> position = (position + 4).also { require(it <= limit) { "truncated fixed32" } }
            else -> throw IllegalArgumentException("unsupported protobuf wire type $wire")
        }
    }

    private fun readByte(): Int {
        require(position < limit) { "unexpected end of protobuf input" }
        return bytes[position++].toInt() and 0xff
    }
}
