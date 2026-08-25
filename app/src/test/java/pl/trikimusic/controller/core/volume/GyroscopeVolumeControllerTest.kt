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
    fun `controller is active immediately inside zero to twenty five degree range`() {
        val controller = controller()

        val output = controller.process(sample(0L, Vector3(0f, 0f, 0f), gravityAtTilt(25f)))

        assertTrue(output.sensorValid)
        assertTrue(output.withinTiltRange)
        assertTrue(output.active)
    }

    @Test
    fun `positive Z rotation raises volume without stationary arming`() {
        val controller = controller()

        val actions = rotationSamples(5, 100f, FACE_UP_GRAVITY)
            .mapNotNull { controller.process(it).action }

        assertEquals(listOf(MediaAction.VOLUME_UP, MediaAction.VOLUME_UP), actions)
    }

    @Test
    fun `negative Z rotation lowers volume without stationary arming`() {
        val controller = controller()

        val actions = rotationSamples(5, -100f, FACE_UP_GRAVITY)
            .mapNotNull { controller.process(it).action }

        assertEquals(listOf(MediaAction.VOLUME_DOWN, MediaAction.VOLUME_DOWN), actions)
    }

    @Test
    fun `acceleration magnitude does not gate control when tilt is in range`() {
        val lowMagnitudeController = controller()
        val highMagnitudeController = controller()

        val lowActions = rotationSamples(5, 100f, Vector3(0f, 0f, -0.4f))
            .mapNotNull { lowMagnitudeController.process(it).action }
        val highActions = rotationSamples(5, -100f, Vector3(0f, 0f, -1.8f))
            .mapNotNull { highMagnitudeController.process(it).action }

        assertTrue(lowActions.isNotEmpty())
        assertTrue(highActions.isNotEmpty())
    }

    @Test
    fun `tilt above twenty five degrees blocks volume`() {
        val controller = controller()

        val outputs = rotationSamples(6, 200f, gravityAtTilt(25.1f)).map(controller::process)

        assertTrue(outputs.all { it.sensorValid })
        assertTrue(outputs.none { it.withinTiltRange })
        assertTrue(outputs.none { it.active })
        assertTrue(outputs.none { it.action != null })
    }

    @Test
    fun `vertical and upside down cap can never control volume`() {
        val verticalController = controller()
        val upsideDownController = controller()

        val vertical = rotationSamples(6, 200f, Vector3(1f, 0f, 0f)).map(verticalController::process)
        val upsideDown = rotationSamples(6, -200f, FACE_DOWN_GRAVITY).map(upsideDownController::process)

        assertTrue(vertical.none { it.withinTiltRange || it.action != null })
        assertTrue(upsideDown.none { it.withinTiltRange || it.action != null })
    }

    @Test
    fun `off axis movement does not block Z control inside tilt range`() {
        val controller = controller()
        val outputs = List(5) { index ->
            sample(
                index * SAMPLE_PERIOD_NANOS,
                Vector3(500f, -400f, 100f),
                FACE_UP_GRAVITY,
            )
        }.map(controller::process)

        assertTrue(outputs.all { it.active })
        assertEquals(listOf(MediaAction.VOLUME_UP, MediaAction.VOLUME_UP), outputs.mapNotNull { it.action })
    }

    @Test
    fun `leaving tilt range clears accumulated rotation`() {
        val controller = controller()
        controller.process(sample(0L, Vector3(0f, 0f, 100f), FACE_UP_GRAVITY))
        controller.process(sample(SAMPLE_PERIOD_NANOS, Vector3(0f, 0f, 100f), FACE_UP_GRAVITY))

        val blocked = controller.process(
            sample(2 * SAMPLE_PERIOD_NANOS, Vector3(0f, 0f, 100f), gravityAtTilt(25.1f)),
        )
        val firstAfterReturn = controller.process(
            sample(3 * SAMPLE_PERIOD_NANOS, Vector3(0f, 0f, 100f), FACE_UP_GRAVITY),
        )
        val secondAfterReturn = controller.process(
            sample(4 * SAMPLE_PERIOD_NANOS, Vector3(0f, 0f, 100f), FACE_UP_GRAVITY),
        )

        assertFalse(blocked.active)
        assertNull(blocked.action)
        assertNull(firstAfterReturn.action)
        assertEquals(MediaAction.VOLUME_UP, secondAfterReturn.action)
    }

    @Test
    fun `gyro noise and direction reversal do not create accidental step`() {
        val controller = controller()
        val jitter = listOf(12f, -12f, 17f, -17f, 9f).mapIndexed { index, z ->
            sample(index * SAMPLE_PERIOD_NANOS, Vector3(0f, 0f, z), FACE_UP_GRAVITY)
        }

        val outputs = jitter.map(controller::process)

        assertTrue(outputs.all { it.action == null })
        assertTrue(outputs.all { it.active })
    }

    @Test
    fun `stream gap clears partial rotation without requiring stationary rearming`() {
        val controller = controller()
        controller.process(sample(0L, Vector3(0f, 0f, 100f), FACE_UP_GRAVITY))
        controller.process(sample(SAMPLE_PERIOD_NANOS, Vector3(0f, 0f, 100f), FACE_UP_GRAVITY))

        val afterGap = controller.process(sample(2_000_000_000L, Vector3(0f, 0f, 100f), FACE_UP_GRAVITY))
        val next = controller.process(sample(2_050_000_000L, Vector3(0f, 0f, 100f), FACE_UP_GRAVITY))
        val action = controller.process(sample(2_100_000_000L, Vector3(0f, 0f, 100f), FACE_UP_GRAVITY))

        assertTrue(afterGap.active)
        assertNull(afterGap.action)
        assertNull(next.action)
        assertEquals(MediaAction.VOLUME_UP, action.action)
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
            activationGyroscopeDps = 18f,
            releaseGyroscopeDps = 10f,
            degreesPerVolumeStep = 10f,
        ),
    )

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
