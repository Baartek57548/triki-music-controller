package pl.trikimusic.controller.core.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.trikimusic.controller.core.sensor.SensorFilter
import pl.trikimusic.controller.domain.model.CalibrationProfile
import pl.trikimusic.controller.domain.model.FilteredSensorData
import pl.trikimusic.controller.domain.model.OrientationData
import pl.trikimusic.controller.domain.model.RawVector3
import pl.trikimusic.controller.domain.model.TrikiSensorData
import pl.trikimusic.controller.domain.model.Vector3

class FullRotationGestureDetectorTest {
    @Test
    fun rightFullRotationTriggersNextDirection() {
        val fixture = Fixture()
        fixture.stabilize()
        fixture.rotate(positive = true)

        assertEquals(listOf(RotationGestureDirection.RIGHT), fixture.triggers)
        assertTrue(fixture.latest.faceDown)
        assertTrue(fixture.latest.estimatedRotationDegrees >= 330f)
    }

    @Test
    fun leftFullRotationTriggersPreviousDirection() {
        val fixture = Fixture()
        fixture.stabilize()
        fixture.rotate(positive = false)

        assertEquals(listOf(RotationGestureDirection.LEFT), fixture.triggers)
    }

    @Test
    fun partialTurnIsNotEnough() {
        val fixture = Fixture()
        fixture.stabilize()
        fixture.rotate(positive = true, frames = 100)

        assertTrue(fixture.triggers.isEmpty())
        assertTrue(fixture.latest.estimatedRotationDegrees < 330f)
    }

    @Test
    fun faceUpCapsuleCannotTriggerNavigationRotation() {
        val fixture = Fixture(restAcceleration = Vector3(0f, 0f, -1f))
        fixture.stabilize()
        fixture.rotate(positive = true)

        assertTrue(fixture.triggers.isEmpty())
        assertFalse(fixture.latest.faceDown)
        assertEquals(HoldGesturePhase.HOLDING, fixture.latest.phase)
    }

    @Test
    fun directionReversalStartsFreshRotation() {
        val fixture = Fixture()
        fixture.stabilize()
        fixture.rotate(positive = true, frames = 60)
        fixture.rotate(positive = false, frames = 60)

        assertTrue(fixture.triggers.isEmpty())
        assertEquals(HoldGesturePhase.TRACKING, fixture.latest.phase)
    }

    @Test
    fun triggerRearmsAfterQuietStabilization() {
        val fixture = Fixture()
        fixture.stabilize()
        fixture.rotate(positive = true)
        fixture.quiet(frames = 20)
        fixture.stabilize()
        fixture.rotate(positive = false)

        assertEquals(
            listOf(RotationGestureDirection.RIGHT, RotationGestureDirection.LEFT),
            fixture.triggers,
        )
    }

    @Test
    fun sensorFilterPreservesFullRotation() {
        val detector = FullRotationGestureDetector()
        val filter = SensorFilter()
        val directions = mutableListOf<RotationGestureDirection>()
        var timestampNanos = 0L
        repeat(40) {
            timestampNanos += SAMPLE_PERIOD_NANOS
            val raw = sample(timestampNanos, Vector3(0f, 0f, 1f), 0f).source
            detector.process(filter.process(raw, CalibrationProfile()))
        }
        repeat(170) {
            timestampNanos += SAMPLE_PERIOD_NANOS
            val raw = sample(timestampNanos, Vector3(0f, 0f, 1f), 130f).source
            detector.process(filter.process(raw, CalibrationProfile())).takeIf { it.triggered }
                ?.direction?.let(directions::add)
        }

        assertEquals(listOf(RotationGestureDirection.RIGHT), directions)
    }

    private class Fixture(
        restAcceleration: Vector3 = Vector3(0f, 0f, 1f),
    ) {
        private val detector = FullRotationGestureDetector(
            FullRotationGestureDetector.Configuration(
                stabilizationMillis = 200L,
                requiredRotationDegrees = 330f,
                maximumRotationDegrees = 500f,
                maximumRotationMillis = 4_000L,
                activationGyroscopeDps = 18f,
                releaseGyroscopeDps = 8f,
                gyroscopeSmoothingAlpha = 1f,
                rearmQuietMillis = 100L,
            ),
        )
        private var timestampNanos = 0L
        private val rest = restAcceleration
        val triggers = mutableListOf<RotationGestureDirection>()
        var latest = detector.process(sample(timestampNanos, rest))
            private set

        fun stabilize() = feed(rest, 12)

        fun quiet(frames: Int) = feed(rest, frames)

        fun rotate(positive: Boolean, frames: Int = 150) {
            feed(rest, 1, if (positive) 130f else -130f)
            feed(rest, frames, if (positive) 130f else -130f)
        }

        private fun feed(acceleration: Vector3, frames: Int, gyroscopeZ: Float = 0f) {
            repeat(frames) {
                timestampNanos += SAMPLE_PERIOD_NANOS
                latest = detector.process(sample(timestampNanos, acceleration, gyroscopeZ))
                if (latest.triggered) latest.direction?.let(triggers::add)
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
                status = 0,
            )
            return FilteredSensorData(source, source.gyroscopeDps, source.accelerometerG, OrientationData())
        }
    }
}
