package pl.trikimusic.controller.core.gesture

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import pl.trikimusic.controller.domain.model.FilteredSensorData
import pl.trikimusic.controller.domain.model.GestureEvent
import pl.trikimusic.controller.domain.model.GestureFeatureVector
import pl.trikimusic.controller.domain.model.GestureThresholds
import pl.trikimusic.controller.domain.model.GestureType
import pl.trikimusic.controller.domain.model.MIN_PERSONALIZED_SAMPLES_PER_GESTURE
import pl.trikimusic.controller.domain.model.PersonalizedGestureModel
import pl.trikimusic.controller.domain.model.Vector3

/**
 * Classifies a complete motion window instead of reacting to isolated samples.
 * Requiring a stable → moving → stable cycle prevents a stationary controller,
 * sensor bias, or a single corrupted packet from executing media actions.
 */
class GestureEngine {
    private val featureExtractor = GestureFeatureExtractor()
    private val personalizedClassifier = PersonalizedGestureClassifier()
    private val lastEmittedAt = mutableMapOf<GestureType, Long>()
    private var phase = Phase.WARMING_UP
    private var stableSinceNanos: Long? = null
    private var lastTimestampNanos: Long? = null
    private var previousAccelerometer: Vector3? = null
    private var armedGravityReference = Vector3(0f, 0f, 1f)
    private var awaitingFlipReturn = false
    private var flipReturnReference: Vector3? = null
    private val stableHistory = ArrayDeque<FilteredSensorData>(STABLE_HISTORY_SAMPLES)
    private var motionWindow: MotionWindow? = null
    internal var lastCapturedFeatures: GestureFeatureVector? = null
        private set
    internal var lastPersonalizedRecognition: PersonalizedRecognition? = null
        private set

    fun reset() {
        lastEmittedAt.clear()
        resetStreamState()
    }

    fun process(
        sample: FilteredSensorData,
        thresholds: GestureThresholds,
        personalizedModel: PersonalizedGestureModel = PersonalizedGestureModel(),
    ): List<GestureEvent> {
        val now = sample.source.timestampNanos
        val previousTimestamp = lastTimestampNanos
        if (
            previousTimestamp == null ||
            now <= previousTimestamp ||
            now - previousTimestamp > MAX_SAMPLE_GAP_NANOS
        ) {
            initializeStream(sample)
            return emptyList()
        }

        val dtNanos = now - previousTimestamp
        val dtSeconds = dtNanos / NANOS_PER_SECOND_F
        lastTimestampNanos = now

        val accelerationDelta = previousAccelerometer
            ?.let { previous -> (sample.accelerometerG - previous).magnitude }
            ?: 0f
        val gravityDirectionRateDps = previousAccelerometer
            ?.let { previous -> gravityDirectionRate(previous, sample.accelerometerG, dtSeconds) }
            ?: 0f
        previousAccelerometer = sample.accelerometerG

        val stable = isStable(sample, gravityDirectionRateDps, accelerationDelta)
        val moving = isMoving(sample, gravityDirectionRateDps, accelerationDelta)
        if (awaitingFlipReturn) return processFlipReturn(sample, now, stable)

        return when (phase) {
            Phase.WARMING_UP -> {
                warmUp(sample, now, stable)
                emptyList()
            }

            Phase.READY -> {
                if (moving || hasGravityRelativeMotion(sample)) {
                    motionWindow = MotionWindow(now, stableHistory.toList(), armedGravityReference).also {
                        it.add(sample, dtNanos, thresholds)
                    }
                    stableSinceNanos = null
                    phase = Phase.RECORDING
                } else if (stable) {
                    rememberStableSample(sample)
                }
                emptyList()
            }

            Phase.RECORDING -> processMotion(
                sample,
                thresholds,
                personalizedModel,
                now,
                dtNanos,
                stable,
                moving,
            )
        }
    }

    /** Finalizes a user-controlled Start/Stop capture without weakening live detection. */
    fun finishRecording(
        thresholds: GestureThresholds,
        personalizedModel: PersonalizedGestureModel = PersonalizedGestureModel(),
    ): GestureEvent? {
        val window = motionWindow ?: return null
        val timestamp = lastTimestampNanos ?: return null
        if (timestamp - window.startedAtNanos < MIN_RECORDED_MOTION_NANOS) return null
        if (window.emittedDuringMotion) {
            resetStreamState()
            return null
        }
        val event = classify(window, timestamp, thresholds, personalizedModel)
        resetStreamState()
        return event
    }

    private fun warmUp(sample: FilteredSensorData, now: Long, stable: Boolean) {
        if (!stable) {
            stableSinceNanos = null
            return
        }
        val stableSince = stableSinceNanos ?: now.also { stableSinceNanos = it }
        rememberStableSample(sample)
        if (now - stableSince >= ARMING_STABLE_NANOS) {
            armedGravityReference = averageGravity(stableHistory.toList())
            stableSinceNanos = null
            phase = Phase.READY
        }
    }

    private fun processMotion(
        sample: FilteredSensorData,
        thresholds: GestureThresholds,
        personalizedModel: PersonalizedGestureModel,
        now: Long,
        dtNanos: Long,
        stable: Boolean,
        moving: Boolean,
    ): List<GestureEvent> {
        val window = requireNotNull(motionWindow)
        window.add(sample, dtNanos, thresholds)
        val liveEvent = if (!window.emittedDuringMotion) {
            window.liveRecognition(thresholds)?.let { recognition ->
                emit(
                    recognition.type,
                    now,
                    recognition.confidence,
                    recognition.magnitude,
                    thresholds,
                )?.also { window.emittedDuringMotion = true }
            }
        } else {
            null
        }

        if (moving) {
            stableSinceNanos = null
        } else if (stable) {
            if (stableSinceNanos == null) stableSinceNanos = now
        } else {
            stableSinceNanos = null
        }

        if (now - window.startedAtNanos >= MAX_MOTION_WINDOW_NANOS) {
            // A permanently non-neutral stream indicates bias or corrupt data, not a deliberate gesture.
            resetAfterMotion(sample, stable)
            return listOfNotNull(liveEvent)
        }

        val stableSince = stableSinceNanos ?: return listOfNotNull(liveEvent)
        val requiredStableNanos = if (window.shakeCycles == 1) {
            SINGLE_SHAKE_END_STABLE_NANOS
        } else {
            MOTION_END_STABLE_NANOS
        }
        if (now - stableSince < requiredStableNanos) return listOfNotNull(liveEvent)

        val event = if (window.emittedDuringMotion) null else classify(window, now, thresholds, personalizedModel)
        resetAfterMotion(sample, stable = true)
        return listOfNotNull(liveEvent, event)
    }

    private fun classify(
        window: MotionWindow,
        timestampNanos: Long,
        thresholds: GestureThresholds,
        personalizedModel: PersonalizedGestureModel,
    ): GestureEvent? {
        val deterministic = when {
            window.freeFallNanos >= MIN_FREE_FALL_NANOS && window.impactAfterFreeFall -> Recognition(
                GestureType.TAP,
                (window.peakAccelerationMagnitude / thresholds.impactG).coerceIn(0f, 1f),
                window.peakAccelerationMagnitude,
            )

            window.isCleanTap(thresholds) -> Recognition(
                GestureType.TAP,
                (window.peakRawAccelerationMagnitude / (thresholds.impactG * 1.7f)).coerceIn(0f, 1f),
                window.peakRawAccelerationMagnitude,
            )

            window.shakeCycles >= 2 -> Recognition(
                GestureType.DOUBLE_SHAKE,
                (window.peakGyroscopeMagnitude / (thresholds.shakeDps * 1.6f)).coerceIn(0f, 1f),
                window.peakGyroscopeMagnitude,
            )

            window.shakeCycles == 1 -> Recognition(
                GestureType.SHAKE,
                (window.peakGyroscopeMagnitude / (thresholds.shakeDps * 1.6f)).coerceIn(0f, 1f),
                window.peakGyroscopeMagnitude,
            )

            window.longestUpsideDownNanos >= MIN_FLIP_HOLD_NANOS &&
                window.finalGravityProjection < FLIP_FINAL_Z_THRESHOLD &&
                window.peakGyroscopeMagnitude >= MIN_FLIP_GYROSCOPE_DPS -> Recognition(
                GestureType.FLIP,
                abs(window.finalGravityProjection).coerceIn(0f, 1f),
                window.peakGyroscopeMagnitude,
            )

            abs(window.integratedTwistDegrees) >= MIN_ROTATION_DEGREES &&
                window.peakTwistDps >= thresholds.rotationDps &&
                window.peakTwistDominance >= MIN_TWIST_DOMINANCE -> Recognition(
                if (window.integratedTwistDegrees > 0f) GestureType.ROTATE_RIGHT else GestureType.ROTATE_LEFT,
                (abs(window.integratedTwistDegrees) / STRONG_ROTATION_DEGREES).coerceIn(0f, 1f),
                abs(window.integratedTwistDegrees),
            )

            window.peakGravityAngleDegrees >= thresholds.tiltDegrees &&
                window.longestLeanThresholdNanos >= MIN_LEAN_HOLD_NANOS &&
                window.peakGyroscopeMagnitude >= MIN_LEAN_GYROSCOPE_DPS -> Recognition(
                GestureType.LEAN,
                (window.peakGravityAngleDegrees / STRONG_LEAN_DEGREES).coerceIn(0f, 1f),
                window.peakGravityAngleDegrees,
            )

            window.isFlatSlide() -> Recognition(
                GestureType.SLIDE,
                (window.peakHorizontalAccelerationG / STRONG_SLIDE_ACCELERATION_G).coerceIn(0f, 1f),
                window.peakHorizontalAccelerationG,
            )

            else -> null
        }
        lastCapturedFeatures = featureExtractor.extract(window.capturedSamples).features
        val personalized = lastCapturedFeatures?.let { personalizedClassifier.classify(it, personalizedModel) }
        lastPersonalizedRecognition = personalized
        val recognition = resolveRecognition(deterministic, personalized, personalizedModel) ?: return null

        val event = emit(
            recognition.type,
            timestampNanos,
            recognition.confidence,
            recognition.magnitude,
            thresholds,
        )
        if (recognition.type == GestureType.FLIP) {
            // Flip is a state-changing STOP gesture. Consume the physical return to the
            // original side instead of interpreting it as another flip or a 180° lean.
            awaitingFlipReturn = true
            flipReturnReference = armedGravityReference
        }
        return event
    }

    private fun resolveRecognition(
        deterministic: Recognition?,
        personalized: PersonalizedRecognition?,
        model: PersonalizedGestureModel,
    ): Recognition? {
        if (!model.enabled || model.samples.isEmpty()) return deterministic
        if (personalized != null) {
            if (deterministic?.type == personalized.gesture) {
                return deterministic.copy(confidence = max(deterministic.confidence, personalized.confidence))
            }
            val mature = personalized.trainedSampleCount >= MIN_PERSONALIZED_SAMPLES_PER_GESTURE
            val requiredConfidence = if (deterministic == null) {
                PERSONALIZED_ONLY_MIN_CONFIDENCE
            } else {
                PERSONALIZED_OVERRIDE_MIN_CONFIDENCE
            }
            // A learned result may supplement the invariant physical recognizers.
            // The physical gate prevents a numerically similar rest/noise sample
            // from taking over a media action.
            if (mature && personalized.confidence >= requiredConfidence) {
                return Recognition(
                    type = personalized.gesture,
                    confidence = personalized.confidence,
                    magnitude = (1f - personalized.distance).coerceIn(0f, 1f),
                )
            }
        }
        // Personalization may add or strengthen a recognition, but it must not
        // veto a physically valid base gesture. A poor or mislabeled training
        // sample must never disable controls that worked before training.
        return deterministic
    }

    private fun emit(
        type: GestureType,
        timestampNanos: Long,
        confidence: Float,
        magnitude: Float,
        thresholds: GestureThresholds,
    ): GestureEvent? {
        val last = lastEmittedAt[type]
        val cooldownNanos = thresholds.cooldownMillis * NANOS_PER_MILLISECOND
        if (last != null && timestampNanos - last < cooldownNanos) return null
        lastEmittedAt[type] = timestampNanos
        return GestureEvent(type, timestampNanos, confidence.coerceIn(0f, 1f), magnitude)
    }

    private fun resetAfterMotion(sample: FilteredSensorData, stable: Boolean) {
        motionWindow = null
        stableSinceNanos = if (stable) sample.source.timestampNanos else null
        stableHistory.clear()
        if (stable) rememberStableSample(sample)
        phase = Phase.WARMING_UP
    }

    private fun processFlipReturn(
        sample: FilteredSensorData,
        now: Long,
        stable: Boolean,
    ): List<GestureEvent> {
        val reference = flipReturnReference ?: armedGravityReference
        val returned = vectorAngleDegrees(sample.accelerometerG, reference) <= FLIP_RETURN_ANGLE_DEGREES
        if (stable && returned) {
            rememberStableSample(sample)
            val stableSince = stableSinceNanos ?: now.also { stableSinceNanos = it }
            if (now - stableSince >= ARMING_STABLE_NANOS) {
                armedGravityReference = averageGravity(stableHistory.toList())
                awaitingFlipReturn = false
                flipReturnReference = null
                stableSinceNanos = null
                motionWindow = null
                phase = Phase.READY
            }
        } else {
            stableSinceNanos = null
            stableHistory.clear()
        }
        return emptyList()
    }

    private fun initializeStream(sample: FilteredSensorData) {
        phase = Phase.WARMING_UP
        stableSinceNanos = sample.source.timestampNanos
        lastTimestampNanos = sample.source.timestampNanos
        previousAccelerometer = sample.accelerometerG
        armedGravityReference = normalizedOrDefault(sample.accelerometerG)
        awaitingFlipReturn = false
        flipReturnReference = null
        stableHistory.clear()
        rememberStableSample(sample)
        motionWindow = null
    }

    private fun resetStreamState() {
        phase = Phase.WARMING_UP
        stableSinceNanos = null
        lastTimestampNanos = null
        previousAccelerometer = null
        armedGravityReference = Vector3(0f, 0f, 1f)
        awaitingFlipReturn = false
        flipReturnReference = null
        stableHistory.clear()
        motionWindow = null
    }

    private fun rememberStableSample(sample: FilteredSensorData) {
        if (stableHistory.lastOrNull()?.source?.timestampNanos == sample.source.timestampNanos) return
        if (stableHistory.size == STABLE_HISTORY_SAMPLES) stableHistory.removeFirst()
        stableHistory.addLast(sample)
    }

    private fun isStable(sample: FilteredSensorData, gravityDirectionRateDps: Float, accelerationDelta: Float): Boolean =
        sample.gyroscopeMagnitude <= REST_GYROSCOPE_MAX_DPS &&
            abs(sample.accelerationMagnitude - 1f) <= REST_ACCELERATION_DELTA_G &&
            accelerationDelta <= REST_ACCELERATION_STEP_G &&
            gravityDirectionRateDps <= REST_GRAVITY_DIRECTION_RATE_DPS

    private fun isMoving(sample: FilteredSensorData, gravityDirectionRateDps: Float, accelerationDelta: Float): Boolean =
        sample.gyroscopeMagnitude >= MOTION_START_GYROSCOPE_DPS ||
            abs(sample.accelerationMagnitude - 1f) >= MOTION_START_ACCELERATION_DELTA_G ||
            abs(sample.source.accelerometerG.magnitude - 1f) >= MOTION_START_RAW_ACCELERATION_DELTA_G ||
            accelerationDelta >= MOTION_START_ACCELERATION_STEP_G ||
            gravityDirectionRateDps >= MOTION_START_GRAVITY_DIRECTION_RATE_DPS

    private fun hasGravityRelativeMotion(sample: FilteredSensorData): Boolean {
        val filteredGravityAngle = vectorAngleDegrees(sample.accelerometerG, armedGravityReference)
        val rawAcceleration = sample.source.accelerometerG
        val rawProjection = dot(rawAcceleration, armedGravityReference)
        val projectedGravity = Vector3(
            armedGravityReference.x * rawProjection,
            armedGravityReference.y * rawProjection,
            armedGravityReference.z * rawProjection,
        )
        val horizontalAcceleration = (rawAcceleration - projectedGravity).magnitude
        return filteredGravityAngle >= MOTION_START_GRAVITY_ANGLE_DEGREES ||
            horizontalAcceleration >= MOTION_START_HORIZONTAL_ACCELERATION_G
    }

    private fun gravityDirectionRate(previous: Vector3, current: Vector3, dtSeconds: Float): Float =
        vectorAngleDegrees(previous, current) / dtSeconds.coerceAtLeast(0.001f)

    private fun vectorAngleDegrees(first: Vector3, second: Vector3): Float {
        val firstMagnitude = first.magnitude
        val secondMagnitude = second.magnitude
        if (
            firstMagnitude !in RELIABLE_GRAVITY_MIN_G..RELIABLE_GRAVITY_MAX_G ||
            secondMagnitude !in RELIABLE_GRAVITY_MIN_G..RELIABLE_GRAVITY_MAX_G
        ) {
            return 0f
        }
        val cosine = (dot(first, second) / (firstMagnitude * secondMagnitude)).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cosine).toDouble()).toFloat()
    }

    private fun averageGravity(samples: List<FilteredSensorData>): Vector3 {
        val reliable = samples.mapNotNull { sample ->
            sample.accelerometerG.takeIf { it.magnitude in RELIABLE_GRAVITY_MIN_G..RELIABLE_GRAVITY_MAX_G }
        }
        if (reliable.isEmpty()) return armedGravityReference
        val average = Vector3(
            reliable.sumOf { it.x.toDouble() }.toFloat() / reliable.size,
            reliable.sumOf { it.y.toDouble() }.toFloat() / reliable.size,
            reliable.sumOf { it.z.toDouble() }.toFloat() / reliable.size,
        )
        return normalizedOrDefault(average)
    }

    private fun normalizedOrDefault(vector: Vector3): Vector3 {
        val magnitude = vector.magnitude
        return if (magnitude > 0.1f) {
            Vector3(vector.x / magnitude, vector.y / magnitude, vector.z / magnitude)
        } else {
            Vector3(0f, 0f, 1f)
        }
    }

    private fun dot(first: Vector3, second: Vector3): Float =
        first.x * second.x + first.y * second.y + first.z * second.z

    private inner class MotionWindow(
        val startedAtNanos: Long,
        baselineSamples: List<FilteredSensorData>,
        gravityReferenceSnapshot: Vector3,
    ) {
        val capturedSamples = ArrayList<FilteredSensorData>(256)
        private val gravityReference = normalizedOrDefault(gravityReferenceSnapshot)
        var peakGravityAngleDegrees = 0f
            private set
        var longestLeanThresholdNanos = 0L
            private set
        private var currentGravityAngleDegrees = 0f
        var peakHorizontalAccelerationG = 0f
            private set
        private var currentHorizontalAccelerationG = 0f
        private var longestHorizontalAccelerationNanos = 0L
        var integratedTwistDegrees = 0f
            private set
        var peakTwistDps = 0f
            private set
        var peakTwistDominance = 0f
            private set
        private var currentTwistDps = 0f
        var peakGyroscopeMagnitude = 0f
            private set
        private var currentGyroscopeMagnitude = 0f
        var peakAccelerationMagnitude = 0f
            private set
        var peakRawAccelerationMagnitude = 0f
            private set
        var peakVerticalImpactDeltaG = 0f
            private set
        var freeFallNanos = 0L
            private set
        var impactAfterFreeFall = false
            private set
        var longestUpsideDownNanos = 0L
            private set
        var finalGravityProjection = 1f
            private set
        var shakeCycles = 0
            private set

        private var currentFreeFallNanos = 0L
        private var confirmedFreeFall = false
        private var currentLeanThresholdNanos = 0L
        private var currentHorizontalAccelerationNanos = 0L
        private var currentUpsideDownNanos = 0L
        private var shakeInitialVector: Vector3? = null
        private var shakeInitialMagnitude = 0f
        private var shakeLatched = false
        private var shakeInactiveNanos = 0L
        private var shakeActiveSamples = 0
        private var tapCandidateAgeNanos = 0L
        var emittedDuringMotion = false

        init {
            capturedSamples.addAll(baselineSamples.takeLast(STABLE_HISTORY_SAMPLES))
        }

        fun add(
            sample: FilteredSensorData,
            dtNanos: Long,
            thresholds: GestureThresholds,
        ) {
            if (capturedSamples.size < MAX_CAPTURED_MOTION_SAMPLES) capturedSamples.add(sample)
            val dtSeconds = dtNanos / NANOS_PER_SECOND_F
            val twistDps = dot(sample.gyroscopeDps, gravityReference)
            currentTwistDps = twistDps
            integratedTwistDegrees += twistDps * dtSeconds
            peakTwistDps = max(peakTwistDps, abs(twistDps))
            peakGyroscopeMagnitude = max(peakGyroscopeMagnitude, sample.gyroscopeMagnitude)
            currentGyroscopeMagnitude = sample.gyroscopeMagnitude
            if (sample.gyroscopeMagnitude > 0.001f) {
                peakTwistDominance = max(peakTwistDominance, abs(twistDps) / sample.gyroscopeMagnitude)
            }
            peakAccelerationMagnitude = max(peakAccelerationMagnitude, sample.accelerationMagnitude)
            val rawAccelerationMagnitude = sample.source.accelerometerG.magnitude
            peakRawAccelerationMagnitude = max(peakRawAccelerationMagnitude, rawAccelerationMagnitude)
            val gravityProjection = dot(sample.accelerometerG, gravityReference)
            finalGravityProjection = gravityProjection
            val rawGravityProjection = dot(sample.source.accelerometerG, gravityReference)
            currentGravityAngleDegrees = gravityAngleDegrees(sample.accelerometerG)
            peakGravityAngleDegrees = max(peakGravityAngleDegrees, currentGravityAngleDegrees)
            currentLeanThresholdNanos = if (currentGravityAngleDegrees >= thresholds.tiltDegrees) {
                currentLeanThresholdNanos + dtNanos
            } else {
                0L
            }
            longestLeanThresholdNanos = max(longestLeanThresholdNanos, currentLeanThresholdNanos)

            val rawAcceleration = sample.source.accelerometerG
            val horizontalAcceleration = Vector3(
                rawAcceleration.x - gravityReference.x * rawGravityProjection,
                rawAcceleration.y - gravityReference.y * rawGravityProjection,
                rawAcceleration.z - gravityReference.z * rawGravityProjection,
            ).magnitude
            currentHorizontalAccelerationG = horizontalAcceleration
            peakHorizontalAccelerationG = max(peakHorizontalAccelerationG, horizontalAcceleration)
            currentHorizontalAccelerationNanos = if (horizontalAcceleration >= MIN_SLIDE_ACCELERATION_G) {
                currentHorizontalAccelerationNanos + dtNanos
            } else {
                0L
            }
            longestHorizontalAccelerationNanos = max(
                longestHorizontalAccelerationNanos,
                currentHorizontalAccelerationNanos,
            )
            peakVerticalImpactDeltaG = max(peakVerticalImpactDeltaG, abs(abs(rawGravityProjection) - 1f))
            tapCandidateAgeNanos = if (
                rawAccelerationMagnitude >= thresholds.impactG &&
                peakVerticalImpactDeltaG >= MIN_TAP_VERTICAL_DELTA_G
            ) {
                max(tapCandidateAgeNanos, dtNanos)
            } else if (tapCandidateAgeNanos > 0L) {
                tapCandidateAgeNanos + dtNanos
            } else {
                0L
            }

            if (rawAccelerationMagnitude < thresholds.freeFallG) {
                currentFreeFallNanos += dtNanos
                freeFallNanos = max(freeFallNanos, currentFreeFallNanos)
                if (currentFreeFallNanos >= MIN_FREE_FALL_NANOS) confirmedFreeFall = true
            } else {
                if (confirmedFreeFall && rawAccelerationMagnitude >= thresholds.impactG) {
                    impactAfterFreeFall = true
                }
                currentFreeFallNanos = 0L
            }

            val upsideDown = gravityProjection < FLIP_Z_THRESHOLD &&
                sample.accelerationMagnitude in FLIP_GRAVITY_MIN_G..FLIP_GRAVITY_MAX_G
            currentUpsideDownNanos = if (upsideDown) currentUpsideDownNanos + dtNanos else 0L
            longestUpsideDownNanos = max(longestUpsideDownNanos, currentUpsideDownNanos)

            updateShake(sample, dtNanos, thresholds)
        }

        fun liveRecognition(thresholds: GestureThresholds): Recognition? = when {
            isCleanTap(thresholds) && tapCandidateAgeNanos >= TAP_CONFIRM_NANOS -> Recognition(
                GestureType.TAP,
                (peakRawAccelerationMagnitude / (thresholds.impactG * 1.7f)).coerceIn(0f, 1f),
                peakRawAccelerationMagnitude,
            )

            abs(integratedTwistDegrees) >= LIVE_ROTATION_DEGREES &&
                peakTwistDps >= thresholds.rotationDps &&
                peakTwistDominance >= MIN_TWIST_DOMINANCE &&
                abs(currentTwistDps) <= max(MIN_ROTATION_RELEASE_DPS, thresholds.rotationDps * ROTATION_RELEASE_RATIO) -> Recognition(
                if (integratedTwistDegrees > 0f) GestureType.ROTATE_RIGHT else GestureType.ROTATE_LEFT,
                (abs(integratedTwistDegrees) / STRONG_ROTATION_DEGREES).coerceIn(0f, 1f),
                abs(integratedTwistDegrees),
            )

            peakGravityAngleDegrees >= thresholds.tiltDegrees &&
                longestLeanThresholdNanos >= LIVE_LEAN_HOLD_NANOS &&
                peakGyroscopeMagnitude >= MIN_LEAN_GYROSCOPE_DPS &&
                currentGyroscopeMagnitude <= MAX_LIVE_LEAN_GYROSCOPE_DPS &&
                currentGravityAngleDegrees >= thresholds.tiltReleaseDegrees &&
                finalGravityProjection >= MIN_LIVE_LEAN_GRAVITY_PROJECTION -> Recognition(
                GestureType.LEAN,
                (peakGravityAngleDegrees / STRONG_LEAN_DEGREES).coerceIn(0f, 1f),
                peakGravityAngleDegrees,
            )

            isFlatSlide() && currentHorizontalAccelerationG <= SLIDE_RELEASE_ACCELERATION_G -> Recognition(
                GestureType.SLIDE,
                (peakHorizontalAccelerationG / STRONG_SLIDE_ACCELERATION_G).coerceIn(0f, 1f),
                peakHorizontalAccelerationG,
            )

            else -> null
        }

        fun isCleanTap(thresholds: GestureThresholds): Boolean =
            peakRawAccelerationMagnitude >= thresholds.impactG &&
                peakVerticalImpactDeltaG >= MIN_TAP_VERTICAL_DELTA_G &&
                peakGyroscopeMagnitude <= MAX_TAP_GYROSCOPE_DPS &&
                peakGravityAngleDegrees <= MAX_TAP_TILT_DEGREES

        fun isFlatSlide(): Boolean =
            peakHorizontalAccelerationG >= MIN_SLIDE_ACCELERATION_G &&
                longestHorizontalAccelerationNanos >= MIN_SLIDE_ACTIVE_NANOS &&
                peakGravityAngleDegrees <= MAX_SLIDE_GRAVITY_ANGLE_DEGREES &&
                peakGyroscopeMagnitude <= MAX_SLIDE_GYROSCOPE_DPS &&
                peakVerticalImpactDeltaG <= MAX_SLIDE_VERTICAL_IMPACT_G

        private fun gravityAngleDegrees(acceleration: Vector3): Float {
            val magnitude = acceleration.magnitude
            if (magnitude !in RELIABLE_GRAVITY_MIN_G..RELIABLE_GRAVITY_MAX_G) {
                return currentGravityAngleDegrees
            }
            val normalizedDot = (dot(acceleration, gravityReference) / magnitude).coerceIn(-1f, 1f)
            return Math.toDegrees(acos(normalizedDot).toDouble()).toFloat()
        }

        private fun updateShake(sample: FilteredSensorData, dtNanos: Long, thresholds: GestureThresholds) {
            val accelerationDelta = abs(sample.accelerationMagnitude - 1f)
            val active = sample.gyroscopeMagnitude >= thresholds.shakeDps &&
                accelerationDelta >= SHAKE_ACCELERATION_DELTA_G
            if (!active) {
                shakeInactiveNanos += dtNanos
                if (shakeInactiveNanos >= SHAKE_RELEASE_NANOS) {
                    shakeInitialVector = null
                    shakeInitialMagnitude = 0f
                    shakeLatched = false
                    shakeActiveSamples = 0
                }
                return
            }

            shakeInactiveNanos = 0L
            val initial = shakeInitialVector
            if (initial == null) {
                shakeInitialVector = sample.gyroscopeDps
                shakeInitialMagnitude = sample.gyroscopeMagnitude
                shakeActiveSamples = 1
                return
            }

            shakeActiveSamples++
            if (shakeLatched || shakeActiveSamples < SHAKE_MIN_ACTIVE_SAMPLES) return
            val denominator = shakeInitialMagnitude * sample.gyroscopeMagnitude
            if (denominator <= 0.0001f) return
            val normalizedDot = dot(initial, sample.gyroscopeDps) / denominator
            if (normalizedDot <= SHAKE_REVERSAL_DOT_THRESHOLD) {
                shakeCycles++
                shakeLatched = true
            }
        }

        private fun dot(first: Vector3, second: Vector3): Float =
            first.x * second.x + first.y * second.y + first.z * second.z
    }

    private data class Recognition(
        val type: GestureType,
        val confidence: Float,
        val magnitude: Float,
    )

    private enum class Phase { WARMING_UP, READY, RECORDING }

    private companion object {
        const val NANOS_PER_SECOND_F = 1_000_000_000f
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val MAX_SAMPLE_GAP_NANOS = 250_000_000L
        const val ARMING_STABLE_NANOS = 280_000_000L
        const val MOTION_END_STABLE_NANOS = 280_000_000L
        const val SINGLE_SHAKE_END_STABLE_NANOS = 480_000_000L
        const val MAX_MOTION_WINDOW_NANOS = 4_000_000_000L
        const val REST_GYROSCOPE_MAX_DPS = 22f
        const val REST_ACCELERATION_DELTA_G = 0.11f
        const val REST_ACCELERATION_STEP_G = 0.045f
        const val REST_GRAVITY_DIRECTION_RATE_DPS = 16f
        const val MOTION_START_GYROSCOPE_DPS = 28f
        const val MOTION_START_ACCELERATION_DELTA_G = 0.15f
        const val MOTION_START_RAW_ACCELERATION_DELTA_G = 0.2f
        const val MOTION_START_ACCELERATION_STEP_G = 0.075f
        const val MOTION_START_GRAVITY_DIRECTION_RATE_DPS = 28f
        const val MOTION_START_GRAVITY_ANGLE_DEGREES = 4f
        const val MOTION_START_HORIZONTAL_ACCELERATION_G = 0.1f

        const val MIN_LEAN_GYROSCOPE_DPS = 28f
        const val MIN_LEAN_HOLD_NANOS = 40_000_000L
        const val MIN_RECORDED_MOTION_NANOS = 80_000_000L
        const val STRONG_LEAN_DEGREES = 55f
        const val MIN_ROTATION_DEGREES = 7f
        const val LIVE_ROTATION_DEGREES = 7f
        const val STRONG_ROTATION_DEGREES = 70f
        const val MIN_TWIST_DOMINANCE = 0.48f
        const val MIN_ROTATION_RELEASE_DPS = 18f
        const val ROTATION_RELEASE_RATIO = 0.55f
        const val MIN_FREE_FALL_NANOS = 35_000_000L
        const val MIN_FLIP_HOLD_NANOS = 80_000_000L
        const val MIN_FLIP_GYROSCOPE_DPS = 55f
        const val FLIP_Z_THRESHOLD = -0.72f
        const val FLIP_FINAL_Z_THRESHOLD = -0.55f
        const val FLIP_GRAVITY_MIN_G = 0.65f
        const val FLIP_GRAVITY_MAX_G = 1.4f
        const val FLIP_RETURN_ANGLE_DEGREES = 20f
        const val MIN_TAP_VERTICAL_DELTA_G = 0.18f
        const val MAX_TAP_GYROSCOPE_DPS = 95f
        const val MAX_TAP_TILT_DEGREES = 20f
        const val TAP_CONFIRM_NANOS = 55_000_000L
        const val LIVE_LEAN_HOLD_NANOS = 75_000_000L
        const val MAX_LIVE_LEAN_GYROSCOPE_DPS = 55f
        const val MIN_LIVE_LEAN_GRAVITY_PROJECTION = 0.25f
        const val MIN_SLIDE_ACCELERATION_G = 0.14f
        const val SLIDE_RELEASE_ACCELERATION_G = 0.075f
        const val STRONG_SLIDE_ACCELERATION_G = 0.42f
        const val MIN_SLIDE_ACTIVE_NANOS = 35_000_000L
        const val MAX_SLIDE_GRAVITY_ANGLE_DEGREES = 20f
        const val MAX_SLIDE_GYROSCOPE_DPS = 80f
        const val MAX_SLIDE_VERTICAL_IMPACT_G = 0.16f
        const val RELIABLE_GRAVITY_MIN_G = 0.65f
        const val RELIABLE_GRAVITY_MAX_G = 1.4f
        const val SHAKE_ACCELERATION_DELTA_G = 0.16f
        const val SHAKE_RELEASE_NANOS = 140_000_000L
        const val SHAKE_MIN_ACTIVE_SAMPLES = 4
        const val SHAKE_REVERSAL_DOT_THRESHOLD = -0.35f
        const val MAX_CAPTURED_MOTION_SAMPLES = 512
        const val STABLE_HISTORY_SAMPLES = 12
        const val PERSONALIZED_ONLY_MIN_CONFIDENCE = 0.7f
        const val PERSONALIZED_OVERRIDE_MIN_CONFIDENCE = 0.76f
    }
}
