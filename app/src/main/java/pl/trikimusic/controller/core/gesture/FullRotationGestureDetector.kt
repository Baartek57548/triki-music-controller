package pl.trikimusic.controller.core.gesture

import kotlin.math.abs
import kotlin.math.cos
import pl.trikimusic.controller.domain.model.FilteredSensorData
import pl.trikimusic.controller.domain.model.MediaAction
import pl.trikimusic.controller.domain.model.Vector3

/**
 * Recognizes one deliberate 270° rotation around the capsule's local Z axis while it is inverted.
 *
 * The detector intentionally follows the same signed gyro integration model as the volume
 * controller. A positive Z rotation is a right turn, a negative Z rotation is a left turn. The
 * inverted pose and acceleration magnitude gate keep track navigation separate from volume use.
 */
class FullRotationGestureDetector(
    private val configuration: Configuration = Configuration(),
) {
    data class Configuration(
        val stabilizationMillis: Long = 500L,
        // The two low-pass stages lose roughly 20–25° at the edges of a deliberate turn. A 245°
        // integrated threshold therefore makes the physical gesture finish around the requested 270°.
        val requiredRotationDegrees: Float = FILTERED_ROTATION_TRIGGER_DEGREES,
        val maximumRotationDegrees: Float = 420f,
        val maximumRotationMillis: Long = 5_000L,
        val maximumFaceDownTiltDegrees: Float = 25f,
        val maximumAccelerationDeviationG: Float = 0.20f,
        val activationGyroscopeDps: Float = 22f,
        val releaseGyroscopeDps: Float = 12f,
        val gyroscopeSmoothingAlpha: Float = 0.16f,
        val maximumSampleGapMillis: Long = 250L,
        val armingMaximumAngularRateDps: Float = 45f,
        val rearmQuietMillis: Long = 180L,
    ) {
        init {
            require(stabilizationMillis in 200L..3_000L)
            require(requiredRotationDegrees.isFinite() && requiredRotationDegrees in 180f..360f)
            require(maximumRotationDegrees.isFinite() && maximumRotationDegrees > requiredRotationDegrees)
            require(maximumRotationMillis in 1_000L..10_000L)
            require(maximumFaceDownTiltDegrees.isFinite() && maximumFaceDownTiltDegrees in 5f..45f)
            require(maximumAccelerationDeviationG.isFinite() && maximumAccelerationDeviationG in 0.05f..0.50f)
            require(activationGyroscopeDps.isFinite() && activationGyroscopeDps > 0f)
            require(releaseGyroscopeDps.isFinite() && releaseGyroscopeDps in 0f..activationGyroscopeDps)
            require(gyroscopeSmoothingAlpha.isFinite() && gyroscopeSmoothingAlpha in 0.01f..1f)
            require(maximumSampleGapMillis in 100L..1_000L)
            require(armingMaximumAngularRateDps.isFinite() && armingMaximumAngularRateDps in 10f..120f)
            require(rearmQuietMillis in 80L..500L)
        }
    }

    private var previousTimestampNanos: Long? = null
    private var stabilizationSinceNanos: Long? = null
    private var smoothedGyroscopeZ: Float? = null
    private var accumulatedRotationDegrees = 0f
    private var motionStartedNanos: Long? = null
    private var activeDirection = 0
    private var awaitingQuietRearm = false
    private var quietRearmSinceNanos: Long? = null
    private var triggered = false
    private var faceDown = false

    fun reset() {
        previousTimestampNanos = null
        stabilizationSinceNanos = null
        smoothedGyroscopeZ = null
        accumulatedRotationDegrees = 0f
        motionStartedNanos = null
        activeDirection = 0
        awaitingQuietRearm = false
        quietRearmSinceNanos = null
        triggered = false
        faceDown = false
    }

    fun process(sample: FilteredSensorData): FullRotationGestureResult {
        val timestampNanos = sample.source.timestampNanos
        val acceleration = sample.accelerometerG
        if (!isUsableAcceleration(acceleration)) {
            restartStabilization(timestampNanos, null)
            return result(HoldGesturePhase.HOLDING, 0f)
        }
        faceDown = isFaceDown(acceleration)

        val previousTimestamp = previousTimestampNanos
        if (
            previousTimestamp != null &&
            (timestampNanos <= previousTimestamp ||
                timestampNanos - previousTimestamp > configuration.maximumSampleGapMillis * NANOS_PER_MILLISECOND)
        ) {
            restartStabilization(timestampNanos, acceleration)
            return result(HoldGesturePhase.HOLDING, 0f)
        }
        previousTimestampNanos = timestampNanos

        val stabilizationStart = stabilizationSinceNanos
        if (stabilizationStart == null) {
            stabilizationSinceNanos = timestampNanos
            return result(HoldGesturePhase.HOLDING, 0f)
        }

        if (!faceDown || abs(sample.accelerationMagnitude - STANDARD_GRAVITY_G) > configuration.maximumAccelerationDeviationG) {
            restartStabilization(timestampNanos, acceleration)
            return result(HoldGesturePhase.HOLDING, 0f)
        }

        val stabilizationElapsedNanos = (timestampNanos - stabilizationStart).coerceAtLeast(0L)
        val requiredStabilizationNanos = configuration.stabilizationMillis * NANOS_PER_MILLISECOND
        val stabilizationProgress = if (requiredStabilizationNanos == 0L) {
            1f
        } else {
            (stabilizationElapsedNanos.toDouble() / requiredStabilizationNanos).toFloat().coerceIn(0f, 1f)
        }
        if (stabilizationElapsedNanos < requiredStabilizationNanos) {
            resetRotation()
            return result(HoldGesturePhase.HOLDING, stabilizationProgress)
        }

        val gyroscopeZ = smoothGyroscopeZ(sample.gyroscopeDps.z)
        if (awaitingQuietRearm) {
            if (
                abs(gyroscopeZ) <= configuration.releaseGyroscopeDps &&
                sample.gyroscopeMagnitude <= configuration.armingMaximumAngularRateDps
            ) {
                val quietSince = quietRearmSinceNanos ?: timestampNanos.also { quietRearmSinceNanos = it }
                if (timestampNanos - quietSince >= configuration.rearmQuietMillis * NANOS_PER_MILLISECOND) {
                    restartStabilization(timestampNanos, acceleration)
                }
            } else {
                quietRearmSinceNanos = null
            }
            return result(HoldGesturePhase.REARMING, 1f, gyroscopeZ)
        }

        if (!gyroscopeZ.isFinite()) {
            invalidateMotion(timestampNanos, acceleration)
            return result(HoldGesturePhase.REARMING, 1f)
        }
        if (abs(gyroscopeZ) <= configuration.releaseGyroscopeDps) {
            resetRotation(preserveSmoothing = true)
            return result(HoldGesturePhase.READY, 1f, gyroscopeZ)
        }

        val direction = if (gyroscopeZ > 0f) 1 else -1
        if (activeDirection != 0 && activeDirection != direction) {
            resetRotation(preserveSmoothing = true)
        }
        if (activeDirection == 0 && abs(gyroscopeZ) < configuration.activationGyroscopeDps) {
            return result(HoldGesturePhase.READY, 1f, gyroscopeZ)
        }
        activeDirection = direction
        if (motionStartedNanos == null) motionStartedNanos = timestampNanos
        val motionElapsedNanos = timestampNanos - (motionStartedNanos ?: timestampNanos)
        if (motionElapsedNanos > configuration.maximumRotationMillis * NANOS_PER_MILLISECOND) {
            invalidateMotion(timestampNanos, acceleration)
            return result(HoldGesturePhase.REARMING, 1f, gyroscopeZ)
        }

        val deltaSeconds = if (previousTimestamp == null || timestampNanos <= previousTimestamp) {
            0f
        } else {
            (timestampNanos - previousTimestamp).coerceAtMost(MAX_SAMPLE_INTERVAL_NANOS) / NANOS_PER_SECOND
        }
        val nextRotation = accumulatedRotationDegrees + gyroscopeZ * deltaSeconds
        if (abs(nextRotation) > configuration.maximumRotationDegrees) {
            invalidateMotion(timestampNanos, acceleration)
            return result(HoldGesturePhase.REARMING, 1f, gyroscopeZ)
        }
        accumulatedRotationDegrees = nextRotation

        val progress = (abs(accumulatedRotationDegrees) / configuration.requiredRotationDegrees).coerceIn(0f, 1f)
        if (abs(accumulatedRotationDegrees) >= configuration.requiredRotationDegrees) {
            triggered = true
            awaitingQuietRearm = true
            quietRearmSinceNanos = null
            return result(
                phase = HoldGesturePhase.TRIGGERED,
                stabilizationProgress = 1f,
                gyroscopeZ = gyroscopeZ,
                direction = if (accumulatedRotationDegrees > 0f) RotationGestureDirection.RIGHT else RotationGestureDirection.LEFT,
            )
        }

        return result(
            phase = HoldGesturePhase.TRACKING,
            stabilizationProgress = progress,
            gyroscopeZ = gyroscopeZ,
        )
    }

    private fun result(
        phase: HoldGesturePhase,
        stabilizationProgress: Float,
        gyroscopeZ: Float = smoothedGyroscopeZ ?: 0f,
        direction: RotationGestureDirection? = null,
    ) = FullRotationGestureResult(
        triggered = triggered && phase == HoldGesturePhase.TRIGGERED,
        direction = direction ?: activeDirection.takeIf { it != 0 }?.let {
            if (it > 0) RotationGestureDirection.RIGHT else RotationGestureDirection.LEFT
        },
        phase = phase,
        stabilizationProgress = stabilizationProgress,
        faceDown = faceDown,
        estimatedRotationDegrees = accumulatedRotationDegrees,
        gyroscopeZDps = gyroscopeZ.takeIf(Float::isFinite) ?: 0f,
    )

    private fun resetRotation(preserveSmoothing: Boolean = false) {
        accumulatedRotationDegrees = 0f
        motionStartedNanos = null
        activeDirection = 0
        if (!preserveSmoothing) smoothedGyroscopeZ = null
    }

    private fun restartStabilization(timestampNanos: Long, acceleration: Vector3?) {
        previousTimestampNanos = timestampNanos
        stabilizationSinceNanos = timestampNanos
        faceDown = acceleration?.let(::isFaceDown) ?: false
        awaitingQuietRearm = false
        quietRearmSinceNanos = null
        triggered = false
        resetRotation()
    }

    private fun invalidateMotion(timestampNanos: Long, acceleration: Vector3) {
        previousTimestampNanos = timestampNanos
        stabilizationSinceNanos = timestampNanos
        faceDown = isFaceDown(acceleration)
        awaitingQuietRearm = true
        quietRearmSinceNanos = null
        triggered = false
        resetRotation(preserveSmoothing = true)
    }

    private fun smoothGyroscopeZ(value: Float): Float {
        val previous = smoothedGyroscopeZ
        val smoothed = if (previous == null) value else {
            previous + configuration.gyroscopeSmoothingAlpha * (value - previous)
        }
        smoothedGyroscopeZ = smoothed
        return smoothed
    }

    private fun isFaceDown(value: Vector3): Boolean {
        val magnitude = value.magnitude
        if (!magnitude.isFinite() || magnitude < MIN_USABLE_ACCELERATION_G) return false
        val zComponent = (value.z / magnitude).coerceIn(-1f, 1f)
        return zComponent >= cos(Math.toRadians(configuration.maximumFaceDownTiltDegrees.toDouble())).toFloat()
    }

    private fun isUsableAcceleration(value: Vector3): Boolean =
        value.x.isFinite() && value.y.isFinite() && value.z.isFinite() &&
            value.magnitude in MIN_USABLE_ACCELERATION_G..MAX_USABLE_ACCELERATION_G

    companion object {
        const val PHYSICAL_ROTATION_TARGET_DEGREES = 270f
        const val FILTERED_ROTATION_TRIGGER_DEGREES = 245f

        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val NANOS_PER_SECOND = 1_000_000_000f
        private const val MAX_SAMPLE_INTERVAL_NANOS = 100_000_000L
        private const val STANDARD_GRAVITY_G = 1f
        private const val MIN_USABLE_ACCELERATION_G = 0.20f
        private const val MAX_USABLE_ACCELERATION_G = 2.50f
    }
}

data class FullRotationGestureResult(
    val triggered: Boolean = false,
    val direction: RotationGestureDirection? = null,
    val phase: HoldGesturePhase,
    val stabilizationProgress: Float,
    val faceDown: Boolean,
    val estimatedRotationDegrees: Float,
    val gyroscopeZDps: Float,
)

enum class RotationGestureDirection {
    RIGHT,
    LEFT,
}

fun RotationGestureDirection.toInvertedCapsuleNavigationAction(): MediaAction = when (this) {
    RotationGestureDirection.LEFT -> MediaAction.NEXT
    RotationGestureDirection.RIGHT -> MediaAction.PREVIOUS
}
