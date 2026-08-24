package pl.trikimusic.controller.core.gesture

import kotlin.math.max
import kotlin.math.min
import pl.trikimusic.controller.domain.model.FilteredSensorData
import pl.trikimusic.controller.domain.model.GestureEvent
import pl.trikimusic.controller.domain.model.GestureThresholds
import pl.trikimusic.controller.domain.model.PersonalizedGestureModel

data class GestureRecordingResult(
    val events: List<GestureEvent>,
    val sampleCount: Int,
    val durationMillis: Long,
    val peakGyroscopeDps: Float,
    val minimumAccelerationG: Float,
    val maximumAccelerationG: Float,
) {
    val strongestEvent: GestureEvent?
        get() = events.maxByOrNull { it.confidence }
}

/** Analyzes the exact sample range selected by the user's Start and Stop actions. */
class GestureRecordingAnalyzer {
    fun analyze(
        samples: List<FilteredSensorData>,
        thresholds: GestureThresholds,
        personalizedModel: PersonalizedGestureModel = PersonalizedGestureModel(),
    ): GestureRecordingResult {
        if (samples.isEmpty()) {
            return GestureRecordingResult(emptyList(), 0, 0L, 0f, 0f, 0f)
        }

        val ordered = samples
            .asSequence()
            .filter { it.source.timestampNanos >= 0L }
            .distinctBy { it.source.timestampNanos }
            .sortedBy { it.source.timestampNanos }
            .toList()
        if (ordered.isEmpty()) {
            return GestureRecordingResult(emptyList(), 0, 0L, 0f, 0f, 0f)
        }

        val engine = GestureEngine()
        val events = buildList {
            ordered.forEach { sample -> addAll(engine.process(sample, thresholds, personalizedModel)) }
            engine.finishRecording(thresholds, personalizedModel)?.let(::add)
        }

        var peakGyroscope = 0f
        var minimumAcceleration = Float.POSITIVE_INFINITY
        var maximumAcceleration = 0f
        ordered.forEach { sample ->
            peakGyroscope = max(peakGyroscope, sample.gyroscopeMagnitude)
            minimumAcceleration = min(minimumAcceleration, sample.accelerationMagnitude)
            maximumAcceleration = max(maximumAcceleration, sample.accelerationMagnitude)
        }
        val durationMillis = if (ordered.size >= 2) {
            ((ordered.last().source.timestampNanos - ordered.first().source.timestampNanos) / 1_000_000L)
                .coerceAtLeast(0L)
        } else {
            0L
        }

        return GestureRecordingResult(
            events = events,
            sampleCount = ordered.size,
            durationMillis = durationMillis,
            peakGyroscopeDps = peakGyroscope,
            minimumAccelerationG = minimumAcceleration.takeIf { it.isFinite() } ?: 0f,
            maximumAccelerationG = maximumAcceleration,
        )
    }
}
