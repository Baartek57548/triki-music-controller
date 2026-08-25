package pl.trikimusic.controller.core.volume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import pl.trikimusic.controller.domain.model.FilteredSensorData
import pl.trikimusic.controller.domain.model.MediaAction
import pl.trikimusic.controller.domain.model.OrientationData
import pl.trikimusic.controller.domain.model.RawVector3
import pl.trikimusic.controller.domain.model.TrikiSensorData
import pl.trikimusic.controller.domain.model.Vector3

class GyroscopeVolumeControllerTest {
    @Test
    fun `default controller requires nine hundred milliseconds of uninterrupted rest`() {
        val controller = GyroscopeVolumeController()
        val outputs = List(19) { index ->
            sample(index * SAMPLE_PERIOD_NANOS, 0f, FACE_UP_GRAVITY)
        }.map(controller::process)

        assertTrue(outputs.take(18).none { it.armed })
        assertEquals(0.944f, outputs[17].armingProgress, 0.01f)
        assertTrue(outputs.last().armed)
        assertEquals(1f, outputs.last().armingProgress, 0f)
    }

    @Test
    fun `positive Z rotation raises volume after stationary arming`() {
        val controller = controller()
        val actions = samples(
            stationarySamples = 4,
            rotationSamples = 4,
            gyroscopeZ = 100f,
            acceleration = FACE_UP_GRAVITY,
        ).mapNotNull { controller.process(it).action }

        assertEquals(listOf(MediaAction.VOLUME_UP, MediaAction.VOLUME_UP), actions)
    }

    @Test
    fun `negative Z rotation lowers volume`() {
        val controller = controller()
        val actions = samples(
            stationarySamples = 4,
            rotationSamples = 4,
            gyroscopeZ = -100f,
            acceleration = FACE_UP_GRAVITY,
        ).mapNotNull { controller.process(it).action }

        assertEquals(listOf(MediaAction.VOLUME_DOWN, MediaAction.VOLUME_DOWN), actions)
    }

    @Test
    fun `acceleration outside plus or minus twenty percent blocks volume`() {
        val controller = controller()
        val low = samples(4, 6, 200f, Vector3(0f, 0f, -0.79f))
        val high = samples(4, 6, -200f, Vector3(0f, 0f, -1.21f), startNanos = 1_000_000_000L)

        (low + high).forEach { result ->
            val output = controller.process(result)
            assertFalse(output.accelerometerWithinTolerance)
            assertFalse(output.armed)
            assertNull(output.action)
        }
    }

    @Test
    fun `cap tilted by ninety degrees can never control volume`() {
        val controller = controller()
        val outputs = samples(5, 8, 200f, Vector3(1f, 0f, 0f)).map(controller::process)

        assertTrue(outputs.all { it.accelerometerWithinTolerance })
        assertTrue(outputs.none { it.levelOrientation })
        assertTrue(outputs.none { it.armed })
        assertTrue(outputs.none { it.action != null })
    }

    @Test
    fun `upside down cap can never control volume`() {
        val controller = controller()
        val outputs = samples(5, 8, -200f, FACE_DOWN_GRAVITY).map(controller::process)

        assertTrue(outputs.all { it.accelerometerWithinTolerance })
        assertTrue(outputs.none { it.levelOrientation })
        assertTrue(outputs.none { it.armed })
        assertTrue(outputs.none { it.action != null })
    }

    @Test
    fun `small level-surface tolerance is accepted but larger tilt is blocked`() {
        val controller = controller()
        val acceptedGravity = gravityAtTilt(20f)
        val blockedGravity = gravityAtTilt(30f)

        val accepted = samples(4, 0, 0f, acceptedGravity).map(controller::process)
        controller.reset()
        val blocked = samples(4, 4, 100f, blockedGravity).map(controller::process)

        assertTrue(accepted.last().levelOrientation)
        assertTrue(accepted.last().armed)
        assertTrue(blocked.none { it.levelOrientation })
        assertTrue(blocked.none { it.action != null })
    }

    @Test
    fun `arming requires continuous gyroscope stillness`() {
        val controller = controller()
        val moving = List(5) { index ->
            sample(index * SAMPLE_PERIOD_NANOS, Vector3(0f, 0f, 12f), FACE_UP_GRAVITY)
        }
        val resting = List(2) { index ->
            sample((5 + index) * SAMPLE_PERIOD_NANOS, Vector3(0f, 0f, 0f), FACE_UP_GRAVITY)
        }

        val movingOutputs = moving.map(controller::process)
        val restingOutputs = resting.map(controller::process)

        assertTrue(movingOutputs.none { it.armed })
        assertTrue(movingOutputs.all { it.armingProgress == 0f })
        assertFalse(restingOutputs.first().armed)
        assertTrue(restingOutputs.last().armed)
    }

    @Test
    fun `off-axis rotation immediately disarms controller`() {
        val controller = controller()
        samples(4, 0, 0f, FACE_UP_GRAVITY).forEach(controller::process)

        val unsafe = controller.process(
            sample(4 * SAMPLE_PERIOD_NANOS, Vector3(30f, 0f, 200f), FACE_UP_GRAVITY),
        )
        val next = controller.process(
            sample(5 * SAMPLE_PERIOD_NANOS, Vector3(0f, 0f, 200f), FACE_UP_GRAVITY),
        )

        assertFalse(unsafe.armed)
        assertNull(unsafe.action)
        assertFalse(next.armed)
        assertNull(next.action)
    }

    @Test
    fun `turning an armed cap upside down disarms before applying Z rotation`() {
        val controller = controller()
        samples(4, 0, 0f, FACE_UP_GRAVITY).forEach(controller::process)

        val output = controller.process(
            sample(4 * SAMPLE_PERIOD_NANOS, Vector3(0f, 0f, 300f), FACE_DOWN_GRAVITY),
        )

        assertFalse(output.levelOrientation)
        assertFalse(output.armed)
        assertNull(output.action)
    }

    @Test
    fun `gyro noise and direction reversal do not create accidental step`() {
        val controller = controller()
        val arming = samples(4, 0, 0f, FACE_UP_GRAVITY)
        val jitter = listOf(12f, -12f, 17f, -17f, 9f).mapIndexed { index, z ->
            sample(200_000_000L + index * SAMPLE_PERIOD_NANOS, z, FACE_UP_GRAVITY)
        }

        val outputs = (arming + jitter).map(controller::process)

        assertTrue(outputs.all { it.action == null })
    }

    @Test
    fun `stream gap requires stationary gate to arm again`() {
        val controller = controller()
        samples(4, 4, 100f, FACE_UP_GRAVITY).forEach(controller::process)

        val outputAfterGap = controller.process(
            sample(2_000_000_000L, 300f, FACE_UP_GRAVITY),
        )

        assertFalse(outputAfterGap.armed)
        assertNull(outputAfterGap.action)
    }

    private fun controller() = GyroscopeVolumeController(
        GyroscopeVolumeController.Configuration(
            stationaryArmingMillis = 40L,
            activationGyroscopeDps = 18f,
            releaseGyroscopeDps = 10f,
            degreesPerVolumeStep = 10f,
        ),
    )

    private fun samples(
        stationarySamples: Int,
        rotationSamples: Int,
        gyroscopeZ: Float,
        acceleration: Vector3,
        startNanos: Long = 0L,
    ): List<FilteredSensorData> = buildList {
        repeat(stationarySamples) { index ->
            add(sample(startNanos + index * SAMPLE_PERIOD_NANOS, Vector3(0f, 0f, 0f), acceleration))
        }
        repeat(rotationSamples) { index ->
            add(
                sample(
                    startNanos + (stationarySamples + index) * SAMPLE_PERIOD_NANOS,
                    Vector3(0f, 0f, gyroscopeZ),
                    acceleration,
                ),
            )
        }
    }

    private fun sample(timestampNanos: Long, gyroscopeZ: Float, acceleration: Vector3): FilteredSensorData =
        sample(timestampNanos, Vector3(0f, 0f, gyroscopeZ), acceleration)

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
