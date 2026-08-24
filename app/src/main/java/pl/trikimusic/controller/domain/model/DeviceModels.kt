package pl.trikimusic.controller.domain.model

enum class TrikiConnectionState {
    DISCONNECTED,
    SCANNING,
    FOUND,
    CONNECTING,
    CONNECTED,
    READY,
    RECONNECTING,
    ERROR,
}

data class TrikiDevice(
    val name: String,
    val address: String,
    val rssi: Int? = null,
    val isKnown: Boolean = false,
)

data class TrikiBatteryState(
    val percent: Int? = null,
    val lastUpdatedMillis: Long? = null,
)

data class TrikiDeviceInfo(
    val manufacturer: String? = null,
    val model: String? = null,
    val serialNumber: String? = null,
    val firmwareRevision: String? = null,
    val hardwareRevision: String? = null,
    val softwareRevision: String? = null,
)

data class GattDescriptorInfo(
    val uuid: String,
)

data class GattCharacteristicInfo(
    val uuid: String,
    val properties: Set<String>,
    val valueHex: String? = null,
    val descriptors: List<GattDescriptorInfo> = emptyList(),
)

data class GattServiceInfo(
    val uuid: String,
    val characteristics: List<GattCharacteristicInfo>,
)

data class RawBlePacket(
    val timestampMillis: Long,
    val characteristicUuid: String,
    val bytes: ByteArray,
) {
    val hex: String
        get() = bytes.joinToString(" ") { byte -> "%02X".format(byte.toInt() and 0xFF) }

    val decimal: String
        get() = bytes.joinToString(" ") { byte -> (byte.toInt() and 0xFF).toString() }

    override fun equals(other: Any?): Boolean =
        other is RawBlePacket &&
            timestampMillis == other.timestampMillis &&
            characteristicUuid == other.characteristicUuid &&
            bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * (31 * timestampMillis.hashCode() + characteristicUuid.hashCode()) + bytes.contentHashCode()
}

data class TrikiBleState(
    val connectionState: TrikiConnectionState = TrikiConnectionState.DISCONNECTED,
    val selectedDevice: TrikiDevice? = null,
    val discoveredDevices: List<TrikiDevice> = emptyList(),
    val rssi: Int? = null,
    val battery: TrikiBatteryState = TrikiBatteryState(),
    val deviceInfo: TrikiDeviceInfo = TrikiDeviceInfo(),
    val gattServices: List<GattServiceInfo> = emptyList(),
    val lastFrameMillis: Long? = null,
    val measuredSampleRateHz: Float? = null,
    val decodedFrames: Long = 0L,
    val discardedStartupFrames: Long = 0L,
    val droppedProtocolBytes: Long = 0L,
    val lastPacketId: Int? = null,
    val errorMessage: String? = null,
)
