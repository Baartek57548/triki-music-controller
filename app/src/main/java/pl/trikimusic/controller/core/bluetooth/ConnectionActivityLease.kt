package pl.trikimusic.controller.core.bluetooth

import kotlin.math.abs
import pl.trikimusic.controller.domain.model.FilteredSensorData

class ConnectionActivityLease(
    private val idleTimeoutNanos: Long = DEFAULT_IDLE_TIMEOUT_NANOS,
    private val accelerationDeltaThresholdG: Float = DEFAULT_ACCELERATION_DELTA_THRESHOLD_G,
    private val gyroscopeThresholdDps: Float = DEFAULT_GYROSCOPE_THRESHOLD_DPS,
) {
    private var lastActivityTimestampNanos: Long? = null
    private var parkingRequested = false

    init {
        require(idleTimeoutNanos > 0L)
        require(accelerationDeltaThresholdG > 0f && accelerationDeltaThresholdG.isFinite())
        require(gyroscopeThresholdDps > 0f && gyroscopeThresholdDps.isFinite())
    }

    fun observe(
        sample: FilteredSensorData,
        explicitActivity: Boolean,
    ): Boolean {
        val sensorActivity =
            abs(sample.accelerationMagnitude - 1f) >= accelerationDeltaThresholdG ||
                sample.gyroscopeMagnitude >= gyroscopeThresholdDps
        return observe(sample.source.timestampNanos, explicitActivity || sensorActivity)
    }

    fun observe(timestampNanos: Long, active: Boolean): Boolean {
        if (timestampNanos < 0L) {
            reset()
            return false
        }
        val previous = lastActivityTimestampNanos
        if (previous == null || timestampNanos < previous) {
            lastActivityTimestampNanos = timestampNanos
            parkingRequested = false
            return false
        }
        if (active) {
            lastActivityTimestampNanos = timestampNanos
            parkingRequested = false
            return false
        }
        if (!parkingRequested && timestampNanos - previous >= idleTimeoutNanos) {
            parkingRequested = true
            return true
        }
        return false
    }

    fun reset() {
        lastActivityTimestampNanos = null
        parkingRequested = false
    }

    companion object {
        const val DEFAULT_IDLE_TIMEOUT_NANOS = 12_000_000_000L
        const val DEFAULT_ACCELERATION_DELTA_THRESHOLD_G = 0.08f
        const val DEFAULT_GYROSCOPE_THRESHOLD_DPS = 5f
    }
}
