package pl.trikimusic.controller.core.gesture

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
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
    COMPLETING,
    REARMING,
    TRIGGERED,
}

data class HoldArcGestureResult(
    val action: RatingGestureAction? = null,
    val direction: RatingGestureAction? = null,
    val phase: HoldGesturePhase,
    val holdProgress: Float,
    val faceDown: Boolean,
    val estimatedHorizontalDisplacementMeters: Float,
    val estimatedArcDepthMeters: Float,
)

/**
 * Recognizes a short left/right arc after the inverted capsule has stabilized.
 *
 * The fixed face-down pose makes the device X axis the repeatable horizontal reference. A valid
 * attempt needs a directional impulse, a shallow vertical excursion with both acceleration phases,
 * and deliberate braking. Short bounded integration windows prevent accelerometer drift from
 * accumulating between gestures.
 */
class HoldArcGestureDetector(
    private val configuration: Configuration = Configuration(),
) {
    data class Configuration(
        val holdMillis: Long = 500L,
        // The filtered IMU underestimates a hand path by roughly 10%; 9 cm in the estimate
        // therefore corresponds to the requested approximately 10 cm physical arc.
        val triggerDisplacementMeters: Float = 0.09f,
        val motionStartAccelerationG: Float = 0.05f,
        val accelerationDeadZoneG: Float = 0.025f,
        val verticalAccelerationDeadZoneG: Float = 0.020f,
        val maximumMotionMillis: Long = 1_800L,
        val linearAccelerationSmoothingAlpha: Float = 0.35f,
        val armingAccelerationToleranceG: Float = 0.18f,
        val armingMaximumAngularRateDps: Float = 45f,
        val maximumFaceDownTiltDegrees: Float = 25f,
        val directionConfirmationMillis: Long = 120L,
        val minimumDirectionImpulseGSeconds: Float = 0.025f,
        val minimumDirectionPeakAccelerationG: Float = 0.08f,
        val maximumCandidateDirectionChanges: Int = 1,
        val brakingAccelerationG: Float = 0.040f,
        val minimumDisplacementBeforeBrakingMeters: Float = 0.04f,
        val minimumBrakingImpulseGSeconds: Float = 0.020f,
        val minimumArcDepthMeters: Float = 0.012f,
        val maximumArcDepthMeters: Float = 0.12f,
        val minimumArcImpulseEachDirectionGSeconds: Float = 0.006f,
        val maximumFinalVerticalOffsetMeters: Float = 0.07f,
        val maximumForwardDisplacementMeters: Float = 0.10f,
        val minimumMotionMillis: Long = 280L,
        val directionMismatchToleranceMeters: Float = 0.04f,
        val maximumTriggerVelocityMetersPerSecond: Float = 0.70f,
        val maximumVerticalVelocityMetersPerSecond: Float = 0.70f,
        val maximumTriggerDisplacementMeters: Float = 0.16f,
        val maximumMotionAngularRateDps: Float = 120f,
        val maximumRotationMillis: Long = 80L,
        val rearmQuietMillis: Long = 140L,
    ) {
        init {
            require(holdMillis in 200L..3_000L)
            require(triggerDisplacementMeters.isFinite() && triggerDisplacementMeters in 0.08f..0.50f)
            require(motionStartAccelerationG.isFinite() && motionStartAccelerationG in 0.05f..1f)
            require(accelerationDeadZoneG.isFinite() && accelerationDeadZoneG in 0.01f..motionStartAccelerationG)
            require(
                verticalAccelerationDeadZoneG.isFinite() &&
                    verticalAccelerationDeadZoneG in 0.01f..motionStartAccelerationG,
            )
            require(maximumMotionMillis in 500L..4_000L)
            require(linearAccelerationSmoothingAlpha.isFinite() && linearAccelerationSmoothingAlpha in 0.05f..1f)
            require(armingAccelerationToleranceG.isFinite() && armingAccelerationToleranceG in 0.05f..0.40f)
            require(armingMaximumAngularRateDps.isFinite() && armingMaximumAngularRateDps in 10f..120f)
            require(maximumFaceDownTiltDegrees.isFinite() && maximumFaceDownTiltDegrees in 5f..45f)
            require(directionConfirmationMillis in 40L..300L)
            require(minimumDirectionImpulseGSeconds.isFinite() && minimumDirectionImpulseGSeconds in 0.005f..0.20f)
            require(
                minimumDirectionPeakAccelerationG.isFinite() &&
                    minimumDirectionPeakAccelerationG in motionStartAccelerationG..1f,
            )
            require(maximumCandidateDirectionChanges in 0..3)
            require(
                brakingAccelerationG.isFinite() &&
                    brakingAccelerationG in accelerationDeadZoneG..motionStartAccelerationG,
            )
            require(
                minimumDisplacementBeforeBrakingMeters.isFinite() &&
                    minimumDisplacementBeforeBrakingMeters in 0.02f..triggerDisplacementMeters,
            )
            require(minimumBrakingImpulseGSeconds.isFinite() && minimumBrakingImpulseGSeconds in 0.005f..0.20f)
            require(
                minimumArcDepthMeters.isFinite() &&
                    minimumArcDepthMeters in 0.01f..maximumArcDepthMeters,
            )
            require(maximumArcDepthMeters.isFinite() && maximumArcDepthMeters in 0.03f..0.25f)
            require(
                minimumArcImpulseEachDirectionGSeconds.isFinite() &&
                    minimumArcImpulseEachDirectionGSeconds in 0.003f..0.10f,
            )
            require(
                maximumFinalVerticalOffsetMeters.isFinite() &&
                    maximumFinalVerticalOffsetMeters in minimumArcDepthMeters..0.20f,
            )
            require(maximumForwardDisplacementMeters.isFinite() && maximumForwardDisplacementMeters in 0.03f..0.25f)
            require(minimumMotionMillis in directionConfirmationMillis..maximumMotionMillis)
            require(
                directionMismatchToleranceMeters.isFinite() &&
                    directionMismatchToleranceMeters in 0.02f..triggerDisplacementMeters,
            )
            require(
                maximumTriggerVelocityMetersPerSecond.isFinite() &&
                    maximumTriggerVelocityMetersPerSecond in 0.20f..2f,
            )
            require(
                maximumVerticalVelocityMetersPerSecond.isFinite() &&
                    maximumVerticalVelocityMetersPerSecond in 0.20f..2f,
            )
            require(
                maximumTriggerDisplacementMeters.isFinite() &&
                    maximumTriggerDisplacementMeters in triggerDisplacementMeters..MAX_ABSOLUTE_DISPLACEMENT_METERS,
            )
            require(maximumMotionAngularRateDps.isFinite() && maximumMotionAngularRateDps in 60f..360f)
            require(maximumMotionAngularRateDps > armingMaximumAngularRateDps)
            require(maximumRotationMillis in 40L..300L)
            require(rearmQuietMillis in 80L..500L)
        }
    }

    private var stabilizationSinceNanos: Long? = null
    private var previousTimestampNanos: Long? = null
    private var gravityBaseline: Vector3? = null
    private var motionStartedNanos: Long? = null
    private var filteredHorizontalAccelerationG = 0f
    private var filteredVerticalAccelerationG = 0f
    private var filteredForwardAccelerationG = 0f
    private var horizontalVelocityMetersPerSecond = 0f
    private var verticalVelocityMetersPerSecond = 0f
    private var forwardVelocityMetersPerSecond = 0f
    private var horizontalDisplacementMeters = 0f
    private var verticalDisplacementMeters = 0f
    private var forwardDisplacementMeters = 0f
    private var minimumVerticalDisplacementMeters = 0f
    private var maximumVerticalDisplacementMeters = 0f
    private var positiveVerticalImpulseGSeconds = 0f
    private var negativeVerticalImpulseGSeconds = 0f
    private var candidateAction: RatingGestureAction? = null
    private var confirmedAction: RatingGestureAction? = null
    private var directionConfirmationNanos = 0L
    private var directionImpulseGSeconds = 0f
    private var peakDirectionAccelerationG = 0f
    private var candidateDirectionChanges = 0
    private var brakingImpulseGSeconds = 0f
    private var excessiveRotationSinceNanos: Long? = null
    private var awaitingQuietRearm = false
    private var quietRearmSinceNanos: Long? = null
    private var faceDown = false
    private var triggered = false

    fun reset() {
        stabilizationSinceNanos = null
        previousTimestampNanos = null
        gravityBaseline = null
        faceDown = false
        resetMotion()
        triggered = false
    }

    fun process(sample: FilteredSensorData): HoldArcGestureResult {
        val timestampNanos = sample.source.timestampNanos
        val acceleration = sample.accelerometerG
        if (!isUsableAcceleration(acceleration)) {
            restartArming(timestampNanos, null)
            return result(HoldGesturePhase.HOLDING, 0f)
        }
        faceDown = isFaceDown(acceleration)

        if (stabilizationSinceNanos == null) {
            stabilizationSinceNanos = timestampNanos
            previousTimestampNanos = timestampNanos
            gravityBaseline = acceleration
            return result(HoldGesturePhase.HOLDING, 0f)
        }

        val previousTimestamp = previousTimestampNanos
        if (
            previousTimestamp == null ||
            timestampNanos <= previousTimestamp ||
            timestampNanos - previousTimestamp > MAX_SAMPLE_GAP_NANOS
        ) {
            restartArming(timestampNanos, acceleration)
            return result(HoldGesturePhase.HOLDING, 0f)
        }
        val deltaNanos = timestampNanos - previousTimestamp
        val deltaSeconds = deltaNanos / NANOS_PER_SECOND
        previousTimestampNanos = timestampNanos

        val stabilizationStart = requireNotNull(stabilizationSinceNanos)
        val stabilizationNanos = configuration.holdMillis * NANOS_PER_MILLISECOND
        val stabilizedNanos = (timestampNanos - stabilizationStart).coerceAtLeast(0L)
        val holdProgress = (stabilizedNanos.toDouble() / stabilizationNanos).toFloat().coerceIn(0f, 1f)
        if (stabilizedNanos < stabilizationNanos) {
            if (!isStableForArming(sample)) {
                restartArming(timestampNanos, acceleration)
                return result(HoldGesturePhase.HOLDING, 0f)
            }
            gravityBaseline = lowPass(gravityBaseline, acceleration, BASELINE_CAPTURE_ALPHA)
            resetMotion()
            return result(HoldGesturePhase.HOLDING, holdProgress)
        }

        val baseline = gravityBaseline ?: acceleration.also { gravityBaseline = it }
        val baselineMagnitude = baseline.magnitude
        if (
            baselineMagnitude !in MIN_BASELINE_GRAVITY_G..MAX_BASELINE_GRAVITY_G ||
            !isFaceDown(baseline)
        ) {
            restartArming(timestampNanos, acceleration)
            return result(HoldGesturePhase.HOLDING, 0f)
        }
        faceDown = true
        val gravityUnit = normalized(baseline)
        val rightUnit = rightUnit(gravityUnit)
        val forwardUnit = normalized(cross(gravityUnit, rightUnit))
        val linearAcceleration = Vector3(
            acceleration.x - gravityUnit.x * baselineMagnitude,
            acceleration.y - gravityUnit.y * baselineMagnitude,
            acceleration.z - gravityUnit.z * baselineMagnitude,
        )
        filteredHorizontalAccelerationG = smooth(filteredHorizontalAccelerationG, dot(linearAcceleration, rightUnit))
        filteredVerticalAccelerationG = smooth(filteredVerticalAccelerationG, dot(linearAcceleration, gravityUnit))
        filteredForwardAccelerationG = smooth(filteredForwardAccelerationG, dot(linearAcceleration, forwardUnit))

        if (awaitingQuietRearm) {
            val quiet = maximumLinearAcceleration() < configuration.accelerationDeadZoneG &&
                sample.gyroscopeMagnitude <= configuration.armingMaximumAngularRateDps
            if (quiet) {
                val quietSince = quietRearmSinceNanos ?: timestampNanos.also { quietRearmSinceNanos = it }
                gravityBaseline = lowPass(gravityBaseline, acceleration, BASELINE_TRACKING_ALPHA)
                if (timestampNanos - quietSince >= configuration.rearmQuietMillis * NANOS_PER_MILLISECOND) {
                    restartArming(timestampNanos, acceleration)
                }
            } else {
                quietRearmSinceNanos = null
            }
            return result(HoldGesturePhase.REARMING, 1f)
        }

        if (sample.gyroscopeMagnitude > configuration.maximumMotionAngularRateDps) {
            val rotationSince = excessiveRotationSinceNanos
                ?: timestampNanos.also { excessiveRotationSinceNanos = it }
            if (timestampNanos - rotationSince >= configuration.maximumRotationMillis * NANOS_PER_MILLISECOND) {
                invalidateMotion()
                return result(HoldGesturePhase.REARMING, 1f)
            }
            return result(if (motionStartedNanos == null) HoldGesturePhase.READY else HoldGesturePhase.TRACKING, 1f)
        }
        excessiveRotationSinceNanos = null

        if (motionStartedNanos == null) {
            if (abs(filteredHorizontalAccelerationG) < configuration.motionStartAccelerationG) {
                resetMotion()
                return result(HoldGesturePhase.READY, 1f)
            }
            startMotion(timestampNanos, actionForAcceleration(filteredHorizontalAccelerationG))
        }

        var motionElapsedNanos = timestampNanos - requireNotNull(motionStartedNanos)
        if (motionElapsedNanos > configuration.maximumMotionMillis * NANOS_PER_MILLISECOND) {
            invalidateMotion()
            return result(HoldGesturePhase.REARMING, 1f)
        }

        val effectiveHorizontalAccelerationG = deadZone(
            filteredHorizontalAccelerationG,
            configuration.accelerationDeadZoneG,
        )
        if (confirmedAction == null) {
            if (effectiveHorizontalAccelerationG == 0f) {
                invalidateMotion()
                return result(HoldGesturePhase.REARMING, 1f)
            }
            val currentAction = actionForAcceleration(effectiveHorizontalAccelerationG)
            if (currentAction != candidateAction) {
                candidateDirectionChanges += 1
                if (candidateDirectionChanges > configuration.maximumCandidateDirectionChanges) {
                    invalidateMotion()
                    return result(HoldGesturePhase.REARMING, 1f)
                }
                startMotion(timestampNanos, currentAction, preserveDirectionChanges = true)
                motionElapsedNanos = 0L
            } else {
                directionConfirmationNanos += deltaNanos
                directionImpulseGSeconds += abs(effectiveHorizontalAccelerationG) * deltaSeconds
                peakDirectionAccelerationG = max(peakDirectionAccelerationG, abs(effectiveHorizontalAccelerationG))
                if (
                    directionConfirmationNanos >= configuration.directionConfirmationMillis * NANOS_PER_MILLISECOND &&
                    directionImpulseGSeconds >= configuration.minimumDirectionImpulseGSeconds &&
                    peakDirectionAccelerationG >= configuration.minimumDirectionPeakAccelerationG
                ) {
                    confirmedAction = currentAction
                }
            }
        } else if (
            isAccelerationOppositeTo(effectiveHorizontalAccelerationG, requireNotNull(confirmedAction)) &&
            abs(effectiveHorizontalAccelerationG) >= configuration.brakingAccelerationG &&
            directionalDisplacement(requireNotNull(confirmedAction)) >= configuration.minimumDisplacementBeforeBrakingMeters
        ) {
            brakingImpulseGSeconds += abs(effectiveHorizontalAccelerationG) * deltaSeconds
        }

        val effectiveVerticalAccelerationG = deadZone(
            filteredVerticalAccelerationG,
            configuration.verticalAccelerationDeadZoneG,
        )
        if (effectiveVerticalAccelerationG > 0f) {
            positiveVerticalImpulseGSeconds += effectiveVerticalAccelerationG * deltaSeconds
        } else if (effectiveVerticalAccelerationG < 0f) {
            negativeVerticalImpulseGSeconds += -effectiveVerticalAccelerationG * deltaSeconds
        }

        integrateMotion(
            horizontalAccelerationG = effectiveHorizontalAccelerationG,
            verticalAccelerationG = effectiveVerticalAccelerationG,
            forwardAccelerationG = deadZone(filteredForwardAccelerationG, configuration.accelerationDeadZoneG),
            deltaSeconds = deltaSeconds,
        )

        val lockedAction = confirmedAction
        if (lockedAction != null && directionalDisplacement(lockedAction) < -configuration.directionMismatchToleranceMeters) {
            invalidateMotion()
            return result(HoldGesturePhase.REARMING, 1f)
        }
        if (
            lockedAction != null &&
            directionalDisplacement(lockedAction) > configuration.maximumTriggerDisplacementMeters
        ) {
            invalidateMotion()
            return result(HoldGesturePhase.REARMING, 1f)
        }
        if (arcDepthMeters() > configuration.maximumArcDepthMeters ||
            abs(forwardDisplacementMeters) > configuration.maximumForwardDisplacementMeters
        ) {
            invalidateMotion()
            return result(HoldGesturePhase.REARMING, 1f)
        }

        val action = lockedAction?.takeIf {
            brakingImpulseGSeconds >= configuration.minimumBrakingImpulseGSeconds &&
                abs(horizontalVelocityMetersPerSecond) <= configuration.maximumTriggerVelocityMetersPerSecond &&
                abs(verticalVelocityMetersPerSecond) <= configuration.maximumVerticalVelocityMetersPerSecond &&
                abs(verticalDisplacementMeters) <= configuration.maximumFinalVerticalOffsetMeters &&
                arcDepthMeters() >= configuration.minimumArcDepthMeters &&
                positiveVerticalImpulseGSeconds >= configuration.minimumArcImpulseEachDirectionGSeconds &&
                negativeVerticalImpulseGSeconds >= configuration.minimumArcImpulseEachDirectionGSeconds &&
                motionElapsedNanos >= configuration.minimumMotionMillis * NANOS_PER_MILLISECOND &&
                directionalDisplacement(it) >= configuration.triggerDisplacementMeters
        }
        if (action != null) {
            triggered = true
            awaitingQuietRearm = true
            quietRearmSinceNanos = null
        }
        return result(
            phase = when {
                triggered -> HoldGesturePhase.TRIGGERED
                brakingImpulseGSeconds > 0f -> HoldGesturePhase.COMPLETING
                else -> HoldGesturePhase.TRACKING
            },
            holdProgress = 1f,
            action = action,
        )
    }

    private fun integrateMotion(
        horizontalAccelerationG: Float,
        verticalAccelerationG: Float,
        forwardAccelerationG: Float,
        deltaSeconds: Float,
    ) {
        val horizontalAcceleration = horizontalAccelerationG * STANDARD_GRAVITY_METERS_PER_SECOND_SQUARED
        val verticalAcceleration = verticalAccelerationG * STANDARD_GRAVITY_METERS_PER_SECOND_SQUARED
        val forwardAcceleration = forwardAccelerationG * STANDARD_GRAVITY_METERS_PER_SECOND_SQUARED
        horizontalDisplacementMeters += horizontalVelocityMetersPerSecond * deltaSeconds +
            0.5f * horizontalAcceleration * deltaSeconds * deltaSeconds
        verticalDisplacementMeters += verticalVelocityMetersPerSecond * deltaSeconds +
            0.5f * verticalAcceleration * deltaSeconds * deltaSeconds
        forwardDisplacementMeters += forwardVelocityMetersPerSecond * deltaSeconds +
            0.5f * forwardAcceleration * deltaSeconds * deltaSeconds
        horizontalVelocityMetersPerSecond = boundedVelocity(
            horizontalVelocityMetersPerSecond + horizontalAcceleration * deltaSeconds,
            horizontalAccelerationG,
        )
        verticalVelocityMetersPerSecond = boundedVelocity(
            verticalVelocityMetersPerSecond + verticalAcceleration * deltaSeconds,
            verticalAccelerationG,
        )
        forwardVelocityMetersPerSecond = boundedVelocity(
            forwardVelocityMetersPerSecond + forwardAcceleration * deltaSeconds,
            forwardAccelerationG,
        )
        horizontalDisplacementMeters = boundedDisplacement(horizontalDisplacementMeters)
        verticalDisplacementMeters = boundedDisplacement(verticalDisplacementMeters)
        forwardDisplacementMeters = boundedDisplacement(forwardDisplacementMeters)
        minimumVerticalDisplacementMeters = min(minimumVerticalDisplacementMeters, verticalDisplacementMeters)
        maximumVerticalDisplacementMeters = max(maximumVerticalDisplacementMeters, verticalDisplacementMeters)
    }

    private fun result(
        phase: HoldGesturePhase,
        holdProgress: Float,
        action: RatingGestureAction? = null,
    ) = HoldArcGestureResult(
        action = action,
        direction = confirmedAction,
        phase = phase,
        holdProgress = holdProgress,
        faceDown = faceDown,
        estimatedHorizontalDisplacementMeters = horizontalDisplacementMeters,
        estimatedArcDepthMeters = arcDepthMeters(),
    )

    private fun resetMotion() {
        clearMotionState()
        awaitingQuietRearm = false
        quietRearmSinceNanos = null
        triggered = false
    }

    private fun clearMotionState() {
        motionStartedNanos = null
        filteredHorizontalAccelerationG = 0f
        filteredVerticalAccelerationG = 0f
        filteredForwardAccelerationG = 0f
        horizontalVelocityMetersPerSecond = 0f
        verticalVelocityMetersPerSecond = 0f
        forwardVelocityMetersPerSecond = 0f
        horizontalDisplacementMeters = 0f
        verticalDisplacementMeters = 0f
        forwardDisplacementMeters = 0f
        minimumVerticalDisplacementMeters = 0f
        maximumVerticalDisplacementMeters = 0f
        positiveVerticalImpulseGSeconds = 0f
        negativeVerticalImpulseGSeconds = 0f
        candidateAction = null
        confirmedAction = null
        directionConfirmationNanos = 0L
        directionImpulseGSeconds = 0f
        peakDirectionAccelerationG = 0f
        candidateDirectionChanges = 0
        brakingImpulseGSeconds = 0f
        excessiveRotationSinceNanos = null
    }

    private fun invalidateMotion() {
        clearMotionState()
        awaitingQuietRearm = true
        quietRearmSinceNanos = null
    }

    private fun startMotion(
        timestampNanos: Long,
        action: RatingGestureAction,
        preserveDirectionChanges: Boolean = false,
    ) {
        val horizontalAcceleration = filteredHorizontalAccelerationG
        val verticalAcceleration = filteredVerticalAccelerationG
        val forwardAcceleration = filteredForwardAccelerationG
        val preservedDirectionChanges = if (preserveDirectionChanges) candidateDirectionChanges else 0
        clearMotionState()
        filteredHorizontalAccelerationG = horizontalAcceleration
        filteredVerticalAccelerationG = verticalAcceleration
        filteredForwardAccelerationG = forwardAcceleration
        motionStartedNanos = timestampNanos
        candidateAction = action
        candidateDirectionChanges = preservedDirectionChanges
    }

    private fun restartArming(timestampNanos: Long, acceleration: Vector3?) {
        stabilizationSinceNanos = timestampNanos
        previousTimestampNanos = timestampNanos
        gravityBaseline = acceleration
        faceDown = acceleration?.let(::isFaceDown) ?: false
        resetMotion()
    }

    private fun isStableForArming(sample: FilteredSensorData): Boolean =
        faceDown &&
            abs(sample.accelerationMagnitude - 1f) <= configuration.armingAccelerationToleranceG &&
            sample.gyroscopeMagnitude <= configuration.armingMaximumAngularRateDps

    private fun isFaceDown(value: Vector3): Boolean {
        val magnitude = value.magnitude
        if (!magnitude.isFinite() || magnitude < MIN_USABLE_ACCELERATION_G) return false
        val faceDownComponent = (value.z / magnitude).coerceIn(-1f, 1f)
        return faceDownComponent >= cos(Math.toRadians(configuration.maximumFaceDownTiltDegrees.toDouble())).toFloat()
    }

    private fun actionForAcceleration(accelerationG: Float): RatingGestureAction =
        if (accelerationG > 0f) RatingGestureAction.LIKE else RatingGestureAction.DISLIKE

    private fun isAccelerationOppositeTo(accelerationG: Float, action: RatingGestureAction): Boolean = when (action) {
        RatingGestureAction.LIKE -> accelerationG < 0f
        RatingGestureAction.DISLIKE -> accelerationG > 0f
    }

    private fun directionalDisplacement(action: RatingGestureAction): Float = when (action) {
        RatingGestureAction.LIKE -> horizontalDisplacementMeters
        RatingGestureAction.DISLIKE -> -horizontalDisplacementMeters
    }

    private fun maximumLinearAcceleration(): Float = max(
        abs(filteredHorizontalAccelerationG),
        max(abs(filteredVerticalAccelerationG), abs(filteredForwardAccelerationG)),
    )

    private fun arcDepthMeters(): Float = maximumVerticalDisplacementMeters - minimumVerticalDisplacementMeters

    private fun smooth(previous: Float, current: Float): Float =
        previous + configuration.linearAccelerationSmoothingAlpha * (current - previous)

    private fun boundedVelocity(value: Float, accelerationG: Float): Float {
        val damped = if (accelerationG == 0f) value * VELOCITY_DAMPING_WHEN_QUIET else value
        return damped.coerceIn(-MAX_ABSOLUTE_VELOCITY_METERS_PER_SECOND, MAX_ABSOLUTE_VELOCITY_METERS_PER_SECOND)
    }

    private fun boundedDisplacement(value: Float): Float =
        value.coerceIn(-MAX_ABSOLUTE_DISPLACEMENT_METERS, MAX_ABSOLUTE_DISPLACEMENT_METERS)

    private fun deadZone(value: Float, threshold: Float): Float = if (abs(value) < threshold) 0f else value

    private fun isUsableAcceleration(value: Vector3): Boolean =
        value.x.isFinite() && value.y.isFinite() && value.z.isFinite() &&
            value.magnitude in MIN_USABLE_ACCELERATION_G..MAX_USABLE_ACCELERATION_G

    private fun normalized(value: Vector3): Vector3 {
        val magnitude = value.magnitude
        require(magnitude > MIN_VECTOR_MAGNITUDE)
        return Vector3(value.x / magnitude, value.y / magnitude, value.z / magnitude)
    }

    private fun rightUnit(gravityUnit: Vector3): Vector3 {
        val projection = gravityUnit.x
        return normalized(
            Vector3(
                1f - projection * gravityUnit.x,
                -projection * gravityUnit.y,
                -projection * gravityUnit.z,
            ),
        )
    }

    private fun lowPass(previous: Vector3?, current: Vector3, alpha: Float): Vector3 {
        if (previous == null) return current
        return Vector3(
            previous.x + alpha * (current.x - previous.x),
            previous.y + alpha * (current.y - previous.y),
            previous.z + alpha * (current.z - previous.z),
        )
    }

    private fun cross(first: Vector3, second: Vector3): Vector3 = Vector3(
        first.y * second.z - first.z * second.y,
        first.z * second.x - first.x * second.z,
        first.x * second.y - first.y * second.x,
    )

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
        const val MIN_VECTOR_MAGNITUDE = 0.001f
        const val MAX_ABSOLUTE_VELOCITY_METERS_PER_SECOND = 2f
        const val MAX_ABSOLUTE_DISPLACEMENT_METERS = 0.60f
        const val VELOCITY_DAMPING_WHEN_QUIET = 0.92f
    }
}
