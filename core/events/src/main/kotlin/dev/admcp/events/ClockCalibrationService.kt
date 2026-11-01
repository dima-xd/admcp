package dev.admcp.events

import dev.admcp.core.clock.ClockCalibrator
import dev.admcp.core.clock.Sample
import dev.admcp.core.model.ClockCalibration
import dev.admcp.core.model.DeviceId
import java.util.concurrent.ConcurrentHashMap

fun interface ClockSampleProvider {
    suspend fun sample(deviceId: DeviceId): Sample
}

interface ClockCalibrationService {
    suspend fun calibrate(deviceId: DeviceId, samples: Int = 5): ClockCalibration
    suspend fun latest(deviceId: DeviceId): ClockCalibration?
}

class DefaultClockCalibrationService(
    private val provider: ClockSampleProvider,
    private val calibrator: ClockCalibrator = ClockCalibrator(),
) : ClockCalibrationService {
    private val latest = ConcurrentHashMap<DeviceId, ClockCalibration>()

    override suspend fun calibrate(deviceId: DeviceId, samples: Int): ClockCalibration {
        require(samples in 1..50) { "sample count must be between 1 and 50" }
        val collected = (1..samples).map { provider.sample(deviceId) }
        val calibration = calibrator.calibrate(collected, { System.nanoTime() }, { System.nanoTime() })
        latest[deviceId] = calibration
        return calibration
    }

    override suspend fun latest(deviceId: DeviceId): ClockCalibration? = latest[deviceId]
}
