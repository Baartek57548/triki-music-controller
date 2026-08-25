package pl.trikimusic.controller.core.volume

import kotlin.math.abs
import pl.trikimusic.controller.domain.model.FilteredSensorData
import pl.trikimusic.controller.domain.model.MediaAction

data class VolumeControlResult(
    val action: MediaAction? = null,
    val accelerometerWithinTolerance: Boolean,
    val stationary: Boolean,
    val gyroscopeZDps: Float,
)

/**
 * Turns rotation around the local Z axis into discrete Android volume steps.
 *
 * The accelerometer gate intentionally uses only vector magnitude. Comparing individual axes
 * would reject valid in-air control whenever the cap is held at a different angle. A short
 * arming interval and a gyro dead zone prevent sensor noise from changing the volume.
 */
class GyroscopeVolumeController(
    private val configuration: Configuration = Configuration(),
) {
    data class Configuration(
        val minimumStationaryAccelerationG: Float = 0.8f,
        val maximumStationaryAccelerationG: Float = 1.2f,
        val stationaryArmingMillis: Long = 120L,
        val activationGyroscopeDps: Float = 18f,
        val releaseGyroscopeDps: Float = 10f,
        val degreesPerVolumeStep: Float = 15f,
    ) {
        init {
            require(minimumStationaryAccelerationG.isFinite() && minimumStationaryAccelerationG > 0f)
            require(maximumStationaryAccelerationG.isFinite() && maximumStationaryAccelerationG > minimumStationaryAccelerationG)
            require(stationaryArmingMillis in 0L..2_000L)
            require(activationGyroscopeDps.isFinite() && activationGyroscopeDps > 0f)
            require(releaseGyroscopeDps.isFinite() && releaseGyroscopeDps in 0f..activationGyroscopeDps)
            require(degreesPerVolumeStep.isFinite() && degreesPerVolumeStep > 0f)
        }
    }

    private var stationarySinceNanos: Long? = null
    private var previousTimestampNanos: Long? = null
    private var accumulatedRotationDegrees = 0f
    private var activeDirection = 0

    fun reset() {
        stationarySinceNanos = null
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
            stationarySinceNanos = null
            resetRotation()
        }
        val accelerationMagnitude = sample.accelerationMagnitude
        val gyroscopeZ = sample.gyroscopeDps.z
        val sensorsAreFinite = accelerationMagnitude.isFinite() && gyroscopeZ.isFinite()
        val accelerationWithinTolerance = sensorsAreFinite &&
            accelerationMagnitude in configuration.minimumStationaryAccelerationG..configuration.maximumStationaryAccelerationG

        val deltaSeconds = calculateDeltaSeconds(timestampNanos)
        if (!accelerationWithinTolerance) {
            stationarySinceNanos = null
            resetRotation()
            return VolumeControlResult(
                accelerometerWithinTolerance = false,
                stationary = false,
                gyroscopeZDps = gyroscopeZ.takeIf(Float::isFinite) ?: 0f,
            )
        }

        val stationaryStart = stationarySinceNanos ?: timestampNanos.also { stationarySinceNanos = it }
        val stationary = timestampNanos >= stationaryStart &&
            timestampNanos - stationaryStart >= configuration.stationaryArmingMillis * NANOS_PER_MILLISECOND
        if (!stationary) {
            resetRotation()
            return VolumeControlResult(
                accelerometerWithinTolerance = true,
                stationary = false,
                gyroscopeZDps = gyroscopeZ,
            )
        }

        val absoluteGyroscopeZ = abs(gyroscopeZ)
        if (absoluteGyroscopeZ <= configuration.releaseGyroscopeDps) {
            resetRotation()
            return VolumeControlResult(
                accelerometerWithinTolerance = true,
                stationary = true,
                gyroscopeZDps = gyroscopeZ,
            )
        }

        val direction = if (gyroscopeZ > 0f) 1 else -1
        if (activeDirection != 0 && activeDirection != direction) {
            accumulatedRotationDegrees = 0f
        }
        if (activeDirection == 0 && absoluteGyroscopeZ < configuration.activationGyroscopeDps) {
            return VolumeControlResult(
                accelerometerWithinTolerance = true,
                stationary = true,
                gyroscopeZDps = gyroscopeZ,
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
        return VolumeControlResult(
            action = action,
            accelerometerWithinTolerance = true,
            stationary = true,
            gyroscopeZDps = gyroscopeZ,
        )
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
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val NANOS_PER_SECOND = 1_000_000_000f
        const val MAX_SAMPLE_INTERVAL_NANOS = 100_000_000L
        const val MAX_STREAM_GAP_NANOS = 250_000_000L
    }
}
