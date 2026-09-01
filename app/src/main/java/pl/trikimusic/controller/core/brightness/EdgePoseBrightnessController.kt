package pl.trikimusic.controller.core.brightness

import kotlin.math.abs
import kotlin.math.sqrt
import pl.trikimusic.controller.domain.model.FilteredSensorData

data class BrightnessControlResult(
    val active: Boolean,
    val ready: Boolean,
    val brightnessPercent: Float,
    val deltaPercent: Float,
    val stabilizationProgress: Float,
    val statusText: String,
)

class EdgePoseBrightnessController(
    initialBrightnessPercent: Float = 50f,
    private val stabilizationNanos: Long = DEFAULT_STABILIZATION_NANOS,
) {
    companion object {
        const val DEFAULT_STABILIZATION_NANOS = 400_000_000L // 400 ms
        const val DEGREES_PER_PERCENT_BRIGHTNESS = 3.6f // 360 deg = 100%
        const val GYROSCOPE_DEADBAND_DPS = 4.0f
        const val MAXIMUM_Z_AXIS_DEVIATION_G = 0.40f
        const val MINIMUM_PLANE_ACCELERATION_G = 0.75f
        const val MAXIMUM_PLANE_ACCELERATION_G = 1.25f
    }

    private var currentBrightnessPercent = initialBrightnessPercent.coerceIn(0f, 100f)
    private var stabilizationStartNanos: Long? = null
    private var lastTimestampNanos: Long? = null
    private var accumulatedDegrees = 0f

    fun getBrightness(): Float = currentBrightnessPercent

    fun setBrightness(value: Float) {
        currentBrightnessPercent = value.coerceIn(0f, 100f)
    }

    fun reset() {
        stabilizationStartNanos = null
        lastTimestampNanos = null
        accumulatedDegrees = 0f
    }

    fun process(sample: FilteredSensorData): BrightnessControlResult {
        val accZ = abs(sample.accelerometerG.z)
        val planeAcc = sqrt(
            sample.accelerometerG.x * sample.accelerometerG.x +
            sample.accelerometerG.y * sample.accelerometerG.y
        )

        val isEdgePose = accZ <= MAXIMUM_Z_AXIS_DEVIATION_G &&
            planeAcc >= MINIMUM_PLANE_ACCELERATION_G &&
            planeAcc <= MAXIMUM_PLANE_ACCELERATION_G &&
            abs(sample.accelerationMagnitude - 1f) <= 0.25f

        val timestamp = sample.source.timestampNanos

        if (!isEdgePose) {
            stabilizationStartNanos = null
            lastTimestampNanos = timestamp
            accumulatedDegrees = 0f
            return BrightnessControlResult(
                active = false,
                ready = false,
                brightnessPercent = currentBrightnessPercent,
                deltaPercent = 0f,
                stabilizationProgress = 0f,
                statusText = "Postaw kapsel na krawędzi (90°), aby regulować jasność.",
            )
        }

        if (stabilizationStartNanos == null) {
            stabilizationStartNanos = timestamp
            lastTimestampNanos = timestamp
            return BrightnessControlResult(
                active = true,
                ready = false,
                brightnessPercent = currentBrightnessPercent,
                deltaPercent = 0f,
                stabilizationProgress = 0f,
                statusText = "Stabilizacja pozycji 90°…",
            )
        }

        val elapsedStabilization = timestamp - stabilizationStartNanos!!
        val stabilizationProgress = (elapsedStabilization.toFloat() / stabilizationNanos).coerceIn(0f, 1f)

        if (elapsedStabilization < stabilizationNanos) {
            lastTimestampNanos = timestamp
            return BrightnessControlResult(
                active = true,
                ready = false,
                brightnessPercent = currentBrightnessPercent,
                deltaPercent = 0f,
                stabilizationProgress = stabilizationProgress,
                statusText = "Stabilizacja: ${(stabilizationProgress * 100).toInt()}%",
            )
        }

        var deltaPercent = 0f
        val prevTimestamp = lastTimestampNanos
        if (prevTimestamp != null && timestamp > prevTimestamp) {
            val dtSeconds = (timestamp - prevTimestamp) / 1_000_000_000f
            val gyroZ = sample.gyroscopeDps.z

            if (abs(gyroZ) >= GYROSCOPE_DEADBAND_DPS) {
                val deltaDegrees = gyroZ * dtSeconds
                accumulatedDegrees += deltaDegrees

                if (abs(accumulatedDegrees) >= DEGREES_PER_PERCENT_BRIGHTNESS) {
                    val steps = (accumulatedDegrees / DEGREES_PER_PERCENT_BRIGHTNESS).toInt().toFloat()
                    deltaPercent = steps
                    accumulatedDegrees -= steps * DEGREES_PER_PERCENT_BRIGHTNESS
                    currentBrightnessPercent = (currentBrightnessPercent + deltaPercent).coerceIn(0f, 100f)
                }
            }
        }

        lastTimestampNanos = timestamp

        return BrightnessControlResult(
            active = true,
            ready = true,
            brightnessPercent = currentBrightnessPercent,
            deltaPercent = deltaPercent,
            stabilizationProgress = 1f,
            statusText = "Jasność: ${currentBrightnessPercent.toInt()}% (Obracaj kapsel na krawędzi)",
        )
    }
}
