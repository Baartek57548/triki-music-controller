package pl.trikimusic.controller.core.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.trikimusic.controller.domain.model.FilteredSensorData
import pl.trikimusic.controller.domain.model.OrientationData
import pl.trikimusic.controller.domain.model.RawVector3
import pl.trikimusic.controller.domain.model.TrikiSensorData
import pl.trikimusic.controller.domain.model.Vector3

class HoldVerticalGestureDetectorTest {
    @Test
    fun `twenty centimeter lift while held emits like once`() {
        val fixture = Fixture()
        fixture.holdAtRest()

        fixture.accelerate(z = -1.4f, frames = 13)
        fixture.accelerate(z = -0.6f, frames = 13)
        fixture.accelerate(z = -1f, frames = 10)

        assertEquals(listOf(RatingGestureAction.LIKE), fixture.actions)
        assertTrue(fixture.latest.estimatedDisplacementMeters >= 0.20f)
    }

    @Test
    fun `twenty centimeter lowering while held emits dislike once`() {
        val fixture = Fixture()
        fixture.holdAtRest()

        fixture.accelerate(z = -0.6f, frames = 13)
        fixture.accelerate(z = -1.4f, frames = 13)
        fixture.accelerate(z = -1f, frames = 10)

        assertEquals(listOf(RatingGestureAction.DISLIKE), fixture.actions)
        assertTrue(fixture.latest.estimatedDisplacementMeters <= -0.20f)
    }

    @Test
    fun `movement before long hold cannot rate track`() {
        val fixture = Fixture()

        fixture.accelerate(z = -1.4f, frames = 8)
        fixture.accelerate(z = -0.6f, frames = 8)
        fixture.release()

        assertTrue(fixture.actions.isEmpty())
        assertEquals(HoldGesturePhase.IDLE, fixture.latest.phase)
    }

    @Test
    fun `short movement below displacement threshold is ignored`() {
        val fixture = Fixture()
        fixture.holdAtRest()

        fixture.accelerate(z = -1.25f, frames = 8)
        fixture.accelerate(z = -0.75f, frames = 8)
        fixture.accelerate(z = -1f, frames = 10)

        assertTrue(fixture.actions.isEmpty())
        assertNull(fixture.latest.action)
    }

    @Test
    fun `movement without held button is ignored`() {
        val fixture = Fixture(buttonPressed = false)

        fixture.accelerate(z = -1.5f, frames = 40)

        assertTrue(fixture.actions.isEmpty())
        assertEquals(HoldGesturePhase.IDLE, fixture.latest.phase)
    }

    private class Fixture(
        private var buttonPressed: Boolean = true,
    ) {
        private val detector = HoldVerticalGestureDetector(
            HoldVerticalGestureDetector.Configuration(
                holdMillis = 400L,
                triggerDisplacementMeters = 0.20f,
                motionStartAccelerationG = 0.10f,
                accelerationDeadZoneG = 0.03f,
                linearAccelerationSmoothingAlpha = 1f,
            ),
        )
        private var timestampNanos = 0L
        val actions = mutableListOf<RatingGestureAction>()
        var latest = detector.process(sample(timestampNanos, -1f), buttonPressed)
            private set

        fun holdAtRest() {
            accelerate(z = -1f, frames = 25)
            assertTrue(latest.holdProgress >= 1f)
        }

        fun accelerate(z: Float, frames: Int) {
            repeat(frames) {
                timestampNanos += SAMPLE_PERIOD_NANOS
                latest = detector.process(sample(timestampNanos, z), buttonPressed)
                latest.action?.let(actions::add)
            }
        }

        fun release() {
            buttonPressed = false
            timestampNanos += SAMPLE_PERIOD_NANOS
            latest = detector.process(sample(timestampNanos, -1f), buttonPressed)
        }
    }

    private companion object {
        const val SAMPLE_PERIOD_NANOS = 20_000_000L

        fun sample(timestampNanos: Long, z: Float): FilteredSensorData {
            val source = TrikiSensorData(
                frameIndex = timestampNanos / SAMPLE_PERIOD_NANOS,
                timestampNanos = timestampNanos,
                gyroscopeDps = Vector3(0f, 0f, 0f),
                accelerometerG = Vector3(0f, 0f, z),
                rawGyroscope = RawVector3(0, 0, 0),
                rawAccelerometer = RawVector3(0, 0, 0),
                status = 1,
            )
            return FilteredSensorData(
                source = source,
                gyroscopeDps = source.gyroscopeDps,
                accelerometerG = source.accelerometerG,
                orientation = OrientationData(),
            )
        }
    }
}
