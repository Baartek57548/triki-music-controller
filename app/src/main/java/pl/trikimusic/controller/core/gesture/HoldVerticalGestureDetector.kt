package pl.trikimusic.controller.core.gesture

import kotlin.math.abs
import pl.trikimusic.controller.domain.model.FilteredSensorData
import pl.trikimusic.controller.domain.model.Vector3

enum class RatingGestureAction {
    LIKE,
    DISLIKE,
}

enum class HoldGesturePhase {
    IDLE,
    HOLDING,
    READY,
    TRACKING,
    TRIGGERED,
}

data class HoldVerticalGestureResult(
    val action: RatingGestureAction? = null,
    val phase: HoldGesturePhase,
    val holdProgress: Float,
    val estimatedDisplacementMeters: Float,
)

/**
 * Recognizes a deliberate 20 cm vertical movement while the physical button is held.
 *
 * An accelerometer cannot provide an absolute position. The detector therefore captures the local
 * gravity vector during the initial hold, removes that baseline, and double-integrates only a short,
 * thresholded acceleration pulse. The estimate is bounded and reset between attempts to prevent
 * unbounded drift. Hardware validation shows that a deliberate lift produces a negative projected
 * displacement in Triki's face-up sensor convention; lowering produces a positive displacement.
 */
class HoldVerticalGestureDetector(
    private val configuration: Configuration = Configuration(),
) {
    data class Configuration(
        val holdMillis: Long = 500L,
        val triggerDisplacementMeters: Float = 0.20f,
        val motionStartAccelerationG: Float = 0.12f,
        val accelerationDeadZoneG: Float = 0.06f,
        val maximumMotionMillis: Long = 1_800L,
        val linearAccelerationSmoothingAlpha: Float = 0.35f,
    ) {
        init {
            require(holdMillis in 200L..3_000L)
            require(triggerDisplacementMeters.isFinite() && triggerDisplacementMeters in 0.10f..0.50f)
            require(motionStartAccelerationG.isFinite() && motionStartAccelerationG in 0.05f..1f)
            require(
                accelerationDeadZoneG.isFinite() &&
                    accelerationDeadZoneG in 0.01f..motionStartAccelerationG,
            )
            require(maximumMotionMillis in 500L..4_000L)
            require(linearAccelerationSmoothingAlpha.isFinite() && linearAccelerationSmoothingAlpha in 0.05f..1f)
        }
    }

    private var pressedSinceNanos: Long? = null
    private var previousTimestampNanos: Long? = null
    private var gravityBaseline: Vector3? = null
    private var motionStartedNanos: Long? = null
    private var filteredLinearAccelerationG = 0f
    private var verticalVelocityMetersPerSecond = 0f
    private var displacementMeters = 0f
    private var triggered = false

    fun reset() {
        pressedSinceNanos = null
        previousTimestampNanos = null
        gravityBaseline = null
        resetMotion()
        triggered = false
    }

    fun process(sample: FilteredSensorData, buttonPressed: Boolean): HoldVerticalGestureResult {
        if (!buttonPressed) {
            reset()
            return result(HoldGesturePhase.IDLE, 0f)
        }

        val timestampNanos = sample.source.timestampNanos
        val acceleration = sample.accelerometerG
        if (!isUsableAcceleration(acceleration)) {
            resetMotion()
            previousTimestampNanos = timestampNanos
            return result(HoldGesturePhase.HOLDING, 0f)
        }

        val pressStart = pressedSinceNanos ?: timestampNanos.also {
            pressedSinceNanos = it
            previousTimestampNanos = timestampNanos
            gravityBaseline = acceleration
        }
        val previousTimestamp = previousTimestampNanos
        if (
            previousTimestamp == null ||
            timestampNanos <= previousTimestamp ||
            timestampNanos - previousTimestamp > MAX_SAMPLE_GAP_NANOS
        ) {
            pressedSinceNanos = timestampNanos
            previousTimestampNanos = timestampNanos
            gravityBaseline = acceleration
            resetMotion()
            triggered = false
            return result(HoldGesturePhase.HOLDING, 0f)
        }
        val deltaSeconds = (timestampNanos - previousTimestamp) / NANOS_PER_SECOND
        previousTimestampNanos = timestampNanos

        if (triggered) return result(HoldGesturePhase.TRIGGERED, 1f)

        val holdNanos = configuration.holdMillis * NANOS_PER_MILLISECOND
        val heldNanos = (timestampNanos - pressStart).coerceAtLeast(0L)
        val holdProgress = (heldNanos.toDouble() / holdNanos).toFloat().coerceIn(0f, 1f)
        if (heldNanos < holdNanos) {
            gravityBaseline = lowPass(gravityBaseline, acceleration, BASELINE_CAPTURE_ALPHA)
            resetMotion()
            return result(HoldGesturePhase.HOLDING, holdProgress)
        }

        val baseline = gravityBaseline ?: acceleration.also { gravityBaseline = it }
        val baselineMagnitude = baseline.magnitude
        if (baselineMagnitude !in MIN_BASELINE_GRAVITY_G..MAX_BASELINE_GRAVITY_G) {
            gravityBaseline = acceleration
            resetMotion()
            return result(HoldGesturePhase.HOLDING, 0f)
        }
        val gravityUnit = Vector3(
            baseline.x / baselineMagnitude,
            baseline.y / baselineMagnitude,
            baseline.z / baselineMagnitude,
        )
        val projectedAccelerationG = dot(acceleration, gravityUnit)
        val rawLinearAccelerationG = projectedAccelerationG - baselineMagnitude
        filteredLinearAccelerationG += configuration.linearAccelerationSmoothingAlpha *
            (rawLinearAccelerationG - filteredLinearAccelerationG)

        if (motionStartedNanos == null) {
            if (abs(filteredLinearAccelerationG) < configuration.motionStartAccelerationG) {
                gravityBaseline = lowPass(gravityBaseline, acceleration, BASELINE_TRACKING_ALPHA)
                resetMotion()
                return result(HoldGesturePhase.READY, 1f)
            }
            motionStartedNanos = timestampNanos
            verticalVelocityMetersPerSecond = 0f
            displacementMeters = 0f
        }

        val motionElapsedNanos = timestampNanos - requireNotNull(motionStartedNanos)
        if (motionElapsedNanos > configuration.maximumMotionMillis * NANOS_PER_MILLISECOND) {
            gravityBaseline = acceleration
            resetMotion()
            return result(HoldGesturePhase.READY, 1f)
        }

        val effectiveAccelerationG = filteredLinearAccelerationG
            .takeUnless { abs(it) < configuration.accelerationDeadZoneG }
            ?: 0f
        val accelerationMetersPerSecondSquared = effectiveAccelerationG * STANDARD_GRAVITY_METERS_PER_SECOND_SQUARED
        displacementMeters += verticalVelocityMetersPerSecond * deltaSeconds +
            0.5f * accelerationMetersPerSecondSquared * deltaSeconds * deltaSeconds
        verticalVelocityMetersPerSecond = (
            verticalVelocityMetersPerSecond + accelerationMetersPerSecondSquared * deltaSeconds
            ).coerceIn(-MAX_ABSOLUTE_VELOCITY_METERS_PER_SECOND, MAX_ABSOLUTE_VELOCITY_METERS_PER_SECOND)
        if (effectiveAccelerationG == 0f) {
            verticalVelocityMetersPerSecond *= VELOCITY_DAMPING_WHEN_QUIET
        }
        displacementMeters = displacementMeters.coerceIn(
            -MAX_ABSOLUTE_DISPLACEMENT_METERS,
            MAX_ABSOLUTE_DISPLACEMENT_METERS,
        )

        val action = when {
            displacementMeters <= -configuration.triggerDisplacementMeters -> RatingGestureAction.LIKE
            displacementMeters >= configuration.triggerDisplacementMeters -> RatingGestureAction.DISLIKE
            else -> null
        }
        if (action != null) triggered = true
        return result(
            phase = if (triggered) HoldGesturePhase.TRIGGERED else HoldGesturePhase.TRACKING,
            holdProgress = 1f,
            action = action,
        )
    }

    private fun result(
        phase: HoldGesturePhase,
        holdProgress: Float,
        action: RatingGestureAction? = null,
    ) = HoldVerticalGestureResult(
        action = action,
        phase = phase,
        holdProgress = holdProgress,
        estimatedDisplacementMeters = displacementMeters,
    )

    private fun resetMotion() {
        motionStartedNanos = null
        filteredLinearAccelerationG = 0f
        verticalVelocityMetersPerSecond = 0f
        displacementMeters = 0f
    }

    private fun isUsableAcceleration(value: Vector3): Boolean =
        value.x.isFinite() && value.y.isFinite() && value.z.isFinite() &&
            value.magnitude in MIN_USABLE_ACCELERATION_G..MAX_USABLE_ACCELERATION_G

    private fun lowPass(previous: Vector3?, current: Vector3, alpha: Float): Vector3 {
        if (previous == null) return current
        return Vector3(
            previous.x + alpha * (current.x - previous.x),
            previous.y + alpha * (current.y - previous.y),
            previous.z + alpha * (current.z - previous.z),
        )
    }

    private fun dot(first: Vector3, second: Vector3): Float =
        first.x * second.x + first.y * second.y + first.z * second.z

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val NANOS_PER_SECOND = 1_000_000_000f
        const val MAX_SAMPLE_GAP_NANOS = 150_000_000L
        const val STANDARD_GRAVITY_METERS_PER_SECOND_SQUARED = 9.80665f
        const val BASELINE_CAPTURE_ALPHA = 0.12f
        const val BASELINE_TRACKING_ALPHA = 0.025f
        const val MIN_BASELINE_GRAVITY_G = 0.65f
        const val MAX_BASELINE_GRAVITY_G = 1.35f
        const val MIN_USABLE_ACCELERATION_G = 0.20f
        const val MAX_USABLE_ACCELERATION_G = 2.50f
        const val MAX_ABSOLUTE_VELOCITY_METERS_PER_SECOND = 2f
        const val MAX_ABSOLUTE_DISPLACEMENT_METERS = 0.60f
        const val VELOCITY_DAMPING_WHEN_QUIET = 0.92f
    }
}
