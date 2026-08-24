package pl.trikimusic.controller.domain.model

import kotlinx.serialization.Serializable

const val GESTURE_FEATURE_SCHEMA_VERSION = 1
const val CURRENT_GESTURE_LEARNING_VERSION = 1
const val GESTURE_FEATURE_DIMENSION = 39
const val MIN_PERSONALIZED_SAMPLES_PER_GESTURE = 2
const val MAX_PERSONALIZED_SAMPLES_PER_GESTURE = 5

@Serializable
data class GestureFeatureVector(
    val schemaVersion: Int = GESTURE_FEATURE_SCHEMA_VERSION,
    val values: List<Float> = emptyList(),
) {
    val isValid: Boolean
        get() = schemaVersion == GESTURE_FEATURE_SCHEMA_VERSION &&
            values.size == GESTURE_FEATURE_DIMENSION &&
            values.all(Float::isFinite)
}

@Serializable
data class LearnedGestureSample(
    val gesture: GestureType,
    val features: GestureFeatureVector,
    val capturedAtMillis: Long,
)

@Serializable
data class PersonalizedGestureModel(
    val enabled: Boolean = true,
    val samples: List<LearnedGestureSample> = emptyList(),
) {
    fun samplesFor(gesture: GestureType): List<LearnedGestureSample> = samples.filter { it.gesture == gesture }

    fun sampleCountFor(gesture: GestureType): Int = samples.count { it.gesture == gesture }

    fun isTrained(gesture: GestureType): Boolean =
        sampleCountFor(gesture) >= MIN_PERSONALIZED_SAMPLES_PER_GESTURE

    fun withSample(sample: LearnedGestureSample): PersonalizedGestureModel {
        require(sample.features.isValid) { "Próbka modelu gestów ma nieprawidłowy format." }
        val otherGestures = samples.filterNot { it.gesture == sample.gesture }
        val retainedForGesture = samplesFor(sample.gesture)
            .plus(sample)
            .sortedBy { it.capturedAtMillis }
            .takeLast(MAX_PERSONALIZED_SAMPLES_PER_GESTURE)
        return copy(samples = (otherGestures + retainedForGesture).sortedBy { it.capturedAtMillis })
    }

    fun withoutGesture(gesture: GestureType): PersonalizedGestureModel =
        copy(samples = samples.filterNot { it.gesture == gesture })

    fun normalized(): PersonalizedGestureModel {
        val valid = samples
            .filter { it.features.isValid && it.capturedAtMillis >= 0L }
            .groupBy { it.gesture }
            .values
            .flatMap { gestureSamples ->
                gestureSamples.sortedBy { it.capturedAtMillis }.takeLast(MAX_PERSONALIZED_SAMPLES_PER_GESTURE)
            }
            .sortedBy { it.capturedAtMillis }
        return copy(samples = valid)
    }
}
