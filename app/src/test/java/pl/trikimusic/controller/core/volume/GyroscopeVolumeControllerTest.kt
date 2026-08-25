package pl.trikimusic.controller.core.volume

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
    fun `positive Z rotation raises volume after stationary arming`() {
        val controller = controller()
        val actions = samples(
            stationarySamples = 4,
            rotationSamples = 4,
            gyroscopeZ = 100f,
            acceleration = Vector3(0f, 0f, 1f),
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
            acceleration = Vector3(0f, 0f, 1f),
        ).mapNotNull { controller.process(it).action }

        assertEquals(listOf(MediaAction.VOLUME_DOWN, MediaAction.VOLUME_DOWN), actions)
    }

    @Test
    fun `acceleration outside plus or minus twenty percent blocks volume`() {
        val controller = controller()
        val low = samples(4, 6, 200f, Vector3(0f, 0f, 0.79f))
        val high = samples(4, 6, -200f, Vector3(0f, 0f, 1.21f), startNanos = 1_000_000_000L)

        (low + high).forEach { result ->
            val output = controller.process(result)
            assertFalse(output.accelerometerWithinTolerance)
            assertFalse(output.stationary)
            assertNull(output.action)
        }
    }

    @Test
    fun `stationary gate is independent of cap orientation`() {
        val controller = controller()
        val tiltedGravity = Vector3(0.6f, 0.8f, 0f)
        val outputs = samples(4, 4, 100f, tiltedGravity).map(controller::process)

        assertTrue(outputs.all { it.accelerometerWithinTolerance })
        assertTrue(outputs.last().stationary)
        assertEquals(MediaAction.VOLUME_UP, outputs.last().action)
    }

    @Test
    fun `gyro noise and direction reversal do not create accidental step`() {
        val controller = controller()
        val arming = samples(4, 0, 0f, Vector3(0f, 0f, 1f))
        val jitter = listOf(12f, -12f, 17f, -17f, 9f).mapIndexed { index, z ->
            sample(200_000_000L + index * SAMPLE_PERIOD_NANOS, z, Vector3(0f, 0f, 1f))
        }

        val outputs = (arming + jitter).map(controller::process)

        assertTrue(outputs.all { it.action == null })
    }

    @Test
    fun `stream gap requires stationary gate to arm again`() {
        val controller = controller()
        samples(4, 4, 100f, Vector3(0f, 0f, 1f)).forEach(controller::process)

        val outputAfterGap = controller.process(
            sample(2_000_000_000L, 300f, Vector3(0f, 0f, 1f)),
        )

        assertFalse(outputAfterGap.stationary)
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
            add(sample(startNanos + index * SAMPLE_PERIOD_NANOS, 0f, acceleration))
        }
        repeat(rotationSamples) { index ->
            add(sample(startNanos + (stationarySamples + index) * SAMPLE_PERIOD_NANOS, gyroscopeZ, acceleration))
        }
    }

    private fun sample(timestampNanos: Long, gyroscopeZ: Float, acceleration: Vector3): FilteredSensorData {
        val source = TrikiSensorData(
            frameIndex = timestampNanos / SAMPLE_PERIOD_NANOS,
            timestampNanos = timestampNanos,
            gyroscopeDps = Vector3(0f, 0f, gyroscopeZ),
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

    private companion object {
        const val SAMPLE_PERIOD_NANOS = 50_000_000L
    }
}
