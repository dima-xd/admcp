package dev.admcp.logcat

import dev.admcp.core.model.CoverageGap
import dev.admcp.core.model.CoverageGapReason
import dev.admcp.core.model.CoverageStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import java.time.Instant

data class LogRecord(
    val timestamp: String,
    val pid: Int?,
    val tid: Int?,
    val level: String,
    val tag: String,
    val message: String,
    val raw: String,
)

data class LogcatBatch(
    val records: List<LogRecord>,
    val rawText: String,
    val overflow: Boolean,
    val gaps: List<CoverageGap> = emptyList(),
)

data class LogcatCoverageResult(val batches: List<LogcatBatch>, val status: CoverageStatus, val gaps: List<CoverageGap>)

fun interface LogcatSource {
    fun open(): Flow<ByteArray>
}

class LogcatParser {
    private val header = Regex(
        """^(\d\d-\d\d)\s+(\d\d:\d\d:\d\d\.\d+)\s+(\d+)\s+(\d+)\s+([VDIWEFA])\s+(.+?)\s*:\s?(.*)$""",
    )

    fun parse(text: String): List<LogRecord> {
        val records = mutableListOf<MutableList<String>>()
        text.lineSequence().forEach { line ->
            val match = header.find(line)
            if (match != null) {
                records += mutableListOf(line)
            } else {
                if (records.isEmpty()) records += mutableListOf(line) else records.last() += line
            }
        }
        return records.map { lines ->
            val raw = lines.joinToString("\n")
            val match = header.find(lines.first())
            if (match == null) {
                LogRecord("", null, null, "?", "", lines.first(), lines.first().trimEnd('\n'))
            } else {
                LogRecord(
                    timestamp = "10-31 ${match.groupValues[2]}",
                    pid = match.groupValues[3].toIntOrNull(),
                    tid = match.groupValues[4].toIntOrNull(),
                    level = match.groupValues[5],
                    tag = match.groupValues[6],
                    message = lines.drop(1).joinToString("\n").ifBlank { match.groupValues[7] },
                    raw = raw,
                )
            }
        }
    }
}

class LogcatCollector(
    private val source: LogcatSource,
    private val maxRecords: Int = 20_000,
    private val reconnect: Boolean = false,
    private val parser: LogcatParser = LogcatParser(),
) {
    private val fingerprints = LinkedHashSet<String>()

    fun collect(): Flow<LogcatBatch> = flow {
        val gaps = mutableListOf<CoverageGap>()
        var streams = 0
        while (streams == 0 || reconnect) {
            streams++
            try {
                var raw = StringBuilder()
                source.open().collect { chunk -> raw.append(chunk.toString(Charsets.UTF_8)) }
                val records = parser.parse(raw.toString())
                val unique = mutableListOf<LogRecord>()
                var overflow = false
                records.forEach { record ->
                    val fingerprint = fingerprint(record)
                    if (fingerprints.add(fingerprint)) {
                        if (unique.size < maxRecords) unique += record else overflow = true
                    }
                }
                if (fingerprints.size > maxRecords * 2) {
                    val keep = fingerprints.toList().takeLast(maxRecords)
                    fingerprints.clear()
                    fingerprints.addAll(keep)
                }
                emit(LogcatBatch(unique, raw.toString(), overflow, gaps.toList()))
                gaps.clear()
            } catch (error: Exception) {
                gaps += CoverageGap(Instant.now(), Instant.now(), CoverageGapReason.ADB_DISCONNECTED, null)
                if (!reconnect) {
                    emit(LogcatBatch(emptyList(), "", false, gaps.toList()))
                    break
                }
            }
            if (!reconnect) break
        }
    }

    suspend fun collectWithCoverage(): LogcatCoverageResult {
        val batches = mutableListOf<LogcatBatch>()
        collect().collect { batches += it }
        val gaps = batches.flatMap { it.gaps }
        val status = if (gaps.isNotEmpty()) CoverageStatus.PARTIAL else CoverageStatus.COMPLETE
        return LogcatCoverageResult(batches, status, gaps)
    }

    private fun fingerprint(record: LogRecord): String =
        listOf(record.timestamp, record.pid, record.tid, record.level, record.tag, record.message).joinToString("|")
}

class LogcatSafetyPolicy {
    private val forbidden = setOf("-c", "--clear", "-G", "--buffer-size", "-b", "--buffer")
    fun commandsAreReadOnly(args: List<String>): Boolean =
        args.none { it in setOf("-c", "--clear", "-G", "--buffer-size") } && !args.contains("clear")
}

object LogcatSourceFactory {
    fun static(text: String): LogcatSource = LogcatSource { flowOf(text.toByteArray()) }
}
