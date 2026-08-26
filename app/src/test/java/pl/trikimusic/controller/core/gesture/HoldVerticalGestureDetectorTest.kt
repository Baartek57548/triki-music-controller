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
    fun `production configuration preserves physical direction convention`() {
        val lift = Fixture(configuration = HoldVerticalGestureDetector.Configuration())
        lift.holdAtRest()
        lift.accelerate(z = -0.6f, frames = 15)
        lift.accelerate(z = -1.4f, frames = 15)

        val lowering = Fixture(configuration = HoldVerticalGestureDetector.Configuration())
        lowering.holdAtRest()
        lowering.accelerate(z = -1.4f, frames = 15)
        lowering.accelerate(z = -0.6f, frames = 15)

        assertEquals(listOf(RatingGestureAction.LIKE), lift.actions)
        assertEquals(listOf(RatingGestureAction.DISLIKE), lowering.actions)
    }

    @Test
    fun `brief wrong-way impulse is corrected for both directions`() {
        val lift = Fixture()
        lift.holdAtRest()

        lift.accelerate(z = -1.4f, frames = 5)
        lift.accelerate(z = -0.6f, frames = 13)
        lift.accelerate(z = -1.4f, frames = 13)

        val lowering = Fixture()
        lowering.holdAtRest()
        lowering.accelerate(z = -0.6f, frames = 5)
        lowering.accelerate(z = -1.4f, frames = 13)
        lowering.accelerate(z = -0.6f, frames = 13)

        assertEquals(listOf(RatingGestureAction.LIKE), lift.actions)
        assertEquals(listOf(RatingGestureAction.DISLIKE), lowering.actions)
    }

    @Test
    fun `early opposite pulse cannot satisfy braking`() {
        val fixture = Fixture()
        fixture.holdAtRest()

        fixture.accelerate(z = -0.6f, frames = 7)
        fixture.accelerate(z = -1.4f, frames = 2)
        fixture.accelerate(z = -0.6f, frames = 18)

        assertTrue(fixture.actions.isEmpty())
        assertEquals(HoldGesturePhase.REARMING, fixture.latest.phase)
    }

    @Test
    fun `confirmed gesture waits until braking slows the capsule`() {
        val fixture = Fixture()
        fixture.holdAtRest()

        fixture.accelerate(z = -0.6f, frames = 15)
        fixture.accelerate(z = -1.4f, frames = 3)

        assertTrue(fixture.actions.isEmpty())
        assertEquals(HoldGesturePhase.COMPLETING, fixture.latest.phase)

        fixture.accelerate(z = -1.4f, frames = 12)

        assertEquals(listOf(RatingGestureAction.LIKE), fixture.actions)
    }

    @Test
    fun `oversized throw is rejected instead of guessing rating`() {
        val fixture = Fixture()
        fixture.holdAtRest()

        fixture.accelerate(z = -0.6f, frames = 23)

        assertTrue(fixture.actions.isEmpty())
        assertEquals(HoldGesturePhase.REARMING, fixture.latest.phase)
    }

    @Test
    fun `repeated direction changes invalidate attempt`() {
        val fixture = Fixture()
        fixture.holdAtRest()

        fixture.accelerate(z = -0.6f, frames = 4)
        fixture.accelerate(z = -1.4f, frames = 4)
        fixture.accelerate(z = -0.6f, frames = 1)

        assertTrue(fixture.actions.isEmpty())
        assertEquals(HoldGesturePhase.REARMING, fixture.latest.phase)
    }

    @Test
    fun `lift near twenty five degree tilt still emits like`() {
        val gravityAtTiltLimit = Vector3(0.423f, 0f, -0.906f)
        val fixture = Fixture(
            configuration = HoldVerticalGestureDetector.Configuration(),
            restAcceleration = gravityAtTiltLimit,
        )
        fixture.holdAtRest()

        fixture.accelerate(gravityAtTiltLimit.scaledBy(0.6f), frames = 15)
        fixture.accelerate(gravityAtTiltLimit.scaledBy(1.4f), frames = 15)

        assertEquals(listOf(RatingGestureAction.LIKE), fixture.actions)
    }

    @Test
    fun `twenty centimeter lift while held emits like once`() {
        val fixture = Fixture()
        fixture.holdAtRest()

        fixture.accelerate(z = -0.6f, frames = 13)
        fixture.accelerate(z = -1.4f, frames = 13)
        fixture.accelerate(z = -1f, frames = 10)
        fixture.accelerate(z = -1.6f, frames = 20)

        assertEquals(listOf(RatingGestureAction.LIKE), fixture.actions)
        assertTrue(fixture.latest.estimatedDisplacementMeters <= -0.20f)
    }

    @Test
    fun `twenty centimeter lowering while held emits dislike once`() {
        val fixture = Fixture()
        fixture.holdAtRest()

        fixture.accelerate(z = -1.4f, frames = 13)
        fixture.accelerate(z = -0.6f, frames = 13)
        fixture.accelerate(z = -1f, frames = 10)

        assertEquals(listOf(RatingGestureAction.DISLIKE), fixture.actions)
        assertTrue(fixture.latest.estimatedDisplacementMeters >= 0.20f)
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
    fun `distance without braking phase cannot rate track`() {
        val fixture = Fixture()
        fixture.holdAtRest()

        fixture.accelerate(z = -0.6f, frames = 22)
        fixture.accelerate(z = -1f, frames = 6)

        assertTrue(fixture.actions.isEmpty())
    }

    @Test
    fun `short directional jolt is rejected until motion becomes quiet`() {
        val fixture = Fixture()
        fixture.holdAtRest()

        fixture.accelerate(z = -0.2f, frames = 3)
        fixture.accelerate(z = -1f, frames = 10)

        assertTrue(fixture.actions.isEmpty())
        assertEquals(HoldGesturePhase.READY, fixture.latest.phase)
    }

    @Test
    fun `braking overshoot cannot turn confirmed like into dislike`() {
        val fixture = Fixture()
        fixture.holdAtRest()

        fixture.accelerate(z = -0.6f, frames = 6)
        fixture.accelerate(z = -1.6f, frames = 25)
        fixture.accelerate(z = -1f, frames = 10)

        assertTrue(fixture.actions.isEmpty())
    }

    @Test
    fun `rotation is rejected and detector rearms after quiet period`() {
        val fixture = Fixture()
        fixture.holdAtRest()

        fixture.accelerate(z = -0.6f, frames = 13, gyroscopeZ = 180f)
        fixture.accelerate(z = -1.4f, frames = 13, gyroscopeZ = 180f)
        fixture.accelerate(z = -1f, frames = 10)
        assertTrue(fixture.actions.isEmpty())

        fixture.accelerate(z = -0.6f, frames = 13)
        fixture.accelerate(z = -1.4f, frames = 13)

        assertEquals(listOf(RatingGestureAction.LIKE), fixture.actions)
    }

    @Test
    fun `hand tremor while armed does not start rating gesture`() {
        val fixture = Fixture()
        fixture.holdAtRest()

        repeat(20) {
            fixture.accelerate(
                z = if (it % 2 == 0) -0.96f else -1.04f,
                frames = 1,
                gyroscopeZ = if (it % 2 == 0) 24f else -24f,
            )
        }

        assertTrue(fixture.actions.isEmpty())
        assertEquals(HoldGesturePhase.READY, fixture.latest.phase)
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
        configuration: HoldVerticalGestureDetector.Configuration = HoldVerticalGestureDetector.Configuration(
            holdMillis = 400L,
            triggerDisplacementMeters = 0.20f,
            motionStartAccelerationG = 0.10f,
            accelerationDeadZoneG = 0.03f,
            linearAccelerationSmoothingAlpha = 1f,
        ),
        private val restAcceleration: Vector3 = Vector3(0f, 0f, -1f),
    ) {
        private val detector = HoldVerticalGestureDetector(configuration)
        private var timestampNanos = 0L
        val actions = mutableListOf<RatingGestureAction>()
        var latest = detector.process(sample(timestampNanos, restAcceleration), buttonPressed)
            private set

        fun holdAtRest() {
            accelerate(restAcceleration, frames = 25)
            assertTrue(latest.holdProgress >= 1f)
        }

        fun accelerate(z: Float, frames: Int, gyroscopeZ: Float = 0f) {
            accelerate(Vector3(0f, 0f, z), frames, gyroscopeZ)
        }

        fun accelerate(acceleration: Vector3, frames: Int, gyroscopeZ: Float = 0f) {
            repeat(frames) {
                timestampNanos += SAMPLE_PERIOD_NANOS
                latest = detector.process(sample(timestampNanos, acceleration, gyroscopeZ), buttonPressed)
                latest.action?.let(actions::add)
            }
        }

        fun release() {
            buttonPressed = false
            timestampNanos += SAMPLE_PERIOD_NANOS
            latest = detector.process(sample(timestampNanos, restAcceleration), buttonPressed)
        }
    }

    private companion object {
        const val SAMPLE_PERIOD_NANOS = 20_000_000L

        fun sample(
            timestampNanos: Long,
            acceleration: Vector3,
            gyroscopeZ: Float = 0f,
        ): FilteredSensorData {
            val source = TrikiSensorData(
                frameIndex = timestampNanos / SAMPLE_PERIOD_NANOS,
                timestampNanos = timestampNanos,
                gyroscopeDps = Vector3(0f, 0f, gyroscopeZ),
                accelerometerG = acceleration,
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

        fun Vector3.scaledBy(scale: Float): Vector3 = Vector3(x * scale, y * scale, z * scale)
    }
}
