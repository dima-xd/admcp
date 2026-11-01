package dev.admcp.events

import dev.admcp.core.model.ArtifactId
import dev.admcp.core.model.ClockCalibration
import dev.admcp.core.model.ClockDomain
import dev.admcp.core.model.EventId
import dev.admcp.core.model.OperationId
import dev.admcp.core.model.ProcessRef
import dev.admcp.core.model.SessionId
import kotlinx.serialization.json.JsonObject
import java.time.Instant

enum class EventType {
    SESSION, DEVICE, PROCESS, BACKEND, EXECUTION, BREAKPOINT, LOG, UI, ARTIFACT, CAPABILITY, DIAGNOSTIC, CLOCK, LIFECYCLE,
}

enum class EventSource { HOST, ADB, ANDROID_CLI, JDI, LLDB, DAP, LOGCAT, MCP, UI, SYSTEM }

enum class EventSeverity { TRACE, DEBUG, INFO, WARN, ERROR, FATAL }

data class EventEnvelope(
    val id: EventId,
    val sessionId: SessionId,
    val type: EventType,
    val source: EventSource,
    val hostTimestamp: Instant,
    val deviceTimestamp: Instant?,
    val monotonicNanos: Long?,
    val monotonicClockDomain: ClockDomain,
    val process: ProcessRef?,
    val tid: Long?,
    val severity: EventSeverity?,
    val sourceSequence: Long?,
    val operationId: OperationId?,
    val clockCalibration: ClockCalibration?,
    val artifactRefs: List<ArtifactId>,
    val data: JsonObject,
)

data class EventQuery(
    val sessionId: SessionId? = null,
    val fromHostTime: Instant? = null,
    val toHostTime: Instant? = null,
    val types: Set<EventType> = emptySet(),
    val limit: Int = 200,
    val cursor: String? = null,
)

data class EventPage(
    val events: List<EventEnvelope>,
    val nextCursor: String?,
)

class EventStoreCorruptionException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
