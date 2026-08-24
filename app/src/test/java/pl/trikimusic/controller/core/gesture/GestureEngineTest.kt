package pl.trikimusic.controller.core.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import pl.trikimusic.controller.BuildConfig
import pl.trikimusic.controller.data.bluetooth.FakeTrikiDataSource
import pl.trikimusic.controller.domain.model.CalibrationProfile
import pl.trikimusic.controller.domain.model.FilteredSensorData
import pl.trikimusic.controller.domain.model.GestureThresholds
import pl.trikimusic.controller.domain.model.GestureFeatureVector
import pl.trikimusic.controller.domain.model.GESTURE_FEATURE_DIMENSION
import pl.trikimusic.controller.domain.model.LearnedGestureSample
import pl.trikimusic.controller.domain.model.GestureType
import pl.trikimusic.controller.domain.model.OrientationData
import pl.trikimusic.controller.domain.model.RawVector3
import pl.trikimusic.controller.domain.model.PersonalizedGestureModel
import pl.trikimusic.controller.domain.model.TrikiSensorData
import pl.trikimusic.controller.domain.model.Vector3

class GestureEngineTest {
    private val thresholds = GestureThresholds()

    @Test
    fun `stationary tilted controller never emits a tilt`() {
        val fixture = Fixture(thresholds)

        fixture.rest(count = 1_000, roll = 42f)

        assertTrue(fixture.events.isEmpty())
    }

    @Test
    fun `small stationary sensor noise never emits an event`() {
        val fixture = Fixture(thresholds)
        repeat(2_000) { index ->
            val sign = if (index % 2 == 0) 1f else -1f
            fixture.feed(
                roll = 18f + sign * 0.04f,
                gyro = Vector3(sign * 2.5f, -sign * 1.5f, sign),
                accel = Vector3(sign * 0.006f, -sign * 0.004f, 1f),
            )
        }

        assertTrue(fixture.events.isEmpty())
    }

    @Test
    fun `tilt requires rest movement and rest and emits only once`() {
        val fixture = Fixture(thresholds)
        fixture.rest(35)
        repeat(8) { index ->
            val angleDegrees = (index + 1) * 6f
            val angleRadians = Math.toRadians(angleDegrees.toDouble())
            fixture.feed(
                roll = angleDegrees,
                gyro = Vector3(-120f, 0f, 0f),
                accel = Vector3(0f, sin(angleRadians).toFloat(), cos(angleRadians).toFloat()),
            )
        }
        val heldAngleRadians = Math.toRadians(48.0)
        fixture.rest(
            80,
            roll = 48f,
            accel = Vector3(0f, sin(heldAngleRadians).toFloat(), cos(heldAngleRadians).toFloat()),
        )

        assertEquals(listOf(GestureType.LEAN), fixture.events)
    }

    @Test
    fun `flat horizontal acceleration with little rotation emits slide`() {
        val fixture = Fixture(thresholds)
        fixture.rest(35)
        repeat(8) {
            fixture.feed(
                gyro = Vector3(10f, 4f, 0f),
                accel = Vector3(0.26f, 0f, 1f),
            )
        }
        repeat(8) {
            fixture.feed(
                gyro = Vector3(-10f, -4f, 0f),
                accel = Vector3(-0.26f, 0f, 1f),
            )
        }
        fixture.rest(40)

        assertEquals(listOf(GestureType.SLIDE), fixture.events)
    }

    @Test
    fun `single corrupted orientation sample is rejected`() {
        val fixture = Fixture(thresholds)
        fixture.rest(35)
        fixture.feed(roll = 75f, gyro = Vector3(-500f, 0f, 0f))
        fixture.rest(40)

        assertTrue(fixture.events.isEmpty())
    }

    @Test
    fun `rotation requires meaningful integrated angle`() {
        val fixture = Fixture(thresholds)
        fixture.rest(35)
        repeat(7) { fixture.feed(gyro = Vector3(0f, 0f, -430f)) }
        fixture.rest(35)

        assertEquals(listOf(GestureType.ROTATE_LEFT), fixture.events)
    }

    @Test
    fun `rotation is projected onto gravity and works when cap starts on its side`() {
        val engine = GestureEngine()
        var timestamp = 0L
        val events = mutableListOf<GestureType>()
        repeat(35) {
            timestamp += PERIOD_NANOS
            events += engine.process(
                filtered(timestamp, 0f, Vector3(0f, 0f, 0f), Vector3(1f, 0f, 0f)),
                thresholds,
            ).map { it.type }
        }
        repeat(24) {
            timestamp += PERIOD_NANOS
            events += engine.process(
                filtered(timestamp, 0f, Vector3(80f, 0f, 0f), Vector3(1f, 0f, 0f)),
                thresholds,
            ).map { it.type }
        }
        repeat(4) {
            timestamp += PERIOD_NANOS
            events += engine.process(
                filtered(timestamp, 0f, Vector3(0f, 0f, 0f), Vector3(1f, 0f, 0f)),
                thresholds,
            ).map { it.type }
        }

        assertEquals(listOf(GestureType.ROTATE_RIGHT), events)
    }

    @Test
    fun `clean vertical tap emits play pause gesture without a free fall`() {
        val fixture = Fixture(thresholds)
        fixture.rest(35)
        fixture.feed(accel = Vector3(0f, 0f, 1.65f))
        fixture.rest(10)

        assertEquals(listOf(GestureType.TAP), fixture.events)
    }

    @Test
    fun `constant gyroscope fault cannot repeat media actions`() {
        val fixture = Fixture(thresholds)
        fixture.rest(35)
        repeat(600) { fixture.feed(gyro = Vector3(0f, 0f, 260f)) }

        assertTrue(fixture.events.isEmpty())
    }

    @Test
    fun `one back and forth motion emits one shake`() {
        val fixture = Fixture(thresholds)
        fixture.rest(35)
        fixture.shakeCycle()
        fixture.rest(60)

        assertEquals(listOf(GestureType.SHAKE), fixture.events)
    }

    @Test
    fun `two separated back and forth motions emit one double shake`() {
        val fixture = Fixture(thresholds)
        fixture.rest(35)
        fixture.shakeCycle()
        fixture.rest(18)
        fixture.shakeCycle()
        fixture.rest(40)

        assertEquals(listOf(GestureType.DOUBLE_SHAKE), fixture.events)
    }

    @Test
    fun `free fall without impact is rejected`() {
        val fixture = Fixture(thresholds)
        fixture.rest(35)
        repeat(6) { fixture.feed(accel = Vector3(0f, 0f, 0.08f)) }
        fixture.rest(35)

        assertTrue(fixture.events.isEmpty())
    }

    @Test
    fun `free fall followed by impact emits throw up once`() {
        val fixture = Fixture(thresholds)
        fixture.rest(35)
        repeat(6) { fixture.feed(accel = Vector3(0f, 0f, 0.08f)) }
        fixture.feed(accel = Vector3(0f, 0f, 3.1f))
        fixture.rest(50)

        assertEquals(listOf(GestureType.TAP), fixture.events)
    }

    @Test
    fun `flip requires rotation and a stable upside down finish`() {
        val fixture = Fixture(thresholds)
        fixture.rest(35)
        repeat(10) {
            fixture.feed(
                roll = 180f,
                gyro = Vector3(250f, 0f, 0f),
                accel = Vector3(0f, 0f, -1f),
            )
        }
        fixture.rest(35, roll = 180f, accel = Vector3(0f, 0f, -1f))

        assertEquals(listOf(GestureType.FLIP), fixture.events)
    }

    @Test
    fun `manual recording analyzer finalizes motion at stop`() {
        val fixture = Fixture(thresholds)
        fixture.rest(35)
        repeat(10) { index ->
            val angleDegrees = -(index + 1) * 6f
            val angleRadians = Math.toRadians(angleDegrees.toDouble())
            fixture.feed(
                roll = angleDegrees,
                gyro = Vector3(120f, 0f, 0f),
                accel = Vector3(0f, sin(angleRadians).toFloat(), cos(angleRadians).toFloat()),
            )
        }

        val result = GestureRecordingAnalyzer().analyze(fixture.samples, thresholds)

        assertEquals(GestureType.LEAN, result.strongestEvent?.type)
        assertTrue(result.sampleCount >= 40)
        assertTrue(result.durationMillis > 300L)
    }

    @Test
    fun `manual recording analyzer reports no event for rest`() {
        val fixture = Fixture(thresholds)
        fixture.rest(150, roll = 30f)

        val result = GestureRecordingAnalyzer().analyze(fixture.samples, thresholds)

        assertNull(result.strongestEvent)
    }

    @Test
    fun `all fake device sequences survive filtering and classify as requested`() {
        assumeTrue("FakeTrikiDataSource exists only in debug builds", BuildConfig.DEBUG)
        val source = FakeTrikiDataSource()
        val calibration = CalibrationProfile(sampleCount = 100, calibratedAtMillis = 1L)

        GestureType.entries.forEach { expected ->
            val filter = SensorFilter()
            val engine = GestureEngine()
            val detected = source.generate(expected, startNanos = PERIOD_NANOS)
                .flatMap { sample ->
                    val filtered = filter.process(sample, calibration, thresholds)
                    engine.process(filtered, thresholds)
                }
                .map { it.type }

            assertTrue("Expected $expected, detected $detected", expected in detected)
        }
    }

    @Test
    fun `personalized model recognizes flip when cap starts on its side`() {
        var timestamp = 0L
        val sideCapture = buildList {
            repeat(35) {
                timestamp += PERIOD_NANOS
                add(filtered(timestamp, 0f, Vector3(0f, 0f, 0f), Vector3(1f, 0f, 0f)))
            }
            repeat(20) {
                timestamp += PERIOD_NANOS
                add(filtered(timestamp, 0f, Vector3(0f, 250f, 0f), Vector3(-1f, 0f, 0f)))
            }
            repeat(70) {
                timestamp += PERIOD_NANOS
                add(filtered(timestamp, 0f, Vector3(0f, 0f, 0f), Vector3(-1f, 0f, 0f)))
            }
        }
        val features = requireNotNull(GestureFeatureExtractor().extract(sideCapture).features)
        val model = PersonalizedGestureModel(
            samples = listOf(
                LearnedGestureSample(GestureType.FLIP, features, 1L),
                LearnedGestureSample(GestureType.FLIP, features, 2L),
            ),
        )
        val engine = GestureEngine()

        val detected = sideCapture
            .flatMap { engine.process(it, thresholds, model) }
            .map { it.type }

        assertNotNull(engine.lastCapturedFeatures)
        assertEquals(GestureType.FLIP, engine.lastPersonalizedRecognition?.gesture)
        assertEquals(listOf(GestureType.FLIP), detected)
    }

    @Test
    fun `mislabeled learned samples cannot veto a valid base gesture`() {
        val model = PersonalizedGestureModel(
            samples = listOf(
                LearnedGestureSample(
                    GestureType.ROTATE_LEFT,
                    GestureFeatureVector(values = List(GESTURE_FEATURE_DIMENSION) { 0f }),
                    1L,
                ),
                LearnedGestureSample(
                    GestureType.ROTATE_LEFT,
                    GestureFeatureVector(values = List(GESTURE_FEATURE_DIMENSION) { 0f }),
                    2L,
                ),
            ),
        )
        val engine = GestureEngine()
        var timestamp = 0L
        val events = mutableListOf<GestureType>()
        repeat(35) {
            timestamp += PERIOD_NANOS
            events += engine.process(filtered(timestamp, 0f, Vector3(0f, 0f, 0f), Vector3(0f, 0f, 1f)), thresholds, model)
                .map { it.type }
        }
        repeat(7) {
            timestamp += PERIOD_NANOS
            events += engine.process(filtered(timestamp, 0f, Vector3(0f, 0f, -430f), Vector3(0f, 0f, 1f)), thresholds, model)
                .map { it.type }
        }
        repeat(4) {
            timestamp += PERIOD_NANOS
            events += engine.process(
                filtered(timestamp, 0f, Vector3(0f, 0f, 0f), Vector3(0f, 0f, 1f)),
                thresholds,
                model,
            ).map { it.type }
        }

        assertEquals(listOf(GestureType.ROTATE_LEFT), events)
    }

    private class Fixture(private val thresholds: GestureThresholds) {
        private val engine = GestureEngine()
        private var timeNanos = 0L
        val events = mutableListOf<GestureType>()
        val samples = mutableListOf<FilteredSensorData>()

        fun rest(
            count: Int,
            roll: Float = 0f,
            accel: Vector3 = Vector3(0f, 0f, 1f),
        ) {
            repeat(count) { feed(roll = roll, accel = accel) }
        }

        fun shakeCycle() {
            repeat(5) {
                feed(
                    gyro = Vector3(360f, 300f, 120f),
                    accel = Vector3(0.5f, 0f, 1.2f),
                )
            }
            repeat(5) {
                feed(
                    gyro = Vector3(-360f, -300f, -120f),
                    accel = Vector3(-0.9f, 0f, 0.8f),
                )
            }
        }

        fun feed(
            roll: Float = 0f,
            gyro: Vector3 = Vector3(0f, 0f, 0f),
            accel: Vector3 = Vector3(0f, 0f, 1f),
        ) {
            timeNanos += PERIOD_NANOS
            val sample = filtered(timeNanos, roll, gyro, accel)
            samples += sample
            events += engine.process(sample, thresholds).map { it.type }
        }
    }

    private companion object {
        const val PERIOD_NANOS = 10_000_000L

        fun filtered(
            time: Long,
            roll: Float,
            gyro: Vector3,
            accel: Vector3,
        ): FilteredSensorData {
            val source = TrikiSensorData(
                frameIndex = time / PERIOD_NANOS,
                timestampNanos = time,
                gyroscopeDps = gyro,
                accelerometerG = accel,
                rawGyroscope = RawVector3(0, 0, 0),
                rawAccelerometer = RawVector3(0, 0, 0),
                status = 0,
            )
            return FilteredSensorData(source, gyro, accel, OrientationData(roll = roll))
        }
    }
}
