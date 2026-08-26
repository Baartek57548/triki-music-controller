package pl.trikimusic.controller.core.volume

import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.trikimusic.controller.domain.model.FilteredSensorData
import pl.trikimusic.controller.domain.model.MediaAction
import pl.trikimusic.controller.domain.model.OrientationData
import pl.trikimusic.controller.domain.model.RawVector3
import pl.trikimusic.controller.domain.model.TrikiSensorData
import pl.trikimusic.controller.domain.model.Vector3

class GyroscopeVolumeControllerTest {
    @Test
    fun `default controller requires two continuous seconds inside tilt range`() {
        val controller = GyroscopeVolumeController()
        val outputs = List(41) { index ->
            sample(index * SAMPLE_PERIOD_NANOS, Vector3(0f, 0f, 0f), gravityAtTilt(25f))
        }.map(controller::process)

        assertTrue(outputs.take(40).none { it.active })
        assertEquals(0.975f, outputs[39].stabilizationProgress, 0.001f)
        assertTrue(outputs.last().tiltStable)
        assertTrue(outputs.last().active)
        assertEquals(1f, outputs.last().stabilizationProgress, 0f)
    }

    @Test
    fun `angle stabilization does not require stillness`() {
        val controller = GyroscopeVolumeController()
        val outputs = List(41) { index ->
            sample(
                index * SAMPLE_PERIOD_NANOS,
                Vector3(500f, -400f, 80f),
                FACE_UP_GRAVITY,
            )
        }.map(controller::process)

        assertTrue(outputs.last().active)
        assertTrue(outputs.last().withinTiltRange)
    }

    @Test
    fun `positive and negative Z rotation change volume after stabilization`() {
        val positive = controller()
        val negative = controller()
        val positiveStart = stabilize(positive, FACE_UP_GRAVITY)
        val negativeStart = stabilize(negative, FACE_UP_GRAVITY)

        val positiveActions = rotationSamples(4, 100f, FACE_UP_GRAVITY, positiveStart)
            .mapNotNull { positive.process(it).action }
        val negativeActions = rotationSamples(4, -100f, FACE_UP_GRAVITY, negativeStart)
            .mapNotNull { negative.process(it).action }

        assertEquals(listOf(MediaAction.VOLUME_UP, MediaAction.VOLUME_UP), positiveActions)
        assertEquals(listOf(MediaAction.VOLUME_DOWN, MediaAction.VOLUME_DOWN), negativeActions)
    }

    @Test
    fun `acceleration within twenty percent tolerance allows in-air control`() {
        val low = controller()
        val high = controller()
        val lowGravity = Vector3(0f, 0f, -0.8f)
        val highGravity = Vector3(0f, 0f, -1.2f)
        val lowStart = stabilize(low, lowGravity)
        val highStart = stabilize(high, highGravity)

        val lowActions = rotationSamples(4, 100f, lowGravity, lowStart).mapNotNull { low.process(it).action }
        val highActions = rotationSamples(4, -100f, highGravity, highStart).mapNotNull { high.process(it).action }

        assertTrue(lowActions.isNotEmpty())
        assertTrue(highActions.isNotEmpty())
    }

    @Test
    fun `sudden acceleration blocks volume and restarts full stabilization`() {
        val controller = controller()
        val start = stabilize(controller, FACE_UP_GRAVITY)
        val queuedRotation = controller.process(sample(start, Vector3(0f, 0f, 100f), FACE_UP_GRAVITY))
        val suddenMovement = controller.process(
            sample(start + SAMPLE_PERIOD_NANOS, Vector3(0f, 0f, 600f), Vector3(0f, 0f, -1.21f)),
        )
        val recoveryStart = controller.process(
            sample(start + 2 * SAMPLE_PERIOD_NANOS, Vector3(0f, 0f, 600f), FACE_UP_GRAVITY),
        )
        val recoveryHalfway = controller.process(
            sample(start + 3 * SAMPLE_PERIOD_NANOS, Vector3(0f, 0f, 600f), FACE_UP_GRAVITY),
        )
        val stableAgain = controller.process(
            sample(start + 4 * SAMPLE_PERIOD_NANOS, Vector3(0f, 0f, 600f), FACE_UP_GRAVITY),
        )

        assertNull(queuedRotation.action)
        assertFalse(suddenMovement.accelerationStable)
        assertFalse(suddenMovement.active)
        assertNull(suddenMovement.action)
        assertEquals(0f, suddenMovement.stabilizationProgress, 0f)
        assertFalse(recoveryStart.active)
        assertEquals(0f, recoveryStart.stabilizationProgress, 0f)
        assertFalse(recoveryHalfway.active)
        assertEquals(0.5f, recoveryHalfway.stabilizationProgress, 0.001f)
        assertTrue(stableAgain.active)
        assertNull(stableAgain.action)
    }

    @Test
    fun `default volume response is gentle for moderate rotation`() {
        val controller = GyroscopeVolumeController()
        for (index in 0..40) {
            controller.process(sample(index * SAMPLE_PERIOD_NANOS, Vector3(0f, 0f, 0f), FACE_UP_GRAVITY))
        }

        val early = List(7) { index ->
            controller.process(
                sample((41L + index) * SAMPLE_PERIOD_NANOS, Vector3(0f, 0f, 60f), FACE_UP_GRAVITY),
            )
        }
        val later = List(14) { index ->
            controller.process(
                sample((48L + index) * SAMPLE_PERIOD_NANOS, Vector3(0f, 0f, 60f), FACE_UP_GRAVITY),
            )
        }

        assertTrue(early.none { it.action != null })
        assertTrue(later.any { it.action == MediaAction.VOLUME_UP })
    }

    @Test
    fun `tilt above twenty five degrees blocks and resets stabilization`() {
        val controller = controller()
        stabilize(controller, FACE_UP_GRAVITY)

        val blocked = controller.process(
            sample(200_000_000L, Vector3(0f, 0f, 100f), gravityAtTilt(25.1f)),
        )
        val returned = controller.process(
            sample(250_000_000L, Vector3(0f, 0f, 100f), FACE_UP_GRAVITY),
        )

        assertFalse(blocked.withinTiltRange)
        assertFalse(blocked.active)
        assertEquals(0f, blocked.stabilizationProgress, 0f)
        assertTrue(returned.withinTiltRange)
        assertFalse(returned.active)
        assertEquals(0f, returned.stabilizationProgress, 0f)
    }

    @Test
    fun `vertical and upside down cap can never control volume`() {
        val verticalController = controller()
        val upsideDownController = controller()

        val vertical = rotationSamples(8, 200f, Vector3(1f, 0f, 0f)).map(verticalController::process)
        val upsideDown = rotationSamples(8, -200f, FACE_DOWN_GRAVITY).map(upsideDownController::process)

        assertTrue(vertical.none { it.withinTiltRange || it.action != null })
        assertTrue(upsideDown.none { it.withinTiltRange || it.action != null })
    }

    @Test
    fun `gyroscope smoothing softens abrupt Z change`() {
        val controller = GyroscopeVolumeController(
            GyroscopeVolumeController.Configuration(
                tiltStabilizationMillis = 100L,
                gyroscopeSmoothingAlpha = 0.2f,
                minimumStepIntervalMillis = 100L,
            ),
        )
        val start = stabilize(controller, FACE_UP_GRAVITY)

        val output = controller.process(sample(start, Vector3(0f, 0f, 100f), FACE_UP_GRAVITY))

        assertTrue(output.gyroscopeZDps in 19.9f..20.1f)
        assertNull(output.action)
    }

    @Test
    fun `step rate is limited during very fast rotation`() {
        val controller = controller()
        val start = stabilize(controller, FACE_UP_GRAVITY)
        val outputs = rotationSamples(12, 600f, FACE_UP_GRAVITY, start).map(controller::process)
        val actionIndexes = outputs.mapIndexedNotNull { index, output -> index.takeIf { output.action != null } }

        assertTrue(actionIndexes.size >= 3)
        assertTrue(actionIndexes.zipWithNext().all { (first, second) -> second - first >= 2 })
    }

    @Test
    fun `gyro noise and direction reversal do not create accidental step`() {
        val controller = controller()
        val start = stabilize(controller, FACE_UP_GRAVITY)
        val jitter = listOf(12f, -12f, 17f, -17f, 9f).mapIndexed { index, z ->
            sample(start + index * SAMPLE_PERIOD_NANOS, Vector3(0f, 0f, z), FACE_UP_GRAVITY)
        }

        val outputs = jitter.map(controller::process)

        assertTrue(outputs.all { it.action == null })
        assertTrue(outputs.all { it.active })
    }

    @Test
    fun `stream gap requires angle stabilization again`() {
        val controller = controller()
        stabilize(controller, FACE_UP_GRAVITY)

        val afterGap = controller.process(sample(2_000_000_000L, Vector3(0f, 0f, 100f), FACE_UP_GRAVITY))
        val halfway = controller.process(sample(2_050_000_000L, Vector3(0f, 0f, 100f), FACE_UP_GRAVITY))
        val stableAgain = controller.process(sample(2_100_000_000L, Vector3(0f, 0f, 100f), FACE_UP_GRAVITY))

        assertFalse(afterGap.active)
        assertFalse(halfway.active)
        assertTrue(stableAgain.active)
        assertTrue(stableAgain.action == null)
    }

    @Test
    fun `invalid acceleration vector cannot activate controller`() {
        val controller = controller()

        val zero = controller.process(sample(0L, Vector3(0f, 0f, 100f), Vector3(0f, 0f, 0f)))
        val nonFinite = controller.process(
            sample(SAMPLE_PERIOD_NANOS, Vector3(0f, 0f, 100f), Vector3(Float.NaN, 0f, -1f)),
        )

        assertFalse(zero.sensorValid)
        assertFalse(zero.active)
        assertFalse(nonFinite.sensorValid)
        assertFalse(nonFinite.active)
    }

    private fun controller() = GyroscopeVolumeController(
        GyroscopeVolumeController.Configuration(
            tiltStabilizationMillis = 100L,
            activationGyroscopeDps = 18f,
            releaseGyroscopeDps = 10f,
            degreesPerVolumeStep = 10f,
            gyroscopeSmoothingAlpha = 1f,
            minimumStepIntervalMillis = 100L,
        ),
    )

    private fun stabilize(controller: GyroscopeVolumeController, acceleration: Vector3): Long {
        controller.process(sample(0L, Vector3(0f, 0f, 0f), acceleration))
        controller.process(sample(SAMPLE_PERIOD_NANOS, Vector3(0f, 0f, 0f), acceleration))
        val stable = controller.process(sample(2 * SAMPLE_PERIOD_NANOS, Vector3(0f, 0f, 0f), acceleration))
        assertTrue(stable.active)
        return 3 * SAMPLE_PERIOD_NANOS
    }

    private fun rotationSamples(
        count: Int,
        gyroscopeZ: Float,
        acceleration: Vector3,
        startNanos: Long = 0L,
    ): List<FilteredSensorData> = List(count) { index ->
        sample(
            startNanos + index * SAMPLE_PERIOD_NANOS,
            Vector3(0f, 0f, gyroscopeZ),
            acceleration,
        )
    }

    private fun sample(timestampNanos: Long, gyroscope: Vector3, acceleration: Vector3): FilteredSensorData {
        val source = TrikiSensorData(
            frameIndex = timestampNanos / SAMPLE_PERIOD_NANOS,
            timestampNanos = timestampNanos,
            gyroscopeDps = gyroscope,
            accelerometerG = acceleration,
            rawGyroscope = RawVector3(0, 0, 0),
            rawAccelerometer = RawVector3(0, 0, 0),
            status = 0,
        )
        return FilteredSensorData(
            source = source,
            gyroscopeDps = source.gyroscopeDps,
            accelerometerG = source.accelerometerG,
            orientation = OrientationData(),
        )
    }

    private fun gravityAtTilt(degrees: Float): Vector3 {
        val radians = Math.toRadians(degrees.toDouble())
        return Vector3(sin(radians).toFloat(), 0f, -cos(radians).toFloat())
    }

    private companion object {
        const val SAMPLE_PERIOD_NANOS = 50_000_000L
        val FACE_UP_GRAVITY = Vector3(0f, 0f, -1f)
        val FACE_DOWN_GRAVITY = Vector3(0f, 0f, 1f)
    }
}
