package dev.admcp.core.model

import java.time.Instant

enum class ClockDomain { HOST_MONOTONIC, DEVICE_MONOTONIC, UNKNOWN }
enum class ClockCalibrationStatus { VERIFIED, ESTIMATED, UNAVAILABLE }

data class ClockCalibration(
    val sourceClock: ClockDomain,
    val targetClock: ClockDomain,
    val offsetNanos: Long?,
    val uncertaintyNanos: Long?,
    val sampleCount: Int,
    val measuredAt: Instant,
    val method: String,
    val status: ClockCalibrationStatus,
) {
    init {
        require(sampleCount >= 0) { "sampleCount must not be negative" }
        require(uncertaintyNanos == null || uncertaintyNanos >= 0) { "uncertainty must not be negative" }
    }
}

enum class CoverageGapReason {
    ADB_DISCONNECTED, DEVICE_REBOOTED, BUFFER_UNAVAILABLE, COLLECTOR_BACKPRESSURE,
    WRITER_FAILURE, SESSION_STOPPED, SOURCE_GAP, UNKNOWN,
}
enum class CoverageStatus { COMPLETE, PARTIAL, UNVERIFIED, FAILED }

data class CoverageGap(val startedAt: Instant, val endedAt: Instant?, val reason: CoverageGapReason, val exactLostRecords: Long?)
