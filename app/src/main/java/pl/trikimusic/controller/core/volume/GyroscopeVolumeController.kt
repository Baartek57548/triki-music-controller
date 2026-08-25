package pl.trikimusic.controller.core.volume

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import pl.trikimusic.controller.domain.model.FilteredSensorData
import pl.trikimusic.controller.domain.model.MediaAction

data class VolumeControlResult(
    val action: MediaAction? = null,
    val accelerometerWithinTolerance: Boolean,
    val levelOrientation: Boolean,
    val stillEnoughToArm: Boolean,
    val armed: Boolean,
    val armingProgress: Float,
    val tiltDegrees: Float,
    val gyroscopeZDps: Float,
)

/**
 * Turns rotation around the local Z axis into discrete Android volume steps.
 *
 * Volume control is armed only while the cap is resting top-side up, close to level, with both
 * linear acceleration and angular velocity inside conservative limits. Once armed, rotation
 * around Z is allowed, but off-axis rotation or loss of the level pose disarms the controller.
 */
class GyroscopeVolumeController(
    private val configuration: Configuration = Configuration(),
) {
    data class Configuration(
        val minimumStationaryAccelerationG: Float = 0.8f,
        val maximumStationaryAccelerationG: Float = 1.2f,
        val stationaryArmingMillis: Long = 900L,
        val maximumArmingGyroscopeDps: Float = 5f,
        val maximumOffAxisGyroscopeDps: Float = 22f,
        val maximumLevelTiltDegrees: Float = 25f,
        val levelReleaseTiltDegrees: Float = 32f,
        val activationGyroscopeDps: Float = 18f,
        val releaseGyroscopeDps: Float = 10f,
        val degreesPerVolumeStep: Float = 15f,
    ) {
        init {
            require(minimumStationaryAccelerationG.isFinite() && minimumStationaryAccelerationG > 0f)
            require(maximumStationaryAccelerationG.isFinite() && maximumStationaryAccelerationG > minimumStationaryAccelerationG)
            require(stationaryArmingMillis in 0L..5_000L)
            require(maximumArmingGyroscopeDps.isFinite() && maximumArmingGyroscopeDps > 0f)
            require(
                maximumOffAxisGyroscopeDps.isFinite() &&
                    maximumOffAxisGyroscopeDps > maximumArmingGyroscopeDps,
            )
            require(maximumLevelTiltDegrees.isFinite() && maximumLevelTiltDegrees in 0f..45f)
            require(
                levelReleaseTiltDegrees.isFinite() &&
                    levelReleaseTiltDegrees in maximumLevelTiltDegrees..60f,
            )
            require(activationGyroscopeDps.isFinite() && activationGyroscopeDps > 0f)
            require(releaseGyroscopeDps.isFinite() && releaseGyroscopeDps in 0f..activationGyroscopeDps)
            require(degreesPerVolumeStep.isFinite() && degreesPerVolumeStep > 0f)
        }
    }

    private var armingSinceNanos: Long? = null
    private var previousTimestampNanos: Long? = null
    private var accumulatedRotationDegrees = 0f
    private var activeDirection = 0
    private var armed = false

    fun reset() {
        resetArming()
        previousTimestampNanos = null
    }

    fun process(sample: FilteredSensorData): VolumeControlResult {
        val timestampNanos = sample.source.timestampNanos
        val previousTimestamp = previousTimestampNanos
        if (
            previousTimestamp != null &&
            (timestampNanos <= previousTimestamp || timestampNanos - previousTimestamp > MAX_STREAM_GAP_NANOS)
        ) {
            resetArming()
        }
        val accelerationMagnitude = sample.accelerationMagnitude
        val gyroscopeMagnitude = sample.gyroscopeMagnitude
        val gyroscopeZ = sample.gyroscopeDps.z
        val accelerometerIsFinite = sample.accelerometerG.x.isFinite() &&
            sample.accelerometerG.y.isFinite() &&
            sample.accelerometerG.z.isFinite() &&
            accelerationMagnitude.isFinite()
        val gyroscopeIsFinite = sample.gyroscopeDps.x.isFinite() &&
            sample.gyroscopeDps.y.isFinite() &&
            gyroscopeZ.isFinite() &&
            gyroscopeMagnitude.isFinite()
        val accelerationWithinTolerance = accelerometerIsFinite &&
            accelerationMagnitude in configuration.minimumStationaryAccelerationG..configuration.maximumStationaryAccelerationG
        val tiltDegrees = calculateTiltDegrees(sample.accelerometerG.z, accelerationMagnitude)
        val allowedTiltDegrees = if (armed) {
            configuration.levelReleaseTiltDegrees
        } else {
            configuration.maximumLevelTiltDegrees
        }
        val levelOrientation = accelerometerIsFinite && tiltDegrees <= allowedTiltDegrees
        val offAxisGyroscopeDps = hypot(sample.gyroscopeDps.x, sample.gyroscopeDps.y)
        val offAxisMotionWithinTolerance = gyroscopeIsFinite &&
            offAxisGyroscopeDps <= configuration.maximumOffAxisGyroscopeDps
        val stillEnoughToArm = accelerationWithinTolerance &&
            levelOrientation &&
            gyroscopeIsFinite &&
            gyroscopeMagnitude <= configuration.maximumArmingGyroscopeDps

        val deltaSeconds = calculateDeltaSeconds(timestampNanos)
        if (!accelerationWithinTolerance || !levelOrientation || !offAxisMotionWithinTolerance) {
            resetArming()
            return result(
                accelerationWithinTolerance = accelerationWithinTolerance,
                levelOrientation = levelOrientation,
                stillEnoughToArm = false,
                armingProgress = 0f,
                tiltDegrees = tiltDegrees,
                gyroscopeZ = gyroscopeZ,
            )
        }

        if (!armed) {
            if (!stillEnoughToArm) {
                armingSinceNanos = null
                resetRotation()
                return result(
                    accelerationWithinTolerance = true,
                    levelOrientation = true,
                    stillEnoughToArm = false,
                    armingProgress = 0f,
                    tiltDegrees = tiltDegrees,
                    gyroscopeZ = gyroscopeZ,
                )
            }
            val armingStart = armingSinceNanos ?: timestampNanos.also { armingSinceNanos = it }
            val requiredNanos = configuration.stationaryArmingMillis * NANOS_PER_MILLISECOND
            val elapsedNanos = (timestampNanos - armingStart).coerceAtLeast(0L)
            val armingProgress = if (requiredNanos == 0L) 1f else {
                (elapsedNanos.toDouble() / requiredNanos).toFloat().coerceIn(0f, 1f)
            }
            if (elapsedNanos >= requiredNanos) {
                armed = true
                armingSinceNanos = null
            }
            resetRotation()
            return result(
                accelerationWithinTolerance = true,
                levelOrientation = true,
                stillEnoughToArm = true,
                armingProgress = armingProgress,
                tiltDegrees = tiltDegrees,
                gyroscopeZ = gyroscopeZ,
            )
        }

        val absoluteGyroscopeZ = abs(gyroscopeZ)
        if (absoluteGyroscopeZ <= configuration.releaseGyroscopeDps) {
            resetRotation()
            return result(
                accelerationWithinTolerance = true,
                levelOrientation = true,
                stillEnoughToArm = stillEnoughToArm,
                armingProgress = 1f,
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
                accelerationWithinTolerance = true,
                levelOrientation = true,
                stillEnoughToArm = stillEnoughToArm,
                armingProgress = 1f,
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
            accelerationWithinTolerance = true,
            levelOrientation = true,
            stillEnoughToArm = stillEnoughToArm,
            armingProgress = 1f,
            tiltDegrees = tiltDegrees,
            gyroscopeZ = gyroscopeZ,
        )
    }

    private fun result(
        action: MediaAction? = null,
        accelerationWithinTolerance: Boolean,
        levelOrientation: Boolean,
        stillEnoughToArm: Boolean,
        armingProgress: Float,
        tiltDegrees: Float,
        gyroscopeZ: Float,
    ) = VolumeControlResult(
        action = action,
        accelerometerWithinTolerance = accelerationWithinTolerance,
        levelOrientation = levelOrientation,
        stillEnoughToArm = stillEnoughToArm,
        armed = armed,
        armingProgress = armingProgress,
        tiltDegrees = tiltDegrees.takeIf(Float::isFinite) ?: 180f,
        gyroscopeZDps = gyroscopeZ.takeIf(Float::isFinite) ?: 0f,
    )

    private fun calculateTiltDegrees(accelerometerZ: Float, accelerationMagnitude: Float): Float {
        if (!accelerometerZ.isFinite() || !accelerationMagnitude.isFinite() || accelerationMagnitude < MIN_VECTOR_MAGNITUDE) {
            return 180f
        }
        // Hardware captures show gravity near (0, 0, -1 g) when the cap rests face-up.
        // Negating Z makes 0° mean the safe face-up pose and 180° mean upside-down.
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

    private fun resetArming() {
        armingSinceNanos = null
        armed = false
        resetRotation()
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val NANOS_PER_SECOND = 1_000_000_000f
        const val MAX_SAMPLE_INTERVAL_NANOS = 100_000_000L
        const val MAX_STREAM_GAP_NANOS = 250_000_000L
        const val MIN_VECTOR_MAGNITUDE = 0.001f
    }
}
