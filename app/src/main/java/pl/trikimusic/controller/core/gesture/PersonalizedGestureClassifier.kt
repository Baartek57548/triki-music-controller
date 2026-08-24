package pl.trikimusic.controller.core.gesture

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.sqrt
import pl.trikimusic.controller.domain.model.FilteredSensorData
import pl.trikimusic.controller.domain.model.GESTURE_FEATURE_DIMENSION
import pl.trikimusic.controller.domain.model.GestureFeatureVector
import pl.trikimusic.controller.domain.model.GestureType
import pl.trikimusic.controller.domain.model.MIN_PERSONALIZED_SAMPLES_PER_GESTURE
import pl.trikimusic.controller.domain.model.PersonalizedGestureModel
import pl.trikimusic.controller.domain.model.Vector3

data class GestureFeatureExtractionResult(
    val features: GestureFeatureVector?,
    val qualityScore: Float,
    val qualityAccepted: Boolean,
    val message: String,
)

data class PersonalizedRecognition(
    val gesture: GestureType,
    val confidence: Float,
    val distance: Float,
    val trainedSampleCount: Int,
)

/**
 * Produces a compact feature vector from both IMU sensors. Most features are magnitudes,
 * gravity-relative projections, or changes from the first stable pose. This limits dependence
 * on the absolute orientation in which the cap was placed while retaining signed axis features
 * that distinguish gesture pairs learned by the user.
 */
class GestureFeatureExtractor {
    fun extract(samples: List<FilteredSensorData>): GestureFeatureExtractionResult {
        val ordered = samples
            .asSequence()
            .filter { it.source.timestampNanos >= 0L }
            .distinctBy { it.source.timestampNanos }
            .sortedBy { it.source.timestampNanos }
            .toList()
        if (ordered.size < MIN_FEATURE_SAMPLES) {
            return rejected("Za mało danych z akcelerometru i żyroskopu do utworzenia próbki.")
        }

        val active = BooleanArray(ordered.size)
        var previousAcceleration = ordered.first().accelerometerG
        ordered.forEachIndexed { index, sample ->
            val accelerationStep = (sample.accelerometerG - previousAcceleration).magnitude
            active[index] = sample.gyroscopeMagnitude >= ACTIVE_GYROSCOPE_DPS ||
                abs(sample.accelerationMagnitude - 1f) >= ACTIVE_ACCELERATION_DELTA_G ||
                accelerationStep >= ACTIVE_ACCELERATION_STEP_G
            previousAcceleration = sample.accelerometerG
        }
        val firstActive = active.indexOfFirst { it }
        val lastActive = active.indexOfLast { it }
        if (firstActive < 0 || lastActive < firstActive) {
            return rejected("Nie znaleziono wyraźnego ruchu w nagraniu obu czujników.")
        }

        val segmentStart = (firstActive - FEATURE_PADDING_SAMPLES).coerceAtLeast(0)
        val segmentEnd = (lastActive + FEATURE_PADDING_SAMPLES).coerceAtMost(ordered.lastIndex)
        val segment = ordered.subList(segmentStart, segmentEnd + 1)
        val recordingDurationMillis = durationMillis(ordered)
        val activeDurationSeconds = durationSeconds(ordered[firstActive], ordered[lastActive])
        val initialGravity = gravityEstimate(
            ordered.subList((firstActive - GRAVITY_WINDOW_SAMPLES).coerceAtLeast(0), (firstActive + 1).coerceAtMost(ordered.size)),
        )
        val finalGravity = gravityEstimate(
            ordered.subList(lastActive, (lastActive + GRAVITY_WINDOW_SAMPLES + 1).coerceAtMost(ordered.size)),
        )

        var peakGyroscope = 0f
        var gyroscopeSquareSum = 0f
        var gyroscopeSum = 0f
        var peakAccelerationDeviation = 0f
        var accelerationDeviationSquareSum = 0f
        var accelerationDeviationSum = 0f
        var minimumAcceleration = Float.POSITIVE_INFINITY
        var maximumAcceleration = 0f
        var angularPathDegrees = 0f
        var verticalRotationDegrees = 0f
        var signedPeakVerticalGyroscope = 0f
        var maximumGravityAngleDegrees = 0f
        var reversalCount = 0
        var freeFallSamples = 0
        var integratedGyroscope = ZERO_VECTOR
        var peakAccelerationStep = 0f
        var previousGyroscope: Vector3? = null
        var previousSample: FilteredSensorData? = null
        var previousAccelerationForStep: Vector3? = null
        val temporalGyroscope = FloatArray(TEMPORAL_BINS)
        val temporalAcceleration = FloatArray(TEMPORAL_BINS)
        val temporalCounts = IntArray(TEMPORAL_BINS)
        val segmentDurationNanos = (segment.last().source.timestampNanos - segment.first().source.timestampNanos)
            .coerceAtLeast(1L)

        segment.forEach { sample ->
            val gyroscopeMagnitude = sample.gyroscopeMagnitude
            val accelerationMagnitude = sample.accelerationMagnitude
            val accelerationDeviation = abs(accelerationMagnitude - 1f)
            peakGyroscope = max(peakGyroscope, gyroscopeMagnitude)
            gyroscopeSquareSum += gyroscopeMagnitude * gyroscopeMagnitude
            gyroscopeSum += gyroscopeMagnitude
            peakAccelerationDeviation = max(peakAccelerationDeviation, accelerationDeviation)
            accelerationDeviationSquareSum += accelerationDeviation * accelerationDeviation
            accelerationDeviationSum += accelerationDeviation
            minimumAcceleration = minOf(minimumAcceleration, accelerationMagnitude)
            maximumAcceleration = max(maximumAcceleration, accelerationMagnitude)
            if (accelerationMagnitude < FREE_FALL_FEATURE_G) freeFallSamples++

            val accelerationStep = previousAccelerationForStep
                ?.let { (sample.accelerometerG - it).magnitude }
                ?: 0f
            peakAccelerationStep = max(peakAccelerationStep, accelerationStep)
            previousAccelerationForStep = sample.accelerometerG

            val dtSeconds = previousSample?.let { previous ->
                ((sample.source.timestampNanos - previous.source.timestampNanos)
                    .coerceIn(MIN_DT_NANOS, MAX_DT_NANOS)) / NANOS_PER_SECOND_F
            } ?: 0f
            if (dtSeconds > 0f) {
                angularPathDegrees += gyroscopeMagnitude * dtSeconds
                integratedGyroscope += sample.gyroscopeDps * dtSeconds
                val instantaneousGravity = reliableGravity(sample.accelerometerG) ?: initialGravity
                val verticalGyroscope = dot(sample.gyroscopeDps, instantaneousGravity)
                verticalRotationDegrees += verticalGyroscope * dtSeconds
                if (abs(verticalGyroscope) > abs(signedPeakVerticalGyroscope)) {
                    signedPeakVerticalGyroscope = verticalGyroscope
                }
            }
            previousSample = sample

            reliableGravity(sample.accelerometerG)?.let { gravity ->
                maximumGravityAngleDegrees = max(
                    maximumGravityAngleDegrees,
                    angleDegrees(initialGravity, gravity),
                )
            }

            val previous = previousGyroscope
            if (
                previous != null &&
                previous.magnitude >= REVERSAL_MIN_GYROSCOPE_DPS &&
                gyroscopeMagnitude >= REVERSAL_MIN_GYROSCOPE_DPS
            ) {
                val normalizedDot = dot(previous, sample.gyroscopeDps) / (previous.magnitude * gyroscopeMagnitude)
                if (normalizedDot <= REVERSAL_DOT_THRESHOLD) reversalCount++
            }
            if (gyroscopeMagnitude >= REVERSAL_MIN_GYROSCOPE_DPS) previousGyroscope = sample.gyroscopeDps

            val elapsed = sample.source.timestampNanos - segment.first().source.timestampNanos
            val bin = ((elapsed.toDouble() / segmentDurationNanos) * TEMPORAL_BINS)
                .toInt()
                .coerceIn(0, TEMPORAL_BINS - 1)
            temporalGyroscope[bin] += gyroscopeMagnitude
            temporalAcceleration[bin] += accelerationDeviation
            temporalCounts[bin]++
        }

        fillTemporalAverages(temporalGyroscope, temporalCounts)
        fillTemporalAverages(temporalAcceleration, temporalCounts)
        val sampleCount = segment.size.toFloat().coerceAtLeast(1f)
        val finalGravityDelta = finalGravity - initialGravity
        val finalGravityAngleDegrees = angleDegrees(initialGravity, finalGravity)
        val rootMeanSquareGyroscope = sqrt(gyroscopeSquareSum / sampleCount)
        val rootMeanSquareAccelerationDeviation = sqrt(accelerationDeviationSquareSum / sampleCount)

        val values = ArrayList<Float>(GESTURE_FEATURE_DIMENSION).apply {
            add(normalize(activeDurationSeconds, 2f))
            add(normalize(peakGyroscope, 500f))
            add(normalize(rootMeanSquareGyroscope, 300f))
            add(normalize(gyroscopeSum / sampleCount, 250f))
            add(normalize(angularPathDegrees, 300f))
            add(normalize(peakAccelerationDeviation, 2.5f))
            add(normalize(rootMeanSquareAccelerationDeviation, 0.8f))
            add(normalize(accelerationDeviationSum / sampleCount, 0.6f))
            add(normalize(minimumAcceleration.takeIf(Float::isFinite) ?: 0f, 1f))
            add(normalize(maximumAcceleration, 3.5f))
            add(normalizeSigned(verticalRotationDegrees, 180f))
            add(normalizeSigned(signedPeakVerticalGyroscope, 500f))
            add(normalize(finalGravityAngleDegrees, 180f))
            add(normalize(maximumGravityAngleDegrees, 180f))
            add(normalize(reversalCount.toFloat(), 4f))
            add((freeFallSamples / sampleCount).coerceIn(0f, 1f))
            add(normalizeSigned(integratedGyroscope.x, 180f))
            add(normalizeSigned(integratedGyroscope.y, 180f))
            add(normalizeSigned(integratedGyroscope.z, 180f))
            add(normalizeSigned(finalGravityDelta.x, 2f))
            add(normalizeSigned(finalGravityDelta.y, 2f))
            add(normalizeSigned(finalGravityDelta.z, 2f))
            add(normalize(peakAccelerationStep, 1.5f))
            temporalGyroscope.forEach { add(normalize(it, 500f)) }
            temporalAcceleration.forEach { add(normalize(it, 2.5f)) }
        }
        check(values.size == GESTURE_FEATURE_DIMENSION) { "Niezgodny rozmiar wektora cech gestu." }

        val stableStart = firstActive >= MIN_STABLE_EDGE_SAMPLES &&
            stableWindow(ordered.subList((firstActive - MIN_STABLE_EDGE_SAMPLES).coerceAtLeast(0), firstActive))
        val stableEnd = ordered.lastIndex - lastActive >= MIN_STABLE_EDGE_SAMPLES &&
            stableWindow(ordered.subList(lastActive + 1, lastActive + 1 + MIN_STABLE_EDGE_SAMPLES))
        val durationAccepted = recordingDurationMillis >= MIN_TRAINING_DURATION_MILLIS
        val meaningfulMotion = peakGyroscope >= MIN_MEANINGFUL_GYROSCOPE_DPS ||
            peakAccelerationDeviation >= MIN_MEANINGFUL_ACCELERATION_DELTA_G ||
            maximumGravityAngleDegrees >= MIN_MEANINGFUL_GRAVITY_ANGLE_DEGREES
        val qualityScore = (
            (if (meaningfulMotion) 0.35f else 0f) +
                (if (durationAccepted) 0.2f else 0f) +
                (if (stableStart) 0.225f else 0f) +
                (if (stableEnd) 0.225f else 0f)
            ).coerceIn(0f, 1f)
        val qualityAccepted = meaningfulMotion && durationAccepted && (stableStart || stableEnd) && qualityScore >= 0.7f
        val message = when {
            !durationAccepted -> "Nagranie jest za krótkie; pozostaw krótki bezruch przed lub po ruchu."
            !meaningfulMotion -> "Ruch jest zbyt słaby względem szumu obu czujników."
            !stableStart && !stableEnd -> "Brakuje chwili bezruchu potrzebnej do odjęcia pozycji początkowej."
            qualityAccepted -> "Próbka obu czujników ma dobrą jakość i może uczyć model."
            else -> "Próbka wymaga powtórzenia z wyraźniejszym początkiem lub końcem."
        }
        return GestureFeatureExtractionResult(
            features = GestureFeatureVector(values = values),
            qualityScore = qualityScore,
            qualityAccepted = qualityAccepted,
            message = message,
        )
    }

    private fun rejected(message: String) = GestureFeatureExtractionResult(
        features = null,
        qualityScore = 0f,
        qualityAccepted = false,
        message = message,
    )

    private fun stableWindow(samples: List<FilteredSensorData>): Boolean {
        if (samples.isEmpty()) return false
        val averageGyroscope = samples.sumOf { it.gyroscopeMagnitude.toDouble() }.toFloat() / samples.size
        val averageAccelerationDeviation = samples
            .sumOf { abs(it.accelerationMagnitude - 1f).toDouble() }
            .toFloat() / samples.size
        return averageGyroscope <= TRAINING_STABLE_GYROSCOPE_DPS &&
            averageAccelerationDeviation <= TRAINING_STABLE_ACCELERATION_DELTA_G
    }

    private fun gravityEstimate(samples: List<FilteredSensorData>): Vector3 {
        var sum = ZERO_VECTOR
        var count = 0
        samples.forEach { sample ->
            reliableGravity(sample.accelerometerG)?.let { gravity ->
                sum += gravity
                count++
            }
        }
        return if (count == 0) UNIT_Z_VECTOR else normalized(sum * (1f / count)) ?: UNIT_Z_VECTOR
    }

    private fun reliableGravity(acceleration: Vector3): Vector3? =
        if (acceleration.magnitude in RELIABLE_GRAVITY_MIN_G..RELIABLE_GRAVITY_MAX_G) normalized(acceleration) else null

    private fun normalized(vector: Vector3): Vector3? {
        val magnitude = vector.magnitude
        return if (magnitude > 0.0001f) vector * (1f / magnitude) else null
    }

    private fun angleDegrees(first: Vector3, second: Vector3): Float {
        val cosine = dot(first, second).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cosine.toDouble())).toFloat()
    }

    private fun fillTemporalAverages(values: FloatArray, counts: IntArray) {
        var lastValue = 0f
        values.indices.forEach { index ->
            if (counts[index] > 0) {
                values[index] /= counts[index]
                lastValue = values[index]
            } else {
                values[index] = lastValue
            }
        }
        val firstKnown = counts.indexOfFirst { it > 0 }
        if (firstKnown > 0) {
            for (index in 0 until firstKnown) values[index] = values[firstKnown]
        }
    }

    private fun durationMillis(samples: List<FilteredSensorData>): Long =
        ((samples.last().source.timestampNanos - samples.first().source.timestampNanos) / 1_000_000L)
            .coerceAtLeast(0L)

    private fun durationSeconds(first: FilteredSensorData, last: FilteredSensorData): Float =
        ((last.source.timestampNanos - first.source.timestampNanos).coerceAtLeast(0L)) / NANOS_PER_SECOND_F

    private fun normalize(value: Float, scale: Float): Float = (value / scale).coerceIn(0f, 2f)

    private fun normalizeSigned(value: Float, scale: Float): Float = (value / scale).coerceIn(-2f, 2f)

    private fun dot(first: Vector3, second: Vector3): Float =
        first.x * second.x + first.y * second.y + first.z * second.z

    private companion object {
        const val TEMPORAL_BINS = 8
        const val MIN_FEATURE_SAMPLES = 12
        const val FEATURE_PADDING_SAMPLES = 8
        const val GRAVITY_WINDOW_SAMPLES = 12
        const val MIN_STABLE_EDGE_SAMPLES = 8
        const val MIN_TRAINING_DURATION_MILLIS = 550L
        const val ACTIVE_GYROSCOPE_DPS = 24f
        const val ACTIVE_ACCELERATION_DELTA_G = 0.1f
        const val ACTIVE_ACCELERATION_STEP_G = 0.05f
        const val TRAINING_STABLE_GYROSCOPE_DPS = 38f
        const val TRAINING_STABLE_ACCELERATION_DELTA_G = 0.2f
        const val MIN_MEANINGFUL_GYROSCOPE_DPS = 30f
        const val MIN_MEANINGFUL_ACCELERATION_DELTA_G = 0.13f
        const val MIN_MEANINGFUL_GRAVITY_ANGLE_DEGREES = 10f
        const val RELIABLE_GRAVITY_MIN_G = 0.68f
        const val RELIABLE_GRAVITY_MAX_G = 1.32f
        const val FREE_FALL_FEATURE_G = 0.5f
        const val REVERSAL_MIN_GYROSCOPE_DPS = 90f
        const val REVERSAL_DOT_THRESHOLD = -0.4f
        const val MIN_DT_NANOS = 1_000_000L
        const val MAX_DT_NANOS = 100_000_000L
        const val NANOS_PER_SECOND_F = 1_000_000_000f
    }
}

/** A small on-device k-nearest-prototype model suitable for few-shot personalization. */
class PersonalizedGestureClassifier {
    fun classify(
        features: GestureFeatureVector,
        model: PersonalizedGestureModel,
    ): PersonalizedRecognition? {
        if (!model.enabled || !features.isValid) return null
        val candidates = model.normalized().samples
            .groupBy { it.gesture }
            .mapNotNull { (gesture, samples) ->
                val distances = samples
                    .map { distance(features, it.features) }
                    .filter(Float::isFinite)
                    .sorted()
                if (distances.isEmpty()) return@mapNotNull null
                val nearestCount = minOf(2, distances.size)
                val score = distances.take(nearestCount).average().toFloat()
                val radius = withinClassRadius(samples.map { it.features })
                val limit = if (samples.size >= MIN_PERSONALIZED_SAMPLES_PER_GESTURE) {
                    (radius * 2.4f + 0.08f).coerceIn(MATURE_MIN_DISTANCE_LIMIT, MATURE_MAX_DISTANCE_LIMIT)
                } else {
                    SINGLE_SAMPLE_DISTANCE_LIMIT
                }
                Candidate(gesture, score, limit, samples.size)
            }
            .sortedBy { it.distance }
        val best = candidates.firstOrNull() ?: return null
        if (best.distance > best.limit) return null
        val secondDistance = candidates.getOrNull(1)?.distance
        if (secondDistance != null) {
            val absoluteMargin = secondDistance - best.distance
            val relativeMargin = best.distance / secondDistance.coerceAtLeast(0.0001f)
            if (absoluteMargin < MIN_ABSOLUTE_MARGIN && relativeMargin > MAX_RELATIVE_MARGIN) return null
        }
        if (!passesPhysicalGate(best.gesture, features)) return null

        val distanceConfidence = (1f - best.distance / best.limit).coerceIn(0f, 1f)
        val marginConfidence = secondDistance?.let {
            ((it - best.distance) / it.coerceAtLeast(0.0001f)).coerceIn(0f, 1f)
        } ?: 1f
        val maturity = (best.sampleCount / MIN_PERSONALIZED_SAMPLES_PER_GESTURE.toFloat()).coerceIn(0.5f, 1f)
        return PersonalizedRecognition(
            gesture = best.gesture,
            confidence = (0.6f * distanceConfidence + 0.25f * marginConfidence + 0.15f * maturity)
                .coerceIn(0f, 1f),
            distance = best.distance,
            trainedSampleCount = best.sampleCount,
        )
    }

    fun distance(first: GestureFeatureVector, second: GestureFeatureVector): Float {
        if (!first.isValid || !second.isValid) return Float.POSITIVE_INFINITY
        var weightedSquareSum = 0f
        var weightSum = 0f
        for (index in 0 until GESTURE_FEATURE_DIMENSION) {
            val difference = first.values[index] - second.values[index]
            val weight = FEATURE_WEIGHTS[index]
            weightedSquareSum += difference * difference * weight
            weightSum += weight
        }
        return sqrt(weightedSquareSum / weightSum.coerceAtLeast(0.0001f))
    }

    private fun withinClassRadius(features: List<GestureFeatureVector>): Float {
        if (features.size < 2) return SINGLE_SAMPLE_DISTANCE_LIMIT
        val distances = buildList {
            for (firstIndex in 0 until features.lastIndex) {
                for (secondIndex in firstIndex + 1 until features.size) {
                    add(distance(features[firstIndex], features[secondIndex]))
                }
            }
        }.sorted()
        if (distances.isEmpty()) return SINGLE_SAMPLE_DISTANCE_LIMIT
        val middle = distances.size / 2
        return if (distances.size % 2 == 0) {
            (distances[middle - 1] + distances[middle]) / 2f
        } else {
            distances[middle]
        }
    }

    private fun passesPhysicalGate(gesture: GestureType, features: GestureFeatureVector): Boolean {
        val values = features.values
        val peakGyroscopeDps = values[FeatureIndex.PEAK_GYROSCOPE] * 500f
        val minimumAccelerationG = values[FeatureIndex.MINIMUM_ACCELERATION]
        val maximumAccelerationG = values[FeatureIndex.MAXIMUM_ACCELERATION] * 3.5f
        val verticalRotationDegrees = values[FeatureIndex.VERTICAL_ROTATION] * 180f
        val maximumGravityAngleDegrees = values[FeatureIndex.MAXIMUM_GRAVITY_ANGLE] * 180f
        val reversals = values[FeatureIndex.REVERSALS] * 4f
        return when (gesture) {
            GestureType.TILT_LEFT,
            GestureType.TILT_RIGHT,
            -> maximumGravityAngleDegrees >= 10f && peakGyroscopeDps >= 20f

            GestureType.ROTATE_LEFT,
            GestureType.ROTATE_RIGHT,
            -> abs(verticalRotationDegrees) >= 14f && peakGyroscopeDps >= 45f

            GestureType.SHAKE -> reversals >= 1f && peakGyroscopeDps >= 100f
            GestureType.DOUBLE_SHAKE -> reversals >= 2f && peakGyroscopeDps >= 120f
            GestureType.FLIP -> maximumGravityAngleDegrees >= 70f && peakGyroscopeDps >= 45f
            GestureType.THROW_UP -> minimumAccelerationG <= 0.7f && maximumAccelerationG >= 1.35f
        }
    }

    private data class Candidate(
        val gesture: GestureType,
        val distance: Float,
        val limit: Float,
        val sampleCount: Int,
    )

    private object FeatureIndex {
        const val PEAK_GYROSCOPE = 1
        const val MINIMUM_ACCELERATION = 8
        const val MAXIMUM_ACCELERATION = 9
        const val VERTICAL_ROTATION = 10
        const val MAXIMUM_GRAVITY_ANGLE = 13
        const val REVERSALS = 14
    }

    private companion object {
        const val SINGLE_SAMPLE_DISTANCE_LIMIT = 0.2f
        const val MATURE_MIN_DISTANCE_LIMIT = 0.18f
        const val MATURE_MAX_DISTANCE_LIMIT = 0.52f
        const val MIN_ABSOLUTE_MARGIN = 0.035f
        const val MAX_RELATIVE_MARGIN = 0.88f
        val FEATURE_WEIGHTS = FloatArray(GESTURE_FEATURE_DIMENSION) { index ->
            when (index) {
                0 -> 0.65f
                in 1..15 -> 1.25f
                in 16..22 -> 0.9f
                else -> 0.72f
            }
        }
    }
}

private val ZERO_VECTOR = Vector3(0f, 0f, 0f)
private val UNIT_Z_VECTOR = Vector3(0f, 0f, 1f)

private operator fun Vector3.plus(other: Vector3): Vector3 =
    Vector3(x + other.x, y + other.y, z + other.z)

private operator fun Vector3.times(value: Float): Vector3 = Vector3(x * value, y * value, z * value)
