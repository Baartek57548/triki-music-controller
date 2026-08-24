package pl.trikimusic.controller.core.gesture

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.sqrt
import pl.trikimusic.controller.domain.model.CalibrationProfile
import pl.trikimusic.controller.domain.model.FilteredSensorData
import pl.trikimusic.controller.domain.model.GestureThresholds
import pl.trikimusic.controller.domain.model.OrientationData
import pl.trikimusic.controller.domain.model.TrikiSensorData
import pl.trikimusic.controller.domain.model.Vector3

class SensorFilter {
    private var filteredGyroscope: Vector3? = null
    private var filteredAccelerometer: Vector3? = null
    private var orientation = OrientationData()
    private var previousTimestampNanos: Long? = null

    fun reset() {
        filteredGyroscope = null
        filteredAccelerometer = null
        orientation = OrientationData()
        previousTimestampNanos = null
    }

    fun process(
        sample: TrikiSensorData,
        calibration: CalibrationProfile,
        thresholds: GestureThresholds,
    ): FilteredSensorData {
        val calibratedGyroscope = sample.gyroscopeDps - Vector3(
            calibration.gyroscopeBiasX,
            calibration.gyroscopeBiasY,
            calibration.gyroscopeBiasZ,
        )
        val calibratedAccelerometer = sample.accelerometerG - Vector3(
            calibration.accelerometerBiasX,
            calibration.accelerometerBiasY,
            calibration.accelerometerBiasZ,
        )
        val alpha = thresholds.filterAlpha
        filteredGyroscope = lowPass(filteredGyroscope, calibratedGyroscope, alpha)
        filteredAccelerometer = lowPass(filteredAccelerometer, calibratedAccelerometer, alpha)

        val dt = previousTimestampNanos?.let { previous ->
            ((sample.timestampNanos - previous).coerceIn(MIN_DT_NANOS, MAX_DT_NANOS) / 1_000_000_000f)
        } ?: DEFAULT_DT_SECONDS
        previousTimestampNanos = sample.timestampNanos
        orientation = updateOrientation(
            orientation,
            requireNotNull(filteredGyroscope),
            requireNotNull(filteredAccelerometer),
            calibration,
            dt,
        )
        return FilteredSensorData(
            source = sample,
            gyroscopeDps = requireNotNull(filteredGyroscope),
            accelerometerG = requireNotNull(filteredAccelerometer),
            orientation = orientation,
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

    private fun updateOrientation(
        previous: OrientationData,
        gyroscope: Vector3,
        accelerometer: Vector3,
        calibration: CalibrationProfile,
        dt: Float,
    ): OrientationData {
        val accelNorm = maxOf(accelerometer.magnitude, 0.0001f)
        val accelPitch = Math.toDegrees(
            atan2(
                -accelerometer.x.toDouble(),
                sqrt((accelerometer.y * accelerometer.y + accelerometer.z * accelerometer.z).toDouble()),
            ),
        ).toFloat() - calibration.neutralPitch
        val accelRoll = Math.toDegrees(atan2(accelerometer.y.toDouble(), accelerometer.z.toDouble())).toFloat() - calibration.neutralRoll
        val accelReliable = accelNorm in RELIABLE_ACCEL_MIN..RELIABLE_ACCEL_MAX
        val gyroPitch = previous.pitch + gyroscope.y * dt
        val gyroRoll = previous.roll - gyroscope.x * dt
        val pitch = if (accelReliable) COMPLEMENTARY_ALPHA * gyroPitch + (1f - COMPLEMENTARY_ALPHA) * accelPitch else gyroPitch
        val roll = if (accelReliable) COMPLEMENTARY_ALPHA * gyroRoll + (1f - COMPLEMENTARY_ALPHA) * accelRoll else gyroRoll
        val yaw = normalizeDegrees(previous.yaw - gyroscope.z * dt)
        return OrientationData(
            pitch = normalizeDegrees(pitch),
            roll = normalizeDegrees(roll),
            yaw = yaw,
        )
    }

    private fun normalizeDegrees(value: Float): Float {
        var normalized = value
        while (normalized > 180f) normalized -= 360f
        while (normalized < -180f) normalized += 360f
        return normalized
    }

    private companion object {
        const val COMPLEMENTARY_ALPHA = 0.96f
        const val RELIABLE_ACCEL_MIN = 0.72f
        const val RELIABLE_ACCEL_MAX = 1.28f
        const val DEFAULT_DT_SECONDS = 0.0096f
        const val MIN_DT_NANOS = 1_000_000L
        const val MAX_DT_NANOS = 100_000_000L
    }
}
