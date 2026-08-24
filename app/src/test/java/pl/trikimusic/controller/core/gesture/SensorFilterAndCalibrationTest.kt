package pl.trikimusic.controller.core.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.trikimusic.controller.domain.model.CalibrationProfile
import pl.trikimusic.controller.domain.model.GestureThresholds
import pl.trikimusic.controller.domain.model.RawVector3
import pl.trikimusic.controller.domain.model.TrikiSensorData
import pl.trikimusic.controller.domain.model.Vector3

class SensorFilterAndCalibrationTest {
    @Test
    fun `low pass filter smooths abrupt sensor change`() {
        val filter = SensorFilter()
        val thresholds = GestureThresholds(filterAlpha = 0.25f)
        filter.process(sample(0L, accel = Vector3(0f, 0f, 1f)), CalibrationProfile(), thresholds)

        val result = filter.process(sample(10_000_000L, accel = Vector3(1f, 0f, 1f)), CalibrationProfile(), thresholds)

        assertEquals(0.25f, result.accelerometerG.x, 0.0001f)
    }

    @Test
    fun `filter removes calibrated gyroscope bias`() {
        val filter = SensorFilter()
        val calibration = CalibrationProfile(gyroscopeBiasX = 4f, gyroscopeBiasY = -2f, gyroscopeBiasZ = 1f)

        val result = filter.process(
            sample(0L, gyro = Vector3(4f, -2f, 1f)),
            calibration,
            GestureThresholds(),
        )

        assertEquals(0f, result.gyroscopeMagnitude, 0.0001f)
    }

    @Test
    fun `median stage rejects one sample spike from both sensors`() {
        val filter = SensorFilter()
        val calibration = CalibrationProfile()
        val thresholds = GestureThresholds(filterAlpha = 1f)
        filter.process(sample(0L), calibration, thresholds)
        filter.process(sample(10_000_000L), calibration, thresholds)

        val spike = filter.process(
            sample(
                20_000_000L,
                gyro = Vector3(900f, -700f, 500f),
                accel = Vector3(8f, -6f, 5f),
            ),
            calibration,
            thresholds,
        )

        assertEquals(0f, spike.gyroscopeMagnitude, 0.0001f)
        assertEquals(0f, spike.accelerometerG.x, 0.0001f)
        assertEquals(0f, spike.accelerometerG.y, 0.0001f)
        assertEquals(1f, spike.accelerometerG.z, 0.0001f)
    }

    @Test
    fun `calibration computes stable bias and valid profile`() {
        val samples = List(120) { index ->
            sample(
                index * 10_000_000L,
                gyro = Vector3(1.5f, -0.5f, 0.25f),
                accel = Vector3(0.02f, -0.01f, 1.03f),
            )
        }

        val result = CalibrationCalculator.calculate(samples, 1234L)

        assertTrue(result.isValid)
        assertEquals(1.5f, result.gyroscopeBiasX, 0.001f)
        assertEquals(120, result.sampleCount)
        assertEquals(1234L, result.calibratedAtMillis)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `calibration rejects too few samples`() {
        CalibrationCalculator.calculate(List(10) { sample(it.toLong()) }, 1L)
    }

    private fun sample(
        timestamp: Long,
        gyro: Vector3 = Vector3(0f, 0f, 0f),
        accel: Vector3 = Vector3(0f, 0f, 1f),
    ) = TrikiSensorData(
        timestamp,
        timestamp,
        gyro,
        accel,
        RawVector3(0, 0, 0),
        RawVector3(0, 0, 0),
        0,
    )
}
