package dev.admcp.core.clock

import dev.admcp.core.model.ClockCalibration
import dev.admcp.core.model.ClockCalibrationStatus
import dev.admcp.core.model.ClockDomain
import java.time.Instant

data class Sample(val deviceSendNanos: Long, val hostReceiveNanos: Long, val hostSendNanos: Long)

class ClockCalibrator(private val minSamples: Int = 3) {
    fun calibrate(samples: List<Sample>, deviceNow: () -> Long, hostNow: () -> Long): ClockCalibration {
        require(samples.zipWithNext().all { (a, b) -> b.deviceSendNanos > a.deviceSendNanos }) {
            "device samples must increase monotonically"
        }
        if (samples.size < minSamples) {
            return ClockCalibration(
                ClockDomain.DEVICE_MONOTONIC,
                ClockDomain.HOST_MONOTONIC,
                null,
                null,
                samples.size,
                Instant.now(),
                "midpoint",
                ClockCalibrationStatus.UNAVAILABLE,
            )
        }
        val offsets = samples.map { it.hostSendNanos - it.deviceSendNanos }.sorted()
        val offset = offsets[offsets.size / 2]
        val uncertainty = offsets.maxOfOrNull { kotlin.math.abs(it - offset) } ?: 0
        return ClockCalibration(
            ClockDomain.DEVICE_MONOTONIC,
            ClockDomain.HOST_MONOTONIC,
            offset,
            uncertainty,
            samples.size,
            Instant.now(),
            "midpoint",
            if (uncertainty <= 10) ClockCalibrationStatus.VERIFIED else ClockCalibrationStatus.ESTIMATED,
        )
    }
}
