package pl.trikimusic.controller.core.volume

import kotlin.math.abs
import kotlin.math.acos
import pl.trikimusic.controller.domain.model.FilteredSensorData
import pl.trikimusic.controller.domain.model.MediaAction

data class VolumeControlResult(
    val action: MediaAction? = null,
    val sensorValid: Boolean,
    val withinTiltRange: Boolean,
    val active: Boolean,
    val tiltDegrees: Float,
    val gyroscopeZDps: Float,
)

/**
 * Turns rotation around the local Z axis into discrete Android volume steps.
 *
 * The controller is active immediately while the cap remains top-side up within 0–25 degrees
 * from level. There is deliberately no stationary, acceleration-magnitude or off-axis gate, so
 * the user can control volume while holding the cap in the air. Leaving the permitted tilt range
 * clears accumulated rotation before another volume step can be produced.
 */
class GyroscopeVolumeController(
    private val configuration: Configuration = Configuration(),
) {
    data class Configuration(
        val maximumTiltDegrees: Float = 25f,
        val activationGyroscopeDps: Float = 18f,
        val releaseGyroscopeDps: Float = 10f,
        val degreesPerVolumeStep: Float = 15f,
    ) {
        init {
            require(maximumTiltDegrees.isFinite() && maximumTiltDegrees in 0f..90f)
            require(activationGyroscopeDps.isFinite() && activationGyroscopeDps > 0f)
            require(releaseGyroscopeDps.isFinite() && releaseGyroscopeDps in 0f..activationGyroscopeDps)
            require(degreesPerVolumeStep.isFinite() && degreesPerVolumeStep > 0f)
        }
    }

    private var previousTimestampNanos: Long? = null
    private var accumulatedRotationDegrees = 0f
    private var activeDirection = 0

    fun reset() {
        previousTimestampNanos = null
        resetRotation()
    }

    fun process(sample: FilteredSensorData): VolumeControlResult {
        val timestampNanos = sample.source.timestampNanos
        val previousTimestamp = previousTimestampNanos
        if (
            previousTimestamp != null &&
            (timestampNanos <= previousTimestamp || timestampNanos - previousTimestamp > MAX_STREAM_GAP_NANOS)
        ) {
            resetRotation()
            previousTimestampNanos = null
        }

        val accelerationMagnitude = sample.accelerationMagnitude
        val gyroscopeZ = sample.gyroscopeDps.z
        val accelerometerValid = sample.accelerometerG.x.isFinite() &&
            sample.accelerometerG.y.isFinite() &&
            sample.accelerometerG.z.isFinite() &&
            accelerationMagnitude.isFinite() &&
            accelerationMagnitude >= MIN_VECTOR_MAGNITUDE
        val gyroscopeValid = gyroscopeZ.isFinite()
        val sensorValid = accelerometerValid && gyroscopeValid
        val tiltDegrees = calculateTiltDegrees(sample.accelerometerG.z, accelerationMagnitude)
        val withinTiltRange = sensorValid &&
            tiltDegrees <= configuration.maximumTiltDegrees + TILT_COMPARISON_EPSILON_DEGREES
        val deltaSeconds = calculateDeltaSeconds(timestampNanos)

        if (!withinTiltRange) {
            resetRotation()
            return result(
                sensorValid = sensorValid,
                withinTiltRange = false,
                tiltDegrees = tiltDegrees,
                gyroscopeZ = gyroscopeZ,
            )
        }

        val absoluteGyroscopeZ = abs(gyroscopeZ)
        if (absoluteGyroscopeZ <= configuration.releaseGyroscopeDps) {
            resetRotation()
            return result(
                sensorValid = true,
                withinTiltRange = true,
                tiltDegrees = tiltDegrees,
                gyroscopeZ = gyroscopeZ,
            )
        }

        val direction = if (gyroscopeZ > 0f) 1 else -1
        if (activeDirection != 0 && activeDirection != direction) {
            accumulatedRotationDegrees = 0f
        }
        if (activeDirection == 0 && absoluteGyroscopeZ < configuration.activationGyroscopeDps) {
            return result(
                sensorValid = true,
                withinTiltRange = true,
                tiltDegrees = tiltDegrees,
                gyroscopeZ = gyroscopeZ,
            )
        }

        activeDirection = direction
        accumulatedRotationDegrees += gyroscopeZ * deltaSeconds
        val action = when {
            accumulatedRotationDegrees >= configuration.degreesPerVolumeStep -> {
                accumulatedRotationDegrees -= configuration.degreesPerVolumeStep
                MediaAction.VOLUME_UP
            }
            accumulatedRotationDegrees <= -configuration.degreesPerVolumeStep -> {
                accumulatedRotationDegrees += configuration.degreesPerVolumeStep
                MediaAction.VOLUME_DOWN
            }
            else -> null
        }
        return result(
            action = action,
            sensorValid = true,
            withinTiltRange = true,
            tiltDegrees = tiltDegrees,
            gyroscopeZ = gyroscopeZ,
        )
    }

    private fun result(
        action: MediaAction? = null,
        sensorValid: Boolean,
        withinTiltRange: Boolean,
        tiltDegrees: Float,
        gyroscopeZ: Float,
    ) = VolumeControlResult(
        action = action,
        sensorValid = sensorValid,
        withinTiltRange = withinTiltRange,
        active = sensorValid && withinTiltRange,
        tiltDegrees = tiltDegrees.takeIf(Float::isFinite) ?: 180f,
        gyroscopeZDps = gyroscopeZ.takeIf(Float::isFinite) ?: 0f,
    )

    private fun calculateTiltDegrees(accelerometerZ: Float, accelerationMagnitude: Float): Float {
        if (!accelerometerZ.isFinite() || !accelerationMagnitude.isFinite() || accelerationMagnitude < MIN_VECTOR_MAGNITUDE) {
            return 180f
        }
        // Hardware captures show gravity near (0, 0, -1 g) when the cap is face-up.
        val faceUpComponent = (-accelerometerZ / accelerationMagnitude).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(faceUpComponent).toDouble()).toFloat()
    }

    private fun calculateDeltaSeconds(timestampNanos: Long): Float {
        val previous = previousTimestampNanos
        previousTimestampNanos = timestampNanos
        if (previous == null || timestampNanos <= previous) return 0f
        return (timestampNanos - previous).coerceAtMost(MAX_SAMPLE_INTERVAL_NANOS) / NANOS_PER_SECOND
    }

    private fun resetRotation() {
        accumulatedRotationDegrees = 0f
        activeDirection = 0
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000f
        const val MAX_SAMPLE_INTERVAL_NANOS = 100_000_000L
        const val MAX_STREAM_GAP_NANOS = 250_000_000L
        const val MIN_VECTOR_MAGNITUDE = 0.001f
        const val TILT_COMPARISON_EPSILON_DEGREES = 0.001f
    }
}
