package pl.trikimusic.controller.core.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.trikimusic.controller.domain.model.FilteredSensorData
import pl.trikimusic.controller.domain.model.OrientationData
import pl.trikimusic.controller.domain.model.RawVector3
import pl.trikimusic.controller.domain.model.TrikiSensorData
import pl.trikimusic.controller.domain.model.Vector3

class HoldArcGestureDetectorTest {
    @Test
    fun `production configuration maps right arc to like and left arc to dislike`() {
        val right = Fixture(configuration = HoldArcGestureDetector.Configuration())
        right.holdAtRest()
        right.parabolicArc(rightward = true)

        val left = Fixture(configuration = HoldArcGestureDetector.Configuration())
        left.holdAtRest()
        left.parabolicArc(rightward = false)

        assertEquals(listOf(RatingGestureAction.LIKE), right.actions)
        assertEquals(listOf(RatingGestureAction.DISLIKE), left.actions)
        assertTrue(right.latest.estimatedHorizontalDisplacementMeters >= 0.10f)
        assertTrue(right.latest.estimatedHorizontalDisplacementMeters <= 0.16f)
        assertTrue(left.latest.estimatedHorizontalDisplacementMeters <= -0.10f)
        assertTrue(left.latest.estimatedHorizontalDisplacementMeters >= -0.16f)
        assertTrue(right.latest.estimatedArcDepthMeters >= 0.020f)
    }

    @Test
    fun `one arc emits only one action and rearms after quiet stabilization`() {
        val fixture = Fixture()
        fixture.holdAtRest()
        fixture.parabolicArc(rightward = true)
        fixture.accelerate(fixture.restAcceleration, frames = 30)
        fixture.parabolicArc(rightward = false)

        assertEquals(listOf(RatingGestureAction.LIKE, RatingGestureAction.DISLIKE), fixture.actions)
    }

    @Test
    fun `face up capsule cannot arm rating gesture`() {
        val fixture = Fixture(restAcceleration = Vector3(0f, 0f, -1f))
        fixture.holdAtRest(expectReady = false)
        fixture.parabolicArc(rightward = true)

        assertTrue(fixture.actions.isEmpty())
        assertFalse(fixture.latest.faceDown)
        assertEquals(HoldGesturePhase.HOLDING, fixture.latest.phase)
    }

    @Test
    fun `straight horizontal swipe is rejected without vertical arc`() {
        val fixture = Fixture()
        fixture.holdAtRest()
        fixture.horizontalSwipe(rightward = true)

        assertTrue(fixture.actions.isEmpty())
        assertTrue(fixture.latest.estimatedArcDepthMeters < 0.015f)
        assertNull(fixture.latest.action)
    }

    @Test
    fun `vertical movement without horizontal travel cannot rate track`() {
        val fixture = Fixture()
        fixture.holdAtRest()
        fixture.accelerate(Vector3(0f, 0f, 1.20f), frames = 8)
        fixture.accelerate(Vector3(0f, 0f, 0.80f), frames = 16)
        fixture.accelerate(Vector3(0f, 0f, 1.20f), frames = 8)

        assertTrue(fixture.actions.isEmpty())
        assertEquals(HoldGesturePhase.READY, fixture.latest.phase)
    }

    @Test
    fun `one sided vertical deviation is not accepted as an arc`() {
        val fixture = Fixture()
        fixture.holdAtRest()
        fixture.accelerate(Vector3(0.30f, 0f, 1.15f), frames = 14)
        fixture.accelerate(Vector3(-0.30f, 0f, 1.15f), frames = 14)

        assertTrue(fixture.actions.isEmpty())
    }

    @Test
    fun `short arc below ten centimeters is ignored`() {
        val fixture = Fixture()
        fixture.holdAtRest()
        fixture.parabolicArc(rightward = true, horizontalAccelerationG = 0.14f, quarterFrames = 4)

        assertTrue(fixture.actions.isEmpty())
        assertNull(fixture.latest.action)
    }

    @Test
    fun `oversized throw is rejected instead of guessing rating`() {
        val fixture = Fixture()
        fixture.holdAtRest()
        fixture.parabolicArc(rightward = true, horizontalAccelerationG = 0.45f, quarterFrames = 9)

        assertTrue(fixture.actions.isEmpty())
        assertEquals(HoldGesturePhase.REARMING, fixture.latest.phase)
    }

    @Test
    fun `forward throw is not confused with left or right`() {
        val fixture = Fixture()
        fixture.holdAtRest()
        fixture.accelerate(Vector3(0f, 0.30f, 1.15f), frames = 7)
        fixture.accelerate(Vector3(0f, 0.30f, 0.85f), frames = 7)
        fixture.accelerate(Vector3(0f, -0.30f, 0.85f), frames = 7)
        fixture.accelerate(Vector3(0f, -0.30f, 1.15f), frames = 7)

        assertTrue(fixture.actions.isEmpty())
        assertEquals(HoldGesturePhase.READY, fixture.latest.phase)
    }

    @Test
    fun `movement before full hold cannot rate track`() {
        val fixture = Fixture()
        fixture.parabolicArc(rightward = true, quarterFrames = 3)

        assertTrue(fixture.actions.isEmpty())
        assertEquals(HoldGesturePhase.HOLDING, fixture.latest.phase)
    }

    @Test
    fun `strong rotation invalidates arc and rearms after quiet period`() {
        val fixture = Fixture()
        fixture.holdAtRest()
        fixture.parabolicArc(rightward = true, gyroscopeZ = 180f)

        assertTrue(fixture.actions.isEmpty())
        assertEquals(HoldGesturePhase.REARMING, fixture.latest.phase)

        fixture.accelerate(fixture.restAcceleration, frames = 30)
        fixture.parabolicArc(rightward = false)

        assertEquals(listOf(RatingGestureAction.DISLIKE), fixture.actions)
    }

    @Test
    fun `hand tremor while armed does not start gesture`() {
        val fixture = Fixture()
        fixture.holdAtRest()

        repeat(30) { index ->
            fixture.accelerate(
                Vector3(
                    x = if (index % 2 == 0) 0.035f else -0.035f,
                    y = 0f,
                    z = if (index % 2 == 0) 1.025f else 0.975f,
                ),
                frames = 1,
                gyroscopeZ = if (index % 2 == 0) 20f else -20f,
            )
        }

        assertTrue(fixture.actions.isEmpty())
        assertEquals(HoldGesturePhase.READY, fixture.latest.phase)
    }

    @Test
    fun `rating gesture does not need a button signal`() {
        val fixture = Fixture()
        fixture.holdAtRest()
        fixture.parabolicArc(rightward = true)

        assertEquals(listOf(RatingGestureAction.LIKE), fixture.actions)
    }

    private class Fixture(
        configuration: HoldArcGestureDetector.Configuration = HoldArcGestureDetector.Configuration(
            holdMillis = 400L,
            motionStartAccelerationG = 0.10f,
            accelerationDeadZoneG = 0.03f,
            verticalAccelerationDeadZoneG = 0.02f,
            linearAccelerationSmoothingAlpha = 1f,
            minimumArcImpulseEachDirectionGSeconds = 0.006f,
            minimumArcDepthMeters = 0.015f,
        ),
        val restAcceleration: Vector3 = Vector3(0f, 0f, 1f),
    ) {
        private val detector = HoldArcGestureDetector(configuration)
        private var timestampNanos = 0L
        val actions = mutableListOf<RatingGestureAction>()
        var latest = detector.process(sample(timestampNanos, restAcceleration))
            private set

        fun holdAtRest(expectReady: Boolean = true) {
            accelerate(restAcceleration, frames = 30)
            if (expectReady) {
                assertTrue(latest.faceDown)
                assertTrue(latest.holdProgress >= 1f)
            }
        }

        fun parabolicArc(
            rightward: Boolean,
            horizontalAccelerationG: Float = 0.35f,
            verticalAccelerationG: Float = 0.23f,
            quarterFrames: Int = 5,
            gyroscopeZ: Float = 0f,
        ) {
            val horizontal = if (rightward) horizontalAccelerationG else -horizontalAccelerationG
            accelerate(Vector3(horizontal, 0f, restAcceleration.z + verticalAccelerationG), quarterFrames, gyroscopeZ)
            accelerate(Vector3(horizontal, 0f, restAcceleration.z - verticalAccelerationG), quarterFrames, gyroscopeZ)
            accelerate(Vector3(-horizontal, 0f, restAcceleration.z - verticalAccelerationG), quarterFrames, gyroscopeZ)
            accelerate(Vector3(-horizontal, 0f, restAcceleration.z + verticalAccelerationG), quarterFrames, gyroscopeZ)
        }

        fun horizontalSwipe(rightward: Boolean) {
            val horizontal = if (rightward) 0.30f else -0.30f
            accelerate(Vector3(horizontal, 0f, restAcceleration.z), frames = 14)
            accelerate(Vector3(-horizontal, 0f, restAcceleration.z), frames = 14)
        }

        fun accelerate(acceleration: Vector3, frames: Int, gyroscopeZ: Float = 0f) {
            repeat(frames) {
                timestampNanos += SAMPLE_PERIOD_NANOS
                latest = detector.process(sample(timestampNanos, acceleration, gyroscopeZ))
                latest.action?.let(actions::add)
            }
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
    }
}
