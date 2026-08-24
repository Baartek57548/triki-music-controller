package pl.trikimusic.controller.domain.model

import kotlin.math.sqrt

data class Vector3(
    val x: Float,
    val y: Float,
    val z: Float,
) {
    val magnitude: Float
        get() = sqrt(x * x + y * y + z * z)

    operator fun minus(other: Vector3): Vector3 = Vector3(x - other.x, y - other.y, z - other.z)
}

data class TrikiSensorData(
    val frameIndex: Long,
    val timestampNanos: Long,
    val gyroscopeDps: Vector3,
    val accelerometerG: Vector3,
    val rawGyroscope: RawVector3,
    val rawAccelerometer: RawVector3,
    val status: Int,
) {
    val buttonPressed: Boolean
        get() = status and 0x01 != 0
}

data class RawVector3(
    val x: Short,
    val y: Short,
    val z: Short,
)

data class OrientationData(
    val pitch: Float = 0f,
    val roll: Float = 0f,
    val yaw: Float = 0f,
)

data class FilteredSensorData(
    val source: TrikiSensorData,
    val gyroscopeDps: Vector3,
    val accelerometerG: Vector3,
    val orientation: OrientationData,
) {
    val accelerationMagnitude: Float
        get() = accelerometerG.magnitude

    val gyroscopeMagnitude: Float
        get() = gyroscopeDps.magnitude
}
