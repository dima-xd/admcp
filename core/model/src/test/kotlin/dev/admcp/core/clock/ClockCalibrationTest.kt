package dev.admcp.core.clock

import dev.admcp.core.model.ClockCalibrationStatus
import dev.admcp.core.model.ClockDomain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ClockCalibrationTest {
    @Test
    fun estimatesOffsetFromMidpointSamples() {
        val calibrator = ClockCalibrator(minSamples = 3)
        val result = calibrator.calibrate(
            samples = listOf(
                Sample(100, 300, 300),
                Sample(200, 400, 400),
                Sample(300, 500, 500),
            ),
            deviceNow = { 4000 },
            hostNow = { 4000 + 200 },
        )
        assertEquals(ClockDomain.DEVICE_MONOTONIC, result.sourceClock)
        assertEquals(ClockCalibrationStatus.VERIFIED, result.status)
        assertTrue((result.offsetNanos ?: 0) in 199..201)
        assertTrue((result.uncertaintyNanos ?: 999) < 50)
    }

    @Test
    fun rejectsNonMonotonicSamples() {
        assertFailsWith<IllegalArgumentException> {
            ClockCalibrator(minSamples = 2).calibrate(
                listOf(Sample(100, 100, 200), Sample(90, 200, 300)),
                deviceNow = { 0 },
                hostNow = { 0 },
            )
        }
    }

    @Test
    fun reportsUnavailableWithoutEnoughSamples() {
        val result = ClockCalibrator(minSamples = 3).calibrate(
            listOf(Sample(100, 100, 120)),
            deviceNow = { 0 },
            hostNow = { 0 },
        )
        assertEquals(ClockCalibrationStatus.UNAVAILABLE, result.status)
        assertEquals(null, result.offsetNanos)
        assertTrue(result.uncertaintyNanos == null)
    }
}
