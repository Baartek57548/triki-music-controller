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
        const val DEFAULT_STABILIZATION_NANOS = 150_000_000L // 150 ms szybkiej stabilizacji
        const val DEGREES_PER_PERCENT_BRIGHTNESS = 2.5f // 250 deg = 100% jasności
        const val GYROSCOPE_DEADBAND_DPS = 3.0f
        const val EDGE_ENTER_MAX_Z = 0.45f
        const val EDGE_EXIT_MAX_Z = 0.60f
        const val MINIMUM_PLANE_ACCELERATION_G = 0.65f
        const val MAXIMUM_PLANE_ACCELERATION_G = 1.35f
    }

    private var currentBrightnessPercent = initialBrightnessPercent.coerceIn(0f, 100f)
    private var stabilizationStartNanos: Long? = null
    private var lastTimestampNanos: Long? = null
    private var accumulatedDegrees = 0f
    private var isCurrentlyInEdge = false

    fun getBrightness(): Float = currentBrightnessPercent

    fun setBrightness(value: Float) {
        currentBrightnessPercent = value.coerceIn(0f, 100f)
    }

    fun reset() {
        stabilizationStartNanos = null
        lastTimestampNanos = null
        accumulatedDegrees = 0f
        isCurrentlyInEdge = false
    }

    fun process(sample: FilteredSensorData, isButtonPressed: Boolean = true): BrightnessControlResult {
        val accZ = abs(sample.accelerometerG.z)
        val planeAcc = sqrt(
            sample.accelerometerG.x * sample.accelerometerG.x +
            sample.accelerometerG.y * sample.accelerometerG.y,
        )

        // Histereza pozycji krawędziowej 90°
        val isEdgePose = if (isCurrentlyInEdge) {
            accZ <= EDGE_EXIT_MAX_Z && planeAcc >= MINIMUM_PLANE_ACCELERATION_G * 0.8f
        } else {
            accZ <= EDGE_ENTER_MAX_Z && planeAcc >= MINIMUM_PLANE_ACCELERATION_G && planeAcc <= MAXIMUM_PLANE_ACCELERATION_G
        }

        isCurrentlyInEdge = isEdgePose
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
                statusText = "Postaw kapsel na krawędzi (90°) i przytrzymaj przycisk, aby regulować jasność.",
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

        if (elapsedStabilization < stabilizationNanos && !isButtonPressed) {
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

        if (!isButtonPressed) {
            accumulatedDegrees = 0f
            lastTimestampNanos = timestamp
            return BrightnessControlResult(
                active = true,
                ready = false,
                brightnessPercent = currentBrightnessPercent,
                deltaPercent = 0f,
                stabilizationProgress = 1f,
                statusText = "Przytrzymaj przycisk, aby regulować jasność w pozycji 90°.",
            )
        }

        var deltaPercent = 0f
        val prevTimestamp = lastTimestampNanos
        if (prevTimestamp != null && timestamp > prevTimestamp) {
            val deltaNanos = timestamp - prevTimestamp
            val dtSeconds = if (deltaNanos > 250_000_000L) 0.02f else (deltaNanos / 1_000_000_000f)
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
            statusText = "Jasność: ${currentBrightnessPercent.toInt()}% (Obracaj trzymając przycisk)",
        )
    }
}
