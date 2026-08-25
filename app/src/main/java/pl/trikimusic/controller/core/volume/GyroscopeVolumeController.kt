package pl.trikimusic.controller.core.volume

import kotlin.math.abs
import kotlin.math.acos
import pl.trikimusic.controller.domain.model.FilteredSensorData
import pl.trikimusic.controller.domain.model.MediaAction

data class VolumeControlResult(
    val action: MediaAction? = null,
    val sensorValid: Boolean,
    val withinTiltRange: Boolean,
    val tiltStable: Boolean,
    val stabilizationProgress: Float,
    val active: Boolean,
    val tiltDegrees: Float,
    val gyroscopeZDps: Float,
)

/**
 * Turns rotation around the local Z axis into discrete Android volume steps.
 *
 * The controller becomes active after the cap remains top-side up within 0–25 degrees for two
 * continuous seconds. This stabilization concerns only tilt: there is deliberately no stationary,
 * acceleration-magnitude or off-axis gate, so the user can hold and move the cap in the air.
 */
class GyroscopeVolumeController(
    private val configuration: Configuration = Configuration(),
) {
    data class Configuration(
        val maximumTiltDegrees: Float = 25f,
        val tiltStabilizationMillis: Long = 2_000L,
        val activationGyroscopeDps: Float = 18f,
        val releaseGyroscopeDps: Float = 10f,
        val degreesPerVolumeStep: Float = 15f,
        val gyroscopeSmoothingAlpha: Float = 0.22f,
        val minimumStepIntervalMillis: Long = 100L,
    ) {
        init {
            require(maximumTiltDegrees.isFinite() && maximumTiltDegrees in 0f..90f)
            require(tiltStabilizationMillis in 0L..10_000L)
            require(activationGyroscopeDps.isFinite() && activationGyroscopeDps > 0f)
            require(releaseGyroscopeDps.isFinite() && releaseGyroscopeDps in 0f..activationGyroscopeDps)
            require(degreesPerVolumeStep.isFinite() && degreesPerVolumeStep > 0f)
            require(gyroscopeSmoothingAlpha.isFinite() && gyroscopeSmoothingAlpha in 0.01f..1f)
            require(minimumStepIntervalMillis in 0L..1_000L)
        }
    }

    private var previousTimestampNanos: Long? = null
    private var tiltRangeSinceNanos: Long? = null
    private var accumulatedRotationDegrees = 0f
    private var activeDirection = 0
    private var smoothedGyroscopeZ: Float? = null
    private var lastVolumeStepNanos: Long? = null
    private var tiltStable = false

    fun reset() {
        previousTimestampNanos = null
        resetStabilization()
    }

    fun process(sample: FilteredSensorData): VolumeControlResult {
        val timestampNanos = sample.source.timestampNanos
        val previousTimestamp = previousTimestampNanos
        if (
            previousTimestamp != null &&
            (timestampNanos <= previousTimestamp || timestampNanos - previousTimestamp > MAX_STREAM_GAP_NANOS)
        ) {
            resetStabilization()
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
            resetStabilization()
            return result(
                sensorValid = sensorValid,
                withinTiltRange = false,
                stabilizationProgress = 0f,
                tiltDegrees = tiltDegrees,
                gyroscopeZ = gyroscopeZ,
            )
        }

        val stabilizationStart = tiltRangeSinceNanos ?: timestampNanos.also { tiltRangeSinceNanos = it }
        val requiredStabilizationNanos = configuration.tiltStabilizationMillis * NANOS_PER_MILLISECOND
        val stabilizationElapsedNanos = (timestampNanos - stabilizationStart).coerceAtLeast(0L)
        val stabilizationProgress = if (requiredStabilizationNanos == 0L) {
            1f
        } else {
            (stabilizationElapsedNanos.toDouble() / requiredStabilizationNanos).toFloat().coerceIn(0f, 1f)
        }
        val wasTiltStable = tiltStable
        tiltStable = stabilizationElapsedNanos >= requiredStabilizationNanos
        val filteredGyroscopeZ = smoothGyroscopeZ(gyroscopeZ)
        if (!tiltStable || !wasTiltStable) {
            resetRotation(preserveSmoothing = true)
            return result(
                sensorValid = true,
                withinTiltRange = true,
                stabilizationProgress = stabilizationProgress,
                tiltDegrees = tiltDegrees,
                gyroscopeZ = filteredGyroscopeZ,
            )
        }

        val absoluteGyroscopeZ = abs(filteredGyroscopeZ)
        if (absoluteGyroscopeZ <= configuration.releaseGyroscopeDps) {
            resetRotation(preserveSmoothing = true)
            return result(
                sensorValid = true,
                withinTiltRange = true,
                stabilizationProgress = 1f,
                tiltDegrees = tiltDegrees,
                gyroscopeZ = filteredGyroscopeZ,
            )
        }

        val direction = if (filteredGyroscopeZ > 0f) 1 else -1
        if (activeDirection != 0 && activeDirection != direction) {
            accumulatedRotationDegrees = 0f
        }
        if (activeDirection == 0 && absoluteGyroscopeZ < configuration.activationGyroscopeDps) {
            return result(
                sensorValid = true,
                withinTiltRange = true,
                stabilizationProgress = 1f,
                tiltDegrees = tiltDegrees,
                gyroscopeZ = filteredGyroscopeZ,
            )
        }

        activeDirection = direction
        accumulatedRotationDegrees = (accumulatedRotationDegrees + filteredGyroscopeZ * deltaSeconds).coerceIn(
            -configuration.degreesPerVolumeStep * MAX_PENDING_VOLUME_STEPS,
            configuration.degreesPerVolumeStep * MAX_PENDING_VOLUME_STEPS,
        )
        val minimumStepIntervalNanos = configuration.minimumStepIntervalMillis * NANOS_PER_MILLISECOND
        val mayEmitStep = lastVolumeStepNanos?.let { timestampNanos - it >= minimumStepIntervalNanos } ?: true
        val action = when {
            mayEmitStep && accumulatedRotationDegrees >= configuration.degreesPerVolumeStep -> {
                accumulatedRotationDegrees -= configuration.degreesPerVolumeStep
                lastVolumeStepNanos = timestampNanos
                MediaAction.VOLUME_UP
            }
            mayEmitStep && accumulatedRotationDegrees <= -configuration.degreesPerVolumeStep -> {
                accumulatedRotationDegrees += configuration.degreesPerVolumeStep
                lastVolumeStepNanos = timestampNanos
                MediaAction.VOLUME_DOWN
            }
            else -> null
        }
        return result(
            action = action,
            sensorValid = true,
            withinTiltRange = true,
            stabilizationProgress = 1f,
            tiltDegrees = tiltDegrees,
            gyroscopeZ = filteredGyroscopeZ,
        )
    }

    private fun result(
        action: MediaAction? = null,
        sensorValid: Boolean,
        withinTiltRange: Boolean,
        stabilizationProgress: Float,
        tiltDegrees: Float,
        gyroscopeZ: Float,
    ) = VolumeControlResult(
        action = action,
        sensorValid = sensorValid,
        withinTiltRange = withinTiltRange,
        tiltStable = tiltStable,
        stabilizationProgress = stabilizationProgress,
        active = sensorValid && withinTiltRange && tiltStable,
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

    private fun smoothGyroscopeZ(value: Float): Float {
        val previous = smoothedGyroscopeZ
        val smoothed = if (previous == null) value else {
            previous + configuration.gyroscopeSmoothingAlpha * (value - previous)
        }
        smoothedGyroscopeZ = smoothed
        return smoothed
    }

    private fun resetRotation(preserveSmoothing: Boolean = false) {
        accumulatedRotationDegrees = 0f
        activeDirection = 0
        lastVolumeStepNanos = null
        if (!preserveSmoothing) smoothedGyroscopeZ = null
    }

    private fun resetStabilization() {
        tiltRangeSinceNanos = null
        tiltStable = false
        resetRotation()
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val NANOS_PER_SECOND = 1_000_000_000f
        const val MAX_SAMPLE_INTERVAL_NANOS = 100_000_000L
        const val MAX_STREAM_GAP_NANOS = 250_000_000L
        const val MIN_VECTOR_MAGNITUDE = 0.001f
        const val TILT_COMPARISON_EPSILON_DEGREES = 0.001f
        const val MAX_PENDING_VOLUME_STEPS = 2f
    }
}
