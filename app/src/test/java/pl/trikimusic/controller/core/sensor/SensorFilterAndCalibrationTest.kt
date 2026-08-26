package pl.trikimusic.controller.core.sensor

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.trikimusic.controller.domain.model.CalibrationProfile
import pl.trikimusic.controller.domain.model.CURRENT_ORIENTATION_CONVENTION_VERSION
import pl.trikimusic.controller.domain.model.RawVector3
import pl.trikimusic.controller.domain.model.TrikiSensorData
import pl.trikimusic.controller.domain.model.Vector3

class SensorFilterAndCalibrationTest {
    @Test
    fun `low pass filter smooths abrupt sensor change`() {
        val filter = SensorFilter(filterAlpha = 0.25f)
        filter.process(sample(0L, accel = Vector3(0f, 0f, -1f)), CalibrationProfile())

        val result = filter.process(sample(10_000_000L, accel = Vector3(1f, 0f, -1f)), CalibrationProfile())

        assertEquals(0.25f, result.accelerometerG.x, 0.0001f)
    }

    @Test
    fun `filter removes calibrated gyroscope bias`() {
        val filter = SensorFilter()
        val calibration = CalibrationProfile(gyroscopeBiasX = 4f, gyroscopeBiasY = -2f, gyroscopeBiasZ = 1f)

        val result = filter.process(
            sample(0L, gyro = Vector3(4f, -2f, 1f)),
            calibration,
        )

        assertEquals(0f, result.gyroscopeMagnitude, 0.0001f)
    }

    @Test
    fun `median stage rejects one sample spike from both sensors`() {
        val filter = SensorFilter(filterAlpha = 1f)
        val calibration = CalibrationProfile()
        filter.process(sample(0L), calibration)
        filter.process(sample(10_000_000L), calibration)

        val spike = filter.process(
            sample(
                20_000_000L,
                gyro = Vector3(900f, -700f, 500f),
                accel = Vector3(8f, -6f, 5f),
            ),
            calibration,
        )

        assertEquals(0f, spike.gyroscopeMagnitude, 0.0001f)
        assertEquals(0f, spike.accelerometerG.x, 0.0001f)
        assertEquals(0f, spike.accelerometerG.y, 0.0001f)
        assertEquals(-1f, spike.accelerometerG.z, 0.0001f)
    }

    @Test
    fun `complementary orientation crosses angle wrap without a false jump`() {
        val filter = SensorFilter(filterAlpha = 1f)
        val positiveRadians = Math.toRadians(179.0)
        val negativeRadians = Math.toRadians(-179.0)
        var timestamp = 0L
        var result = filter.process(
            sample(timestamp, accel = Vector3(0f, sin(positiveRadians).toFloat(), -cos(positiveRadians).toFloat())),
            CalibrationProfile(),
        )
        repeat(180) {
            timestamp += 19_230_769L
            result = filter.process(
                sample(timestamp, accel = Vector3(0f, sin(positiveRadians).toFloat(), -cos(positiveRadians).toFloat())),
                CalibrationProfile(),
            )
        }
        timestamp += 19_230_769L
        result = filter.process(
            sample(timestamp, accel = Vector3(0f, sin(negativeRadians).toFloat(), -cos(negativeRadians).toFloat())),
            CalibrationProfile(),
        )

        assertTrue("Roll should stay near the ±180° boundary, got ${result.orientation.roll}", abs(abs(result.orientation.roll) - 180f) < 5f)
    }

    @Test
    fun `extreme calibration angles are normalized without iterative work`() {
        val result = SensorFilter(filterAlpha = 1f).process(
            sample(0L),
            CalibrationProfile(neutralPitch = Float.MAX_VALUE, neutralRoll = -Float.MAX_VALUE),
        )

        assertTrue(result.orientation.pitch.isFinite())
        assertTrue(result.orientation.roll.isFinite())
        assertTrue(result.orientation.pitch in -180f..180f)
        assertTrue(result.orientation.roll in -180f..180f)
    }

    @Test
    fun `calibration computes stable bias and valid profile`() {
        val samples = List(120) { index ->
            sample(
                index * 10_000_000L,
                gyro = Vector3(1.5f, -0.5f, 0.25f),
                accel = Vector3(0.02f, -0.01f, -1.03f),
            )
        }

        val result = CalibrationCalculator.calculate(samples, 1234L)

        assertTrue(result.isValid)
        assertEquals(1.5f, result.gyroscopeBiasX, 0.001f)
        assertEquals(120, result.sampleCount)
        assertEquals(1234L, result.calibratedAtMillis)
        assertEquals(CURRENT_ORIENTATION_CONVENTION_VERSION, result.orientationConventionVersion)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `calibration rejects too few samples`() {
        CalibrationCalculator.calculate(List(10) { sample(it.toLong()) }, 1L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `calibration rejects vertical orientation`() {
        val samples = List(120) { index ->
            sample(index * 10_000_000L, accel = Vector3(0f, -1f, 0f))
        }

        CalibrationCalculator.calculate(samples, 1L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `calibration rejects face-down orientation`() {
        val samples = List(120) { index ->
            sample(index * 10_000_000L, accel = Vector3(0f, 0f, 1f))
        }

        CalibrationCalculator.calculate(samples, 1L)
    }

    @Test
    fun `calibration accepts exactly twenty five degrees of face-up tilt`() {
        val radians = Math.toRadians(25.0)
        val gravity = Vector3(sin(radians).toFloat(), 0f, -cos(radians).toFloat())
        val samples = List(120) { index ->
            sample(index * 10_000_000L, accel = gravity)
        }

        val result = CalibrationCalculator.calculate(samples, 1L)

        assertTrue(result.isValid)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `calibration rejects face-up tilt above twenty five degrees`() {
        val radians = Math.toRadians(25.1)
        val gravity = Vector3(sin(radians).toFloat(), 0f, -cos(radians).toFloat())
        val samples = List(120) { index ->
            sample(index * 10_000_000L, accel = gravity)
        }

        CalibrationCalculator.calculate(samples, 1L)
    }

    private fun sample(
        timestamp: Long,
        gyro: Vector3 = Vector3(0f, 0f, 0f),
        accel: Vector3 = Vector3(0f, 0f, -1f),
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
