package dev.admcp.logcat

import kotlinx.coroutines.CoroutineScope
import dev.admcp.core.model.CoverageGap
import dev.admcp.core.model.CoverageGapReason
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant

enum class LogcatFormat { TEXT, BINARY, PROTO }

data class LogcatCaptureConfig(
    val buffers: Set<String> = setOf("main", "system", "crash"),
    val format: LogcatFormat = LogcatFormat.TEXT,
    val segmentBytes: Long = 8L * 1024 * 1024,
    val maxSessionBytes: Long = 256L * 1024 * 1024,
)

data class LogSegment(
    val path: Path,
    val bytes: Long,
    val sha256: String,
    val createdAt: Instant,
)

data class CaptureHealth(
    val state: String,
    val bytesWritten: Long,
    val segments: Int,
    val droppedBytes: Long,
    val quotaReached: Boolean,
    val error: String?,
)

class RawSegmentWriter(
    root: Path,
    private val segmentBytes: Long = 8L * 1024 * 1024,
    private val maxSessionBytes: Long = 256L * 1024 * 1024,
) {
    private val directory = root.resolve("segments").also(Files::createDirectories)
    private val segments = mutableListOf<LogSegment>()
    private var current: Path? = null
    private var currentBytes = 0L
    private var total = 0L
    private var dropped = 0L
    private var quotaReached = false
    private var failure: String? = null

    @Synchronized
    fun append(bytes: ByteArray): Boolean {
        if (failure != null) return false
        if (total + bytes.size > maxSessionBytes) {
            dropped += bytes.size
            quotaReached = true
            return false
        }
        if (current == null || currentBytes + bytes.size > segmentBytes) {
            closeCurrent()
            current = directory.resolve("segment-$total-${segments.size}.log")
            currentBytes = 0
        }
        val path = current!!
        try {
            Files.write(path, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            currentBytes += bytes.size
            total += bytes.size
            return true
        } catch (error: Exception) {
            failure = error.message ?: error::class.simpleName
            return false
        }
    }

    private fun closeCurrent() {
        val path = current ?: return
        if (!Files.exists(path)) return
        val digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }
        segments += LogSegment(path, Files.size(path), digest, Instant.now())
    }

    @Synchronized
    fun finish(): List<LogSegment> {
        closeCurrent()
        current = null
        currentBytes = 0
        return segments.toList()
    }
    @Synchronized
    fun health() = CaptureHealth(
        state = when {
            failure != null -> "FAILED"
            quotaReached -> "DEGRADED"
            else -> "RUNNING"
        },
        bytesWritten = total,
        segments = segments.size + if (current != null) 1 else 0,
        droppedBytes = dropped,
        quotaReached = quotaReached,
        error = failure,
    )

    fun readAll(): String = segments.joinToString("") { Files.readString(it.path) } +
        (current?.takeIf(Files::exists)?.let { Files.readString(it) } ?: "")
}

class LogcatCaptureSession(
    private val config: LogcatCaptureConfig,
    private val source: LogcatSource,
    root: Path,
) {
    private val writer = RawSegmentWriter(root, config.segmentBytes, config.maxSessionBytes)
    private val mutableState = MutableStateFlow(CaptureHealth("IDLE", 0, 0, 0, false, null))
    val state: StateFlow<CaptureHealth> = mutableState.asStateFlow()
    private var job: Job? = null
    private var startedAt = Instant.now()
    private val gaps = mutableListOf<CoverageGap>()

    fun start(scope: CoroutineScope) {
        check(job == null) { "capture session already started" }
        startedAt = Instant.now()
        job = scope.launch {
            try {
                source.open().collect { bytes ->
                    if (!writer.append(bytes)) {
                        val health = writer.health()
                        mutableState.value = health
                        if (health.quotaReached || health.error != null) throw java.io.IOException("raw log writer stopped")
                    } else {
                        mutableState.value = writer.health()
                    }
                }
            } catch (error: Exception) {
                if (writer.health().quotaReached) {
                    gaps += CoverageGap(startedAt, Instant.now(), CoverageGapReason.COLLECTOR_BACKPRESSURE, null)
                } else {
                    gaps += CoverageGap(startedAt, Instant.now(), CoverageGapReason.WRITER_FAILURE, null)
                    mutableState.value = writer.health().copy(state = "FAILED", error = error.message)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        writer.finish()
        mutableState.value = writer.health().copy(state = "STOPPED")
    }

    fun query(limit: Int = 200): List<LogRecord> =
        LogcatParser().parse(writer.readAll()).takeLast(limit.coerceIn(1, 5000))

    fun gaps(): List<CoverageGap> = gaps.toList()

    fun segments(): List<LogSegment> = writer.finish()
}
