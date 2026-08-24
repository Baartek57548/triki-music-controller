package pl.trikimusic.controller.core.bluetooth

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import pl.trikimusic.controller.domain.model.RawVector3
import pl.trikimusic.controller.domain.model.TrikiSensorData
import pl.trikimusic.controller.domain.model.Vector3

object TrikiProtocol {
    val NUS_SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    val NUS_RX_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    val NUS_TX_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
    val LED_UUID: UUID = UUID.fromString("6e400004-b5a3-f393-e0a9-e50e24dcca9e")
    val BATTERY_SERVICE_UUID: UUID = standardUuid(0x180F)
    val BATTERY_LEVEL_UUID: UUID = standardUuid(0x2A19)
    val DEVICE_INFORMATION_SERVICE_UUID: UUID = standardUuid(0x180A)
    val MANUFACTURER_NAME_UUID: UUID = standardUuid(0x2A29)
    val MODEL_NUMBER_UUID: UUID = standardUuid(0x2A24)
    val SERIAL_NUMBER_UUID: UUID = standardUuid(0x2A25)
    val FIRMWARE_REVISION_UUID: UUID = standardUuid(0x2A26)
    val HARDWARE_REVISION_UUID: UUID = standardUuid(0x2A27)
    val SOFTWARE_REVISION_UUID: UUID = standardUuid(0x2A28)
    val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID = standardUuid(0x2902)

    val START_STREAM_COMMAND: ByteArray = byteArrayOf(
        0x20,
        0x10,
        0x00,
        0xD0.toByte(),
        0x07,
        0x68,
        0x00,
        0x03,
    )

    const val FRAME_LENGTH = 14
    const val FRAME_HEADER = 0x22
    const val GYROSCOPE_LSB_PER_DPS = 131f
    const val ACCELEROMETER_LSB_PER_G = 2048f

    private fun standardUuid(shortUuid: Int): UUID =
        UUID.fromString("0000%04x-0000-1000-8000-00805f9b34fb".format(shortUuid))
}

data class DecoderStatistics(
    val decodedFrames: Long = 0,
    val discardedStartupFrames: Long = 0,
    val droppedBytes: Long = 0,
)

class TrikiProtocolDecoder(
    private val startupFramesToDiscard: Int = DEFAULT_STARTUP_DISCARD,
    private val gyroscopeScale: Float = TrikiProtocol.GYROSCOPE_LSB_PER_DPS,
    private val accelerometerScale: Float = TrikiProtocol.ACCELEROMETER_LSB_PER_G,
) {
    init {
        require(startupFramesToDiscard >= 0)
        require(gyroscopeScale > 0f)
        require(accelerometerScale > 0f)
    }

    private val buffer = ArrayList<Byte>(64)
    private var frameIndex = 0L
    private var discarded = 0L
    private var dropped = 0L
    private var lastTimestampNanos = Long.MIN_VALUE

    val statistics: DecoderStatistics
        get() = DecoderStatistics(frameIndex, discarded, dropped)

    fun reset() {
        buffer.clear()
        frameIndex = 0L
        discarded = 0L
        dropped = 0L
        lastTimestampNanos = Long.MIN_VALUE
    }

    fun decode(notification: ByteArray, receivedAtNanos: Long): List<TrikiSensorData> {
        if (notification.isEmpty()) return emptyList()
        notification.forEach(buffer::add)
        val frames = mutableListOf<ByteArray>()

        while (true) {
            val headerIndex = findHeader()
            if (headerIndex < 0) {
                retainPossibleSplitHeader()
                break
            }
            if (headerIndex > 0) {
                dropped += headerIndex
                repeat(headerIndex) { buffer.removeAt(0) }
            }
            if (buffer.size < TrikiProtocol.FRAME_LENGTH) break

            val frame = ByteArray(TrikiProtocol.FRAME_LENGTH) { index -> buffer[index] }
            repeat(TrikiProtocol.FRAME_LENGTH) { buffer.removeAt(0) }
            frames += frame
        }

        if (frames.isEmpty()) return emptyList()
        val firstTimestamp = receivedAtNanos - (frames.lastIndex * APPROXIMATE_SAMPLE_PERIOD_NANOS)
        return frames.mapIndexedNotNull { index, frame ->
            if (discarded < startupFramesToDiscard) {
                discarded++
                null
            } else {
                val interpolated = firstTimestamp + index * APPROXIMATE_SAMPLE_PERIOD_NANOS
                val timestamp = if (lastTimestampNanos == Long.MIN_VALUE) {
                    interpolated
                } else {
                    maxOf(interpolated, lastTimestampNanos + MIN_MONOTONIC_STEP_NANOS)
                }
                lastTimestampNanos = timestamp
                decodeFrame(frame, timestamp, frameIndex++)
            }
        }
    }

    private fun decodeFrame(frame: ByteArray, timestampNanos: Long, index: Long): TrikiSensorData {
        require(frame.size == TrikiProtocol.FRAME_LENGTH)
        require(frame[0].toInt() and 0xFF == TrikiProtocol.FRAME_HEADER)
        val status = frame[1].toInt() and 0xFF
        require(status in VALID_STATUS_BYTES)

        val values = ByteBuffer.wrap(frame, 2, 12)
            .order(ByteOrder.LITTLE_ENDIAN)
        val gx = values.short
        val gy = values.short
        val gz = values.short
        val ax = values.short
        val ay = values.short
        val az = values.short
        return TrikiSensorData(
            frameIndex = index,
            timestampNanos = timestampNanos,
            gyroscopeDps = Vector3(gx / gyroscopeScale, gy / gyroscopeScale, gz / gyroscopeScale),
            accelerometerG = Vector3(ax / accelerometerScale, ay / accelerometerScale, az / accelerometerScale),
            rawGyroscope = RawVector3(gx, gy, gz),
            rawAccelerometer = RawVector3(ax, ay, az),
            status = status,
        )
    }

    private fun findHeader(): Int {
        for (index in 0 until buffer.lastIndex) {
            if (
                buffer[index].toInt() and 0xFF == TrikiProtocol.FRAME_HEADER &&
                buffer[index + 1].toInt() and 0xFF in VALID_STATUS_BYTES
            ) {
                return index
            }
        }
        return -1
    }

    private fun retainPossibleSplitHeader() {
        if (buffer.isEmpty()) return
        val keepLast = buffer.last().toInt() and 0xFF == TrikiProtocol.FRAME_HEADER
        val removed = buffer.size - if (keepLast) 1 else 0
        dropped += removed
        val last = buffer.lastOrNull()
        buffer.clear()
        if (keepLast && last != null) buffer += last
    }

    private companion object {
        val VALID_STATUS_BYTES = 0..1
        const val DEFAULT_STARTUP_DISCARD = 20
        const val APPROXIMATE_SAMPLE_PERIOD_NANOS = 9_615_385L
        const val MIN_MONOTONIC_STEP_NANOS = 1_000L
    }
}
