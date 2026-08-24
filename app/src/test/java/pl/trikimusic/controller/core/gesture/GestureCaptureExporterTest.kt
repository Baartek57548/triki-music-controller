package pl.trikimusic.controller.core.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.trikimusic.controller.domain.model.CalibrationProfile
import pl.trikimusic.controller.domain.model.FilteredSensorData
import pl.trikimusic.controller.domain.model.GestureThresholds
import pl.trikimusic.controller.domain.model.GestureType
import pl.trikimusic.controller.domain.model.OrientationData
import pl.trikimusic.controller.domain.model.RawVector3
import pl.trikimusic.controller.domain.model.TrikiSensorData
import pl.trikimusic.controller.domain.model.Vector3

class GestureCaptureExporterTest {
    @Test
    fun `exports labeled raw decoded filtered and orientation data in timestamp order`() {
        val csv = GestureCaptureExporter.toCsv(
            samples = listOf(sample(frameIndex = 2L, timestampNanos = 3_000_000L, status = 4), sample(1L, 1_000_000L, 3)),
            expectedGesture = GestureType.ROTATE_LEFT,
            detectedGesture = GestureType.SHAKE,
            confidence = 0.75f,
            featureQuality = 0.8f,
            thresholds = GestureThresholds(),
            calibration = CalibrationProfile(
                neutralPitch = 2.5f,
                neutralRoll = -1.5f,
                sampleCount = 100,
                calibratedAtMillis = 42L,
            ),
        )

        assertTrue(csv.contains("# expected_gesture=ROTATE_LEFT"))
        assertTrue(csv.contains("# detected_gesture=SHAKE"))
        assertTrue(csv.contains("# confidence=0.75"))
        assertTrue(csv.contains("neutral_pitch:2.5;neutral_roll:-1.5"))
        assertTrue(csv.contains("calibrated_at_ms:42"))
        assertTrue(csv.contains("raw_gyro_x,raw_gyro_y,raw_gyro_z"))
        assertTrue(csv.contains("decoded_accel_x_g,decoded_accel_y_g,decoded_accel_z_g"))
        assertTrue(csv.contains("filtered_gyro_x_dps,filtered_gyro_y_dps,filtered_gyro_z_dps"))

        val rows = csv.lineSequence().filter { it.isNotBlank() && !it.startsWith('#') }.toList()
        assertEquals(3, rows.size)
        assertTrue(rows[1].startsWith("0,1,0,0,3,"))
        assertTrue(rows[2].startsWith("1,2,2000000,2000000,4,"))
        assertTrue(rows[1].endsWith(",4.0,5.0,6.0"))
    }

    @Test
    fun `rejects an empty recording`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            GestureCaptureExporter.toCsv(
                samples = emptyList(),
                expectedGesture = GestureType.TAP,
                detectedGesture = null,
                confidence = null,
                featureQuality = 0f,
                thresholds = GestureThresholds(),
                calibration = CalibrationProfile(),
            )
        }

        assertEquals("Brak nagrania gestu do eksportu.", error.message)
    }

    @Test
    fun `rejects non-finite or out-of-range scores`() {
        val data = listOf(sample(frameIndex = 1L, timestampNanos = 1L, status = 0))
        assertThrows(IllegalArgumentException::class.java) {
            GestureCaptureExporter.toCsv(
                samples = data,
                expectedGesture = GestureType.TAP,
                detectedGesture = null,
                confidence = Float.NaN,
                featureQuality = 0.5f,
                thresholds = GestureThresholds(),
                calibration = CalibrationProfile(),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            GestureCaptureExporter.toCsv(
                samples = data,
                expectedGesture = GestureType.TAP,
                detectedGesture = null,
                confidence = 0.5f,
                featureQuality = 1.1f,
                thresholds = GestureThresholds(),
                calibration = CalibrationProfile(),
            )
        }
    }

    private fun sample(frameIndex: Long, timestampNanos: Long, status: Int) = FilteredSensorData(
        source = TrikiSensorData(
            frameIndex = frameIndex,
            timestampNanos = timestampNanos,
            gyroscopeDps = Vector3(1.5f, -2f, 3.25f),
            accelerometerG = Vector3(0.1f, -0.2f, 0.95f),
            rawGyroscope = RawVector3((-10).toShort(), 11.toShort(), (-12).toShort()),
            rawAccelerometer = RawVector3(100.toShort(), (-101).toShort(), 102.toShort()),
            status = status,
        ),
        gyroscopeDps = Vector3(1f, 2f, 3f),
        accelerometerG = Vector3(0.01f, 0.02f, 0.99f),
        orientation = OrientationData(pitch = 4f, roll = 5f, yaw = 6f),
    )
}
