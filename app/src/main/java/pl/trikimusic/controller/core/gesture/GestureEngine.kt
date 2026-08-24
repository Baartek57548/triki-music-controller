package pl.trikimusic.controller.core.gesture

import kotlin.math.abs
import kotlin.math.max
import pl.trikimusic.controller.domain.model.FilteredSensorData
import pl.trikimusic.controller.domain.model.GestureEvent
import pl.trikimusic.controller.domain.model.GestureThresholds
import pl.trikimusic.controller.domain.model.GestureType

class GestureEngine {
    private val lastEmittedAt = mutableMapOf<GestureType, Long>()
    private var tiltLatched = false
    private var flipSamples = 0
    private var rotationSamples = 0
    private var rotationDirection = 0
    private var freeFallSamples = 0
    private var shakeSamples = 0
    private var lastShakePulseNanos: Long? = null
    private var pendingSingleShake: PendingShake? = null

    fun reset() {
        lastEmittedAt.clear()
        tiltLatched = false
        flipSamples = 0
        rotationSamples = 0
        rotationDirection = 0
        freeFallSamples = 0
        shakeSamples = 0
        lastShakePulseNanos = null
        pendingSingleShake = null
    }

    fun process(sample: FilteredSensorData, thresholds: GestureThresholds): List<GestureEvent> {
        val now = sample.source.timestampNanos
        val events = mutableListOf<GestureEvent>()
        flushPendingShake(now, thresholds)?.let(events::add)

        detectTilt(sample, thresholds)?.let(events::add)
        detectRotation(sample, thresholds)?.let(events::add)
        detectThrow(sample, thresholds)?.let(events::add)
        detectFlip(sample, thresholds)?.let(events::add)
        detectShake(sample, thresholds)?.let(events::add)
        return events
    }

    private fun detectTilt(sample: FilteredSensorData, thresholds: GestureThresholds): GestureEvent? {
        val roll = sample.orientation.roll
        if (abs(roll) < thresholds.tiltReleaseDegrees) tiltLatched = false
        if (tiltLatched || abs(roll) < thresholds.tiltDegrees) return null

        val type = if (roll < 0f) GestureType.TILT_LEFT else GestureType.TILT_RIGHT
        tiltLatched = true
        return emit(type, sample.source.timestampNanos, abs(roll) / 90f, abs(roll), thresholds)
    }

    private fun detectRotation(sample: FilteredSensorData, thresholds: GestureThresholds): GestureEvent? {
        val z = sample.gyroscopeDps.z
        val direction = when {
            z > thresholds.rotationDps -> 1
            z < -thresholds.rotationDps -> -1
            else -> 0
        }
        if (direction == 0) {
            rotationSamples = 0
            rotationDirection = 0
            return null
        }
        if (direction == rotationDirection) rotationSamples++ else rotationSamples = 1
        rotationDirection = direction
        if (rotationSamples < ROTATION_MIN_SAMPLES) return null
        rotationSamples = 0
        val type = if (direction > 0) GestureType.ROTATE_RIGHT else GestureType.ROTATE_LEFT
        return emit(type, sample.source.timestampNanos, abs(z) / 700f, abs(z), thresholds)
    }

    private fun detectThrow(sample: FilteredSensorData, thresholds: GestureThresholds): GestureEvent? {
        val magnitude = sample.accelerationMagnitude
        freeFallSamples = if (magnitude < thresholds.freeFallG) freeFallSamples + 1 else 0
        val upwardImpulse = sample.accelerometerG.z > thresholds.impactG && magnitude > thresholds.impactG
        if (freeFallSamples < FREE_FALL_MIN_SAMPLES && !upwardImpulse) return null
        freeFallSamples = 0
        return emit(GestureType.THROW_UP, sample.source.timestampNanos, 0.9f, magnitude, thresholds)
    }

    private fun detectFlip(sample: FilteredSensorData, thresholds: GestureThresholds): GestureEvent? {
        val upsideDown = sample.accelerometerG.z < FLIP_Z_THRESHOLD && sample.accelerationMagnitude in 0.65f..1.4f
        flipSamples = if (upsideDown) flipSamples + 1 else 0
        if (flipSamples < FLIP_MIN_SAMPLES) return null
        flipSamples = 0
        return emit(GestureType.FLIP, sample.source.timestampNanos, abs(sample.accelerometerG.z), abs(sample.orientation.roll), thresholds)
    }

    private fun detectShake(sample: FilteredSensorData, thresholds: GestureThresholds): GestureEvent? {
        val gyro = sample.gyroscopeMagnitude
        val accelDelta = abs(sample.accelerationMagnitude - 1f)
        val active = gyro > thresholds.shakeDps && accelDelta > SHAKE_ACCEL_DELTA_G
        shakeSamples = if (active) shakeSamples + 1 else max(0, shakeSamples - 1)
        if (shakeSamples < SHAKE_MIN_SAMPLES) return null
        shakeSamples = 0

        val now = sample.source.timestampNanos
        val previousPulse = lastShakePulseNanos
        lastShakePulseNanos = now
        return if (previousPulse != null && now - previousPulse <= DOUBLE_SHAKE_WINDOW_NANOS && pendingSingleShake != null) {
            pendingSingleShake = null
            emit(GestureType.DOUBLE_SHAKE, now, (gyro / 700f).coerceIn(0f, 1f), gyro, thresholds)
        } else {
            pendingSingleShake = PendingShake(now, gyro)
            null
        }
    }

    private fun flushPendingShake(now: Long, thresholds: GestureThresholds): GestureEvent? {
        val pending = pendingSingleShake ?: return null
        if (now - pending.timestampNanos <= DOUBLE_SHAKE_WINDOW_NANOS) return null
        pendingSingleShake = null
        return emit(
            GestureType.SHAKE,
            pending.timestampNanos,
            (pending.magnitude / 700f).coerceIn(0f, 1f),
            pending.magnitude,
            thresholds,
        )
    }

    private fun emit(
        type: GestureType,
        timestampNanos: Long,
        confidence: Float,
        magnitude: Float,
        thresholds: GestureThresholds,
    ): GestureEvent? {
        val last = lastEmittedAt[type]
        val cooldownNanos = thresholds.cooldownMillis * 1_000_000L
        if (last != null && timestampNanos - last < cooldownNanos) return null
        lastEmittedAt[type] = timestampNanos
        return GestureEvent(type, timestampNanos, confidence.coerceIn(0f, 1f), magnitude)
    }

    private data class PendingShake(val timestampNanos: Long, val magnitude: Float)

    private companion object {
        const val ROTATION_MIN_SAMPLES = 3
        const val FREE_FALL_MIN_SAMPLES = 2
        const val FLIP_MIN_SAMPLES = 8
        const val FLIP_Z_THRESHOLD = -0.72f
        const val SHAKE_MIN_SAMPLES = 4
        const val SHAKE_ACCEL_DELTA_G = 0.18f
        const val DOUBLE_SHAKE_WINDOW_NANOS = 480_000_000L
    }
}
