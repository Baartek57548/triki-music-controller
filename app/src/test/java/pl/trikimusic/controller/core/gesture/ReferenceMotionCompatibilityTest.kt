package pl.trikimusic.controller.core.gesture

import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.trikimusic.controller.domain.model.CalibrationProfile
import pl.trikimusic.controller.domain.model.GestureThresholds
import pl.trikimusic.controller.domain.model.GestureType
import pl.trikimusic.controller.domain.model.LearnedGestureSample
import pl.trikimusic.controller.domain.model.PersonalizedGestureModel
import pl.trikimusic.controller.domain.model.RawVector3
import pl.trikimusic.controller.domain.model.TrikiSensorData
import pl.trikimusic.controller.domain.model.Vector3

/**
 * Cross-project compatibility checks based on the public TRIKI-Control motion profiles.
 * Values stay in the physical Triki wire scale until the same conversion and filter used
 * by production code, so these tests expose sign, sensitivity, and sampling-rate regressions.
 */
class ReferenceMotionCompatibilityTest {
    @Test
    fun `short reference twists control both volume directions`() {
        val positive = recognize(twistSequence(rawRate = 1_400))
        val negative = recognize(twistSequence(rawRate = -1_400))

        assertEquals(listOf(GestureType.ROTATE_LEFT), positive)
        assertEquals(listOf(GestureType.ROTATE_RIGHT), negative)
    }

    @Test
    fun `twist projection keeps its intent in arbitrary cap positions`() {
        val gravityVectors = listOf(
            Vector3(0f, 0f, -1f),
            Vector3(1f, 0f, 0f),
            Vector3(0f, -1f, 0f),
            Vector3(0.57735f, 0.57735f, -0.57735f),
        )

        gravityVectors.forEach { gravity ->
            val twistGyroscope = Vector3(gravity.x * 98f, gravity.y * 98f, gravity.z * 98f)
            val frames = List(90) { frame(accel = gravity) } +
                List(4) { frame(gyro = twistGyroscope, accel = gravity) } +
                List(40) { frame(accel = gravity) }

            assertEquals("Unexpected result for gravity=$gravity", listOf(GestureType.ROTATE_RIGHT), recognize(frames))
        }
    }

    @Test
    fun `gentle reference lean is recognized after returning to rest`() {
        assertEquals(listOf(GestureType.LEAN), recognize(leanSequence()))
    }

    @Test
    fun `flat reference slide is separated from tilt and tap`() {
        assertEquals(listOf(GestureType.SLIDE), recognize(slideSequence()))
    }

    @Test
    fun `single reference stamp is play pause and not noise`() {
        assertEquals(listOf(GestureType.TAP), recognize(tapSequence()))
    }

    @Test
    fun `reference flip reaches stop gesture without an intermediate lean`() {
        assertEquals(listOf(GestureType.FLIP), recognize(flipSequence()))
    }

    @Test
    fun `filtered return after flip is silent and allows the next twist`() {
        val frames = flipSequence() + returnFromFlip() + twist(rawRate = 1_400) + rest(40)

        assertEquals(listOf(GestureType.FLIP, GestureType.ROTATE_LEFT), recognize(frames))
    }

    @Test
    fun `negative Z rest and sensor noise remain silent`() {
        val noisyRest = List(300) { index ->
            val sign = if (index % 2 == 0) 1f else -1f
            frame(
                gyro = Vector3(sign * 2.1f, -sign * 1.4f, sign * 0.7f),
                accel = Vector3(REST_X_G + sign * 0.004f, sign * 0.003f, REST_Z_G - sign * 0.004f),
            )
        }

        assertTrue(recognize(noisyRest).isEmpty())
    }

    @Test
    fun `all core reference motions produce learnable dual sensor features`() {
        val captures = linkedMapOf(
            GestureType.ROTATE_LEFT to twistSequence(1_400),
            GestureType.ROTATE_RIGHT to twistSequence(-1_400),
            GestureType.LEAN to leanSequence(),
            GestureType.SLIDE to slideSequence(),
            GestureType.TAP to tapSequence(),
            GestureType.FLIP to flipSequence(),
        )
        val extractor = GestureFeatureExtractor()
        val extracted = captures.mapValues { (_, frames) -> extractor.extract(filterFrames(frames)) }
        extracted.forEach { (gesture, result) ->
            assertTrue("Feature quality rejected for $gesture: ${result.message}", result.qualityAccepted)
        }
        val learnedSamples = extracted.entries.flatMapIndexed { index, (gesture, result) ->
            val features = requireNotNull(result.features)
            listOf(
                LearnedGestureSample(gesture, features, index * 2L + 1L),
                LearnedGestureSample(gesture, features, index * 2L + 2L),
            )
        }
        val model = PersonalizedGestureModel(samples = learnedSamples)
        val classifier = PersonalizedGestureClassifier()

        extracted.forEach { (expected, result) ->
            val recognition = classifier.classify(requireNotNull(result.features), model)
            assertEquals("Personalized classifier mismatch for $expected", expected, recognition?.gesture)
        }
    }

    @Test
    fun `all core gestures survive arbitrary cap positions and realistic dual sensor noise`() {
        val captures = coreCaptures()
        val rotations = listOf(
            Rotation(Vector3(1f, 0f, 0f), 0.0),
            Rotation(Vector3(1f, 0f, 0f), 90.0),
            Rotation(Vector3(0f, 1f, 0f), 90.0),
            Rotation(Vector3(1f, 1f, 1f), 120.0),
            Rotation(Vector3(0.3f, -0.7f, 0.64f), 137.0),
        )

        rotations.forEachIndexed { rotationIndex, rotation ->
            captures.forEach { (expected, frames) ->
                val transformed = frames.mapIndexed { frameIndex, frame ->
                    PhysicalFrame(
                        gyroscopeDps = addNoise(rotation.apply(frame.gyroscopeDps), frameIndex, rotationIndex, GYRO_NOISE_DPS),
                        accelerometerG = addNoise(rotation.apply(frame.accelerometerG), frameIndex, rotationIndex + 3, ACCEL_NOISE_G),
                    )
                }
                assertEquals(
                    "Unexpected $expected for rotation=$rotationIndex",
                    listOf(expected),
                    recognize(transformed),
                )
            }
        }
    }

    @Test
    fun `all core gestures survive sampling rate variation and timestamp jitter`() {
        val targetPeriods = listOf(
            15_384_615L, // 65 Hz
            SAMPLE_PERIOD_NANOS,
            25_000_000L, // 40 Hz
        )

        targetPeriods.forEach { targetPeriod ->
            coreCaptures().forEach { (expected, original) ->
                val resampled = resample(original, targetPeriod)
                val intervals = List(resampled.size) { index ->
                    val jitter = JITTER_PATTERN_NANOS[index % JITTER_PATTERN_NANOS.size]
                    (targetPeriod + jitter).coerceAtLeast(5_000_000L)
                }
                assertEquals(
                    "Unexpected $expected for period=${targetPeriod / 1_000_000f} ms",
                    listOf(expected),
                    recognize(resampled, intervals),
                )
            }
        }
    }

    private fun coreCaptures(): LinkedHashMap<GestureType, List<PhysicalFrame>> = linkedMapOf(
        GestureType.ROTATE_LEFT to twistSequence(1_400),
        GestureType.ROTATE_RIGHT to twistSequence(-1_400),
        GestureType.LEAN to leanSequence(),
        GestureType.SLIDE to slideSequence(),
        GestureType.TAP to tapSequence(),
        GestureType.FLIP to flipSequence(),
    )

    private fun recognize(
        frames: List<PhysicalFrame>,
        intervalsNanos: List<Long>? = null,
    ): List<GestureType> {
        val engine = GestureEngine()
        val thresholds = GestureThresholds()
        return filterFrames(frames, intervalsNanos).flatMap { sample -> engine.process(sample, thresholds).map { it.type } }
    }

    private fun filterFrames(
        frames: List<PhysicalFrame>,
        intervalsNanos: List<Long>? = null,
    ) = buildList {
        require(intervalsNanos == null || intervalsNanos.size == frames.size)
        val filter = SensorFilter()
        val thresholds = GestureThresholds()
        val calibration = CalibrationProfile(sampleCount = 100, calibratedAtMillis = 1L)
        var timestampNanos = 0L
        frames.forEachIndexed { index, physical ->
            timestampNanos += intervalsNanos?.get(index) ?: SAMPLE_PERIOD_NANOS
            val sensor = TrikiSensorData(
                frameIndex = index.toLong(),
                timestampNanos = timestampNanos,
                gyroscopeDps = physical.gyroscopeDps,
                accelerometerG = physical.accelerometerG,
                rawGyroscope = RawVector3(
                    (physical.gyroscopeDps.x / GYROSCOPE_DPS_PER_LSB).roundToInt().toShort(),
                    (physical.gyroscopeDps.y / GYROSCOPE_DPS_PER_LSB).roundToInt().toShort(),
                    (physical.gyroscopeDps.z / GYROSCOPE_DPS_PER_LSB).roundToInt().toShort(),
                ),
                rawAccelerometer = RawVector3(
                    (physical.accelerometerG.x * ACCEL_LSB_PER_G).roundToInt().toShort(),
                    (physical.accelerometerG.y * ACCEL_LSB_PER_G).roundToInt().toShort(),
                    (physical.accelerometerG.z * ACCEL_LSB_PER_G).roundToInt().toShort(),
                ),
                status = 0,
            )
            add(filter.process(sensor, calibration, thresholds))
        }
    }

    private fun resample(frames: List<PhysicalFrame>, targetPeriodNanos: Long): List<PhysicalFrame> {
        require(frames.isNotEmpty())
        require(targetPeriodNanos > 0L)
        val durationNanos = (frames.lastIndex * SAMPLE_PERIOD_NANOS).coerceAtLeast(0L)
        val targetLastIndex = (durationNanos.toDouble() / targetPeriodNanos).roundToInt()
        return (0..targetLastIndex).map { targetIndex ->
            val sourceIndex = (targetIndex.toDouble() * targetPeriodNanos / SAMPLE_PERIOD_NANOS)
                .roundToInt()
                .coerceIn(0, frames.lastIndex)
            frames[sourceIndex]
        }
    }

    private fun addNoise(
        vector: Vector3,
        frameIndex: Int,
        phase: Int,
        amplitude: Float,
    ): Vector3 = Vector3(
        vector.x + noise(frameIndex, phase) * amplitude,
        vector.y + noise(frameIndex, phase + 7) * amplitude,
        vector.z + noise(frameIndex, phase + 13) * amplitude,
    )

    private fun noise(frameIndex: Int, phase: Int): Float {
        val value = ((frameIndex * 37 + phase * 17 + 11) % 101) / 50f - 1f
        return value.coerceIn(-1f, 1f)
    }

    private fun rest(samples: Int = 90): List<PhysicalFrame> =
        List(samples) { frame(accel = Vector3(REST_X_G, 0f, REST_Z_G)) }

    private fun twist(rawRate: Int): List<PhysicalFrame> =
        List(4) {
            frame(
                gyro = Vector3(0f, 0f, rawRate * GYROSCOPE_DPS_PER_LSB),
                accel = Vector3(REST_X_G, 0f, REST_Z_G),
            )
        }

    private fun twistSequence(rawRate: Int): List<PhysicalFrame> = rest() + twist(rawRate) + rest(40)

    private fun leanSequence(): List<PhysicalFrame> = buildList {
        addAll(rest())
        val rampSamples = 15
        repeat(rampSamples) { index ->
            val angle = 14.0 * (index + 1) / rampSamples
            add(leanFrame(angle, gyroscopeDps = 47f))
        }
        repeat(25) { add(leanFrame(14.0, gyroscopeDps = 0f)) }
        repeat(rampSamples) { index ->
            val angle = 14.0 * (rampSamples - index - 1) / rampSamples
            add(leanFrame(angle, gyroscopeDps = -47f))
        }
        addAll(rest(40))
    }

    private fun slideSequence(): List<PhysicalFrame> = buildList {
        addAll(rest())
        repeat(8) { add(frame(gyro = Vector3(15f, 4f, 0f), accel = Vector3(0.18f, 0f, REST_Z_G))) }
        repeat(8) { add(frame(gyro = Vector3(-15f, -4f, 0f), accel = Vector3(-0.18f, 0f, REST_Z_G))) }
        addAll(rest(40))
    }

    private fun tapSequence(): List<PhysicalFrame> =
        rest() + frame(accel = Vector3(REST_X_G, 0f, -2_600f / ACCEL_LSB_PER_G)) + rest(40)

    private fun flipSequence(): List<PhysicalFrame> = buildList {
        addAll(rest())
        val rampSamples = 15
        repeat(rampSamples) { index ->
            val angle = 180.0 * (index + 1) / rampSamples
            val radians = Math.toRadians(angle)
            add(
                frame(
                    gyro = Vector3(600f, 0f, 0f),
                    accel = Vector3(
                        REST_X_G,
                        (sin(radians) * REST_MAGNITUDE_G).toFloat(),
                        (-cos(radians) * REST_MAGNITUDE_G).toFloat(),
                    ),
                ),
            )
        }
        repeat(50) { add(frame(accel = Vector3(REST_X_G, 0f, REST_MAGNITUDE_G))) }
    }

    private fun returnFromFlip(): List<PhysicalFrame> = buildList {
        val rampSamples = 15
        repeat(rampSamples) { index ->
            val angle = 180.0 * (rampSamples - index - 1) / rampSamples
            val radians = Math.toRadians(angle)
            add(
                frame(
                    gyro = Vector3(-600f, 0f, 0f),
                    accel = Vector3(
                        REST_X_G,
                        (sin(radians) * REST_MAGNITUDE_G).toFloat(),
                        (-cos(radians) * REST_MAGNITUDE_G).toFloat(),
                    ),
                ),
            )
        }
        addAll(rest(40))
    }

    private fun leanFrame(angleDegrees: Double, gyroscopeDps: Float): PhysicalFrame {
        val radians = Math.toRadians(angleDegrees)
        return frame(
            gyro = Vector3(gyroscopeDps, 0f, 0f),
            accel = Vector3(
                REST_X_G,
                (sin(radians) * REST_MAGNITUDE_G).toFloat(),
                (-cos(radians) * REST_MAGNITUDE_G).toFloat(),
            ),
        )
    }

    private fun frame(
        gyro: Vector3 = Vector3(0f, 0f, 0f),
        accel: Vector3 = Vector3(REST_X_G, 0f, REST_Z_G),
    ): PhysicalFrame = PhysicalFrame(gyro, accel)

    private data class PhysicalFrame(
        val gyroscopeDps: Vector3,
        val accelerometerG: Vector3,
    )

    private data class Rotation(
        val axis: Vector3,
        val angleDegrees: Double,
    ) {
        fun apply(vector: Vector3): Vector3 {
            val axisMagnitude = sqrt(axis.x * axis.x + axis.y * axis.y + axis.z * axis.z)
            require(axisMagnitude > 0f)
            val unit = Vector3(axis.x / axisMagnitude, axis.y / axisMagnitude, axis.z / axisMagnitude)
            val radians = Math.toRadians(angleDegrees)
            val cosine = cos(radians).toFloat()
            val sine = sin(radians).toFloat()
            val dot = unit.x * vector.x + unit.y * vector.y + unit.z * vector.z
            val cross = Vector3(
                unit.y * vector.z - unit.z * vector.y,
                unit.z * vector.x - unit.x * vector.z,
                unit.x * vector.y - unit.y * vector.x,
            )
            return Vector3(
                vector.x * cosine + cross.x * sine + unit.x * dot * (1f - cosine),
                vector.y * cosine + cross.y * sine + unit.y * dot * (1f - cosine),
                vector.z * cosine + cross.z * sine + unit.z * dot * (1f - cosine),
            )
        }
    }

    private companion object {
        const val SAMPLE_PERIOD_NANOS = 19_230_769L
        const val GYROSCOPE_DPS_PER_LSB = 0.070f
        const val ACCEL_LSB_PER_G = 2_048f
        const val REST_X_G = 24f / ACCEL_LSB_PER_G
        const val REST_Z_G = -2_051f / ACCEL_LSB_PER_G
        const val REST_MAGNITUDE_G = 2_050f / ACCEL_LSB_PER_G
        const val GYRO_NOISE_DPS = 2.2f
        const val ACCEL_NOISE_G = 0.006f
        val JITTER_PATTERN_NANOS = longArrayOf(-2_000_000L, 1_000_000L, 0L, 2_500_000L, -1_500_000L)
    }
}
