package pl.trikimusic.controller.core.gesture

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.trikimusic.controller.domain.model.CalibrationProfile
import pl.trikimusic.controller.domain.model.GESTURE_FEATURE_DIMENSION
import pl.trikimusic.controller.domain.model.GestureFeatureVector
import pl.trikimusic.controller.domain.model.GestureThresholds
import pl.trikimusic.controller.domain.model.GestureType
import pl.trikimusic.controller.domain.model.LearnedGestureSample
import pl.trikimusic.controller.domain.model.PersonalizedGestureModel
import pl.trikimusic.controller.domain.model.RawVector3
import pl.trikimusic.controller.domain.model.TrikiSensorData
import pl.trikimusic.controller.domain.model.Vector3

class PersonalizedGestureClassifierTest {
    private val extractor = GestureFeatureExtractor()
    private val classifier = PersonalizedGestureClassifier()

    @Test
    fun `feature extractor combines accelerometer and gyroscope for every gesture`() {
        GestureType.entries.forEach { gesture ->
            val result = extractor.extract(filteredCapture(gesture))

            assertTrue("Quality rejected for $gesture: ${result.message}", result.qualityAccepted)
            assertEquals(GESTURE_FEATURE_DIMENSION, result.features?.values?.size)
            assertTrue(requireNotNull(result.features).isValid)
        }
    }

    @Test
    fun `gravity relative features remain close after rotating cap around vertical axis`() {
        val original = requireNotNull(extractor.extract(filteredCapture(GestureType.TILT_LEFT)).features)
        val rotated = requireNotNull(
            extractor.extract(filteredCapture(GestureType.TILT_LEFT, rotationQuarterTurns = 1)).features,
        )

        for (index in 0..15) {
            assertTrue(
                "Invariant feature $index differs: ${original.values[index]} vs ${rotated.values[index]}",
                abs(original.values[index] - rotated.values[index]) < 0.09f,
            )
        }
        assertTrue(classifier.distance(original, rotated) < 0.18f)
    }

    @Test
    fun `two examples teach local knn model and classify rotation from another cap position`() {
        val leftOriginal = requireNotNull(extractor.extract(filteredCapture(GestureType.ROTATE_LEFT)).features)
        val leftRotated = requireNotNull(
            extractor.extract(filteredCapture(GestureType.ROTATE_LEFT, rotationQuarterTurns = 1)).features,
        )
        val rightOriginal = requireNotNull(extractor.extract(filteredCapture(GestureType.ROTATE_RIGHT)).features)
        val rightRotated = requireNotNull(
            extractor.extract(filteredCapture(GestureType.ROTATE_RIGHT, rotationQuarterTurns = 1)).features,
        )
        val model = modelOf(
            GestureType.ROTATE_LEFT to leftOriginal,
            GestureType.ROTATE_LEFT to leftRotated,
            GestureType.ROTATE_RIGHT to rightOriginal,
            GestureType.ROTATE_RIGHT to rightRotated,
        )
        val query = requireNotNull(
            extractor.extract(filteredCapture(GestureType.ROTATE_LEFT, rotationQuarterTurns = 3)).features,
        )

        val recognition = classifier.classify(query, model)

        assertNotNull(recognition)
        assertEquals(GestureType.ROTATE_LEFT, recognition?.gesture)
        assertEquals(2, recognition?.trainedSampleCount)
    }

    @Test
    fun `physical gate rejects unrelated rest vector even when numerically close`() {
        val learned = requireNotNull(extractor.extract(filteredCapture(GestureType.THROW_UP)).features)
        val model = modelOf(GestureType.THROW_UP to learned, GestureType.THROW_UP to learned)
        val invalid = learned.copy(
            values = learned.values.toMutableList().apply {
                this[8] = 1f
                this[9] = 1f / 3.5f
            },
        )

        assertNull(classifier.classify(invalid, model))
    }

    private fun filteredCapture(
        gesture: GestureType,
        rotationQuarterTurns: Int = 0,
    ) = generateCapture(gesture)
        .map { sample -> rotateAroundZ(sample, rotationQuarterTurns) }
        .let { samples ->
            val filter = SensorFilter()
            val calibration = CalibrationProfile(sampleCount = 100, calibratedAtMillis = 1L)
            val thresholds = GestureThresholds()
            samples.map { filter.process(it, calibration, thresholds) }
        }

    private fun generateCapture(gesture: GestureType): List<TrikiSensorData> {
        val sequence = when (gesture) {
            GestureType.TILT_LEFT -> ramp(Vector3(140f, 0f, 0f)) { index ->
                Vector3(0f, -0.58f * index / 35f, 1f - 0.45f * index / 35f)
            }
            GestureType.TILT_RIGHT -> ramp(Vector3(-140f, 0f, 0f)) { index ->
                Vector3(0f, 0.58f * index / 35f, 1f - 0.45f * index / 35f)
            }
            GestureType.ROTATE_LEFT -> pulse(Vector3(0f, 0f, -420f))
            GestureType.ROTATE_RIGHT -> pulse(Vector3(0f, 0f, 420f))
            GestureType.THROW_UP -> listOf(
                TestMotion(35),
                TestMotion(10, accelerometer = Vector3(0f, 0f, 0.08f)),
                TestMotion(8, accelerometer = Vector3(0f, 0f, 2.8f)),
                TestMotion(70),
            )
            GestureType.SHAKE -> shakePulses(1)
            GestureType.DOUBLE_SHAKE -> shakePulses(2)
            GestureType.FLIP -> listOf(
                TestMotion(35),
                TestMotion(20, Vector3(250f, 0f, 0f), Vector3(0f, 0.2f, -0.98f)),
                TestMotion(180, accelerometer = Vector3(0f, 0f, -1f)),
            )
        }
        var frame = 0L
        return sequence.flatMap { motion ->
            List(motion.sampleCount) {
                val timestamp = 10_000_000L + frame * 10_000_000L
                TrikiSensorData(
                    frameIndex = frame++,
                    timestampNanos = timestamp,
                    gyroscopeDps = motion.gyroscope,
                    accelerometerG = motion.accelerometer,
                    rawGyroscope = RawVector3(0, 0, 0),
                    rawAccelerometer = RawVector3(0, 0, 0),
                    status = 0,
                )
            }
        }
    }

    private fun ramp(
        gyroscope: Vector3,
        accelerometer: (Int) -> Vector3,
    ): List<TestMotion> = listOf(TestMotion(35)) +
        (0 until 36).map { TestMotion(1, gyroscope, accelerometer(it)) } +
        TestMotion(120)

    private fun pulse(gyroscope: Vector3): List<TestMotion> = listOf(
        TestMotion(35),
        TestMotion(10, gyroscope, Vector3(0.2f, 0f, 1.15f)),
        TestMotion(70),
    )

    private fun shakePulses(count: Int): List<TestMotion> = buildList {
        add(TestMotion(35))
        repeat(count) {
            add(TestMotion(10, Vector3(360f, 300f, 120f), Vector3(0.5f, 0f, 1.2f)))
            add(TestMotion(10, Vector3(-360f, -300f, -120f), Vector3(-0.9f, 0f, 0.8f)))
            add(TestMotion(18))
        }
        add(TestMotion(80))
    }

    private fun rotateAroundZ(sample: TrikiSensorData, quarterTurns: Int): TrikiSensorData {
        fun rotate(vector: Vector3): Vector3 {
            var result = vector
            repeat(Math.floorMod(quarterTurns, 4)) {
                result = Vector3(-result.y, result.x, result.z)
            }
            return result
        }
        return sample.copy(
            gyroscopeDps = rotate(sample.gyroscopeDps),
            accelerometerG = rotate(sample.accelerometerG),
        )
    }

    private fun modelOf(vararg samples: Pair<GestureType, GestureFeatureVector>): PersonalizedGestureModel =
        PersonalizedGestureModel(
            samples = samples.mapIndexed { index, (gesture, features) ->
                LearnedGestureSample(gesture, features, capturedAtMillis = index.toLong())
            },
        )

    private data class TestMotion(
        val sampleCount: Int,
        val gyroscope: Vector3 = Vector3(0f, 0f, 0f),
        val accelerometer: Vector3 = Vector3(0f, 0f, 1f),
    )
}
