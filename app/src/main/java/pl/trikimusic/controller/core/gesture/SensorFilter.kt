package pl.trikimusic.controller.core.gesture

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
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
    private val gyroscopeMedian = MedianOfThreeVectorFilter()
    private val accelerometerMedian = MedianOfThreeVectorFilter()

    fun reset() {
        filteredGyroscope = null
        filteredAccelerometer = null
        orientation = OrientationData()
        previousTimestampNanos = null
        gyroscopeMedian.reset()
        accelerometerMedian.reset()
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
        // Median-of-three rejects an isolated BLE/IMU spike before the low-pass stage can smear
        // it over multiple frames. The calibrated noise floor then removes residual gyro chatter.
        val medianGyroscope = gyroscopeMedian.process(calibratedGyroscope)
        val medianAccelerometer = accelerometerMedian.process(calibratedAccelerometer)
        val stabilizedGyroscope = applyGyroscopeNoiseFloor(
            medianGyroscope,
            max(MIN_GYROSCOPE_NOISE_FLOOR_DPS, calibration.gyroscopeNoise * GYROSCOPE_NOISE_MULTIPLIER),
        )
        val alpha = thresholds.filterAlpha
        filteredGyroscope = lowPass(filteredGyroscope, stabilizedGyroscope, alpha)
        filteredAccelerometer = lowPass(filteredAccelerometer, medianAccelerometer, alpha)

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

    private fun applyGyroscopeNoiseFloor(value: Vector3, noiseFloor: Float): Vector3 = Vector3(
        x = value.x.takeUnless { abs(it) <= noiseFloor } ?: 0f,
        y = value.y.takeUnless { abs(it) <= noiseFloor } ?: 0f,
        z = value.z.takeUnless { abs(it) <= noiseFloor } ?: 0f,
    )

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
        const val DEFAULT_DT_SECONDS = 0.0192f
        const val MIN_DT_NANOS = 1_000_000L
        const val MAX_DT_NANOS = 100_000_000L
        const val MIN_GYROSCOPE_NOISE_FLOOR_DPS = 2.5f
        const val GYROSCOPE_NOISE_MULTIPLIER = 2.8f
    }

    private class MedianOfThreeVectorFilter {
        private var older: Vector3? = null
        private var previous: Vector3? = null

        fun reset() {
            older = null
            previous = null
        }

        fun process(current: Vector3): Vector3 {
            val first = older
            val second = previous
            older = second
            previous = current
            if (first == null || second == null) return current
            return Vector3(
                x = median(first.x, second.x, current.x),
                y = median(first.y, second.y, current.y),
                z = median(first.z, second.z, current.z),
            )
        }

        private fun median(first: Float, second: Float, third: Float): Float =
            first + second + third - minOf(first, second, third) - maxOf(first, second, third)
    }
}
