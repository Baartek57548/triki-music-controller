package pl.trikimusic.controller.core.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.UUID
import kotlin.math.pow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pl.trikimusic.controller.core.logging.AppLogger
import pl.trikimusic.controller.core.permissions.PermissionManager
import pl.trikimusic.controller.domain.model.GattCharacteristicInfo
import pl.trikimusic.controller.domain.model.GattDescriptorInfo
import pl.trikimusic.controller.domain.model.GattServiceInfo
import pl.trikimusic.controller.domain.model.LogCategory
import pl.trikimusic.controller.domain.model.RawBlePacket
import pl.trikimusic.controller.domain.model.TrikiBatteryState
import pl.trikimusic.controller.domain.model.TrikiBleState
import pl.trikimusic.controller.domain.model.TrikiConnectionState
import pl.trikimusic.controller.domain.model.TrikiDevice
import pl.trikimusic.controller.domain.model.TrikiDeviceInfo
import pl.trikimusic.controller.domain.model.TrikiSensorData

class TrikiBleManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val permissionManager: PermissionManager,
    private val logger: AppLogger,
) {
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val decoder = TrikiProtocolDecoder()
    private val mutableState = MutableStateFlow(TrikiBleState())
    private val mutableSamples = MutableSharedFlow<TrikiSensorData>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val mutableRawPackets = MutableStateFlow<List<RawBlePacket>>(emptyList())
    private val metadataQueue = ArrayDeque<BluetoothGattCharacteristic>()
    private val metadataValues = mutableMapOf<UUID, ByteArray>()
    private val sampleRateWindow = ArrayDeque<Long>()

    val state: StateFlow<TrikiBleState> = mutableState.asStateFlow()
    val samples: SharedFlow<TrikiSensorData> = mutableSamples.asSharedFlow()
    val rawPackets: StateFlow<List<RawBlePacket>> = mutableRawPackets.asStateFlow()

    private var bluetoothGatt: BluetoothGatt? = null
    private var scanCallback: ScanCallback? = null
    private var scanTimeoutJob: Job? = null
    private var reconnectJob: Job? = null
    private var rssiJob: Job? = null
    private var autoConnectAddress: String? = null
    private var manualDisconnect = false
    private var reconnectAttempt = 0

    fun startScan(knownAddress: String? = null, reconnecting: Boolean = false): Result<Unit> = runCatching {
        val permission = permissionManager.state()
        require(permission.bluetoothSupported) { "Telefon nie obsługuje Bluetooth LE." }
        require(permission.bluetoothPermissionsGranted) { "Brak uprawnień Bluetooth." }
        require(permission.bluetoothEnabled) { "Bluetooth jest wyłączony." }
        require(permission.legacyLocationServicesEnabled) {
            "Android 8–11 wymaga włączonej usługi lokalizacji podczas skanowania BLE."
        }
        stopScanInternal()
        manualDisconnect = false
        autoConnectAddress = knownAddress
        mutableState.update {
            it.copy(
                connectionState = if (reconnecting) TrikiConnectionState.RECONNECTING else TrikiConnectionState.SCANNING,
                discoveredDevices = if (reconnecting) it.discoveredDevices else emptyList(),
                errorMessage = null,
            )
        }
        logger.log(LogCategory.BLE, if (reconnecting) "Skanowanie w celu ponownego połączenia." else "Rozpoczęto skan BLE.")
        startScanner()
        scanTimeoutJob = scope.launch {
            delay(SCAN_TIMEOUT_MILLIS)
            stopScanInternal()
            if (mutableState.value.connectionState in setOf(TrikiConnectionState.SCANNING, TrikiConnectionState.RECONNECTING)) {
                if (knownAddress != null && !manualDisconnect) {
                    scheduleReconnect()
                } else {
                    mutableState.update { it.copy(connectionState = TrikiConnectionState.DISCONNECTED) }
                }
            }
        }
    }.onFailure { error ->
        fail(error.message ?: "Nie można rozpocząć skanowania BLE.", error)
    }

    fun autoConnectKnown(address: String) {
        if (address.isBlank() || bluetoothGatt != null || scanCallback != null) return
        startScan(knownAddress = address, reconnecting = reconnectAttempt > 0)
    }

    @SuppressLint("MissingPermission")
    fun connect(device: TrikiDevice): Result<Unit> = runCatching {
        require(permissionManager.state().connectGranted) { "Brak uprawnienia do połączenia Bluetooth." }
        val adapter = requireNotNull(bluetoothManager?.adapter) { "Adapter Bluetooth jest niedostępny." }
        val remoteDevice = adapter.getRemoteDevice(device.address)
        connect(remoteDevice, device)
    }.onFailure { error -> fail(error.message ?: "Połączenie nie powiodło się.", error) }

    @SuppressLint("MissingPermission")
    fun disconnect(forgetReconnect: Boolean = true) {
        manualDisconnect = forgetReconnect
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempt = 0
        stopScanInternal()
        rssiJob?.cancel()
        rssiJob = null
        bluetoothGatt?.runCatching { disconnect() }
        closeGatt()
        decoder.reset()
        sampleRateWindow.clear()
        mutableState.update {
            it.copy(
                connectionState = TrikiConnectionState.DISCONNECTED,
                selectedDevice = null,
                rssi = null,
                measuredSampleRateHz = null,
                lastFrameMillis = null,
            )
        }
        logger.log(LogCategory.BLE, "Rozłączono Triki.")
    }

    @SuppressLint("MissingPermission")
    fun setLed(enabled: Boolean): Result<Unit> = runCatching {
        val gatt = requireNotNull(bluetoothGatt) { "Triki nie jest połączone." }
        val characteristic = requireNotNull(findCharacteristic(gatt, TrikiProtocol.LED_UUID)) {
            "Charakterystyka LED nie jest dostępna."
        }
        val value = byteArrayOf(if (enabled) 1 else 0)
        val status = writeCharacteristic(gatt, characteristic, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        require(status == BluetoothGatt.GATT_SUCCESS) { "System odrzucił zapis LED: $status" }
    }

    fun clearRawPackets() {
        mutableRawPackets.value = emptyList()
    }

    @SuppressLint("MissingPermission")
    private fun startScanner() {
        val scanner = requireNotNull(bluetoothManager?.adapter?.bluetoothLeScanner) { "Skaner BLE jest niedostępny." }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = handleScanResult(result)

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach(::handleScanResult)
            }

            override fun onScanFailed(errorCode: Int) {
                fail("Skanowanie BLE zakończyło się błędem $errorCode.")
            }
        }
        scanCallback = callback
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0L)
            .build()
        scanner.startScan(null, settings, callback)
    }

    @SuppressLint("MissingPermission")
    private fun handleScanResult(result: ScanResult) {
        val name = result.scanRecord?.deviceName ?: result.device.name ?: "Urządzenie BLE"
        val isNameMatch = name.contains(TRIKI_NAME_FRAGMENT, ignoreCase = true)
        val isAddressMatch = result.device.address.equals(autoConnectAddress, ignoreCase = true)
        if (!isNameMatch && !isAddressMatch) return
        val device = TrikiDevice(name, result.device.address, result.rssi, isKnown = isAddressMatch)
        mutableState.update { current ->
            val devices = (current.discoveredDevices.filterNot { it.address == device.address } + device)
                .sortedWith(compareByDescending<TrikiDevice> { it.isKnown }.thenByDescending { it.rssi ?: Int.MIN_VALUE })
            current.copy(
                connectionState = if (current.connectionState == TrikiConnectionState.SCANNING) TrikiConnectionState.FOUND else current.connectionState,
                discoveredDevices = devices,
            )
        }
        if (isAddressMatch) connect(result.device, device)
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice, model: TrikiDevice) {
        stopScanInternal()
        closeGatt()
        manualDisconnect = false
        mutableState.update {
            it.copy(connectionState = TrikiConnectionState.CONNECTING, selectedDevice = model, errorMessage = null)
        }
        logger.log(LogCategory.BLE, "Łączenie z ${model.name} (${model.address}).")
        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (gatt !== bluetoothGatt) {
                closeGatt(gatt)
                return
            }
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                reconnectAttempt = 0
                mutableState.update { it.copy(connectionState = TrikiConnectionState.CONNECTED, errorMessage = null) }
                logger.log(LogCategory.BLE, "Połączono; rozpoczynam discovery services.")
                if (!gatt.discoverServices()) fail("Nie udało się rozpocząć odkrywania usług GATT.")
                return
            }
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                logger.log(LogCategory.BLE, "Połączenie BLE przerwane (status=$status).")
                closeGatt(gatt)
                decoder.reset()
                sampleRateWindow.clear()
                if (!manualDisconnect && mutableState.value.selectedDevice != null) scheduleReconnect()
                else mutableState.update { it.copy(connectionState = TrikiConnectionState.DISCONNECTED) }
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("Błąd GATT podczas łączenia: $status")
                closeGatt(gatt)
                if (!manualDisconnect) scheduleReconnect()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("Discovery services nie powiodło się: $status")
                return
            }
            val services = gatt.services.map(::mapService)
            mutableState.update { it.copy(gattServices = services) }
            logger.log(LogCategory.BLE, "GATT: ${services.size} usług, ${services.sumOf { it.characteristics.size }} charakterystyk.")
            if (findCharacteristic(gatt, TrikiProtocol.NUS_TX_UUID) == null || findCharacteristic(gatt, TrikiProtocol.NUS_RX_UUID) == null) {
                fail("Urządzenie nie udostępnia potwierdzonego profilu Nordic UART Triki.")
                return
            }
            metadataValues.clear()
            metadataQueue.clear()
            gatt.services.asSequence()
                .flatMap { it.characteristics.asSequence() }
                .filter { characteristic ->
                    characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0 &&
                        characteristic.uuid in METADATA_UUIDS
                }
                .forEach(metadataQueue::addLast)
            readNextMetadata(gatt)
        }

        @Deprecated("Used for Android 8-12 GATT callbacks")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            handleCharacteristicRead(gatt, characteristic, characteristic.value ?: byteArrayOf(), status)
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            handleCharacteristicRead(gatt, characteristic, value, status)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.characteristic.uuid == TrikiProtocol.NUS_TX_UUID) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail("Nie udało się włączyć notyfikacji IMU: $status")
                    return
                }
                startImuStream(gatt)
            }
        }

        @Deprecated("Used for Android 8-12 GATT callbacks")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            handleNotification(characteristic.uuid, characteristic.value ?: byteArrayOf())
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handleNotification(characteristic.uuid, value)
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mutableState.update { it.copy(rssi = rssi) }
        }
    }

    private fun handleCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int,
    ) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            metadataValues[characteristic.uuid] = value.copyOf()
            updateGattValue(characteristic.uuid, value)
            updateStandardMetadata(characteristic.uuid, value)
        } else {
            logger.log(LogCategory.BLE, "Odczyt ${characteristic.uuid} zakończył się statusem $status.")
        }
        readNextMetadata(gatt)
    }

    @SuppressLint("MissingPermission")
    private fun readNextMetadata(gatt: BluetoothGatt) {
        val next = metadataQueue.pollFirst()
        if (next == null) {
            enableNotifications(gatt, TrikiProtocol.NUS_TX_UUID)
            return
        }
        if (!gatt.readCharacteristic(next)) {
            logger.log(LogCategory.BLE, "System odrzucił odczyt ${next.uuid}.")
            readNextMetadata(gatt)
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(gatt: BluetoothGatt, characteristicUuid: UUID) {
        val characteristic = findCharacteristic(gatt, characteristicUuid)
        if (characteristic == null) {
            fail("Brak charakterystyki notyfikacji $characteristicUuid.")
            return
        }
        if (!gatt.setCharacteristicNotification(characteristic, true)) {
            fail("System nie włączył notyfikacji $characteristicUuid.")
            return
        }
        val descriptor = characteristic.getDescriptor(TrikiProtocol.CLIENT_CHARACTERISTIC_CONFIG_UUID)
        if (descriptor == null) {
            fail("Brak deskryptora CCCD dla $characteristicUuid.")
            return
        }
        val status = writeDescriptor(gatt, descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        if (status != BluetoothGatt.GATT_SUCCESS) fail("Zapis CCCD został odrzucony: $status")
    }

    @SuppressLint("MissingPermission")
    private fun startImuStream(gatt: BluetoothGatt) {
        val rx = findCharacteristic(gatt, TrikiProtocol.NUS_RX_UUID)
        if (rx == null) {
            fail("Brak charakterystyki NUS RX.")
            return
        }
        decoder.reset()
        sampleRateWindow.clear()
        val status = writeCharacteristic(
            gatt,
            rx,
            TrikiProtocol.START_STREAM_COMMAND,
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
        )
        if (status != BluetoothGatt.GATT_SUCCESS) {
            fail("Komenda startowa Triki została odrzucona: $status")
            return
        }
        mutableState.update { it.copy(connectionState = TrikiConnectionState.READY, errorMessage = null) }
        logger.log(LogCategory.PROTOCOL, "Wysłano potwierdzoną komendę startową 20 10 00 D0 07 68 00 03.")
        startRssiPolling(gatt)
        scope.launch {
            delay(BATTERY_NOTIFY_DELAY_MILLIS)
            val battery = findCharacteristic(gatt, TrikiProtocol.BATTERY_LEVEL_UUID)
            if (
                mutableState.value.connectionState == TrikiConnectionState.READY &&
                battery != null &&
                battery.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
            ) {
                enableNotifications(gatt, TrikiProtocol.BATTERY_LEVEL_UUID)
                logger.log(LogCategory.BLE, "Włączono opcjonalne notyfikacje poziomu baterii.")
            }
        }
    }

    private fun handleNotification(uuid: UUID, value: ByteArray) {
        val nowMillis = System.currentTimeMillis()
        val packet = RawBlePacket(nowMillis, uuid.toString(), value.copyOf())
        mutableRawPackets.update { current -> (current + packet).takeLast(MAX_RAW_PACKETS) }
        if (uuid == TrikiProtocol.BATTERY_LEVEL_UUID) {
            value.firstOrNull()?.toInt()?.and(0xFF)?.takeIf { it in 0..100 }?.let { updateBattery(it) }
            return
        }
        if (uuid != TrikiProtocol.NUS_TX_UUID) return

        val samples = decoder.decode(value, System.nanoTime())
        samples.forEach { sample ->
            mutableSamples.tryEmit(sample)
            sampleRateWindow.addLast(sample.timestampNanos)
        }
        trimSampleRateWindow()
        val rate = measuredSampleRate()
        mutableState.update {
            it.copy(
                lastFrameMillis = nowMillis.takeIf { samples.isNotEmpty() } ?: it.lastFrameMillis,
                measuredSampleRateHz = rate,
            )
        }
    }

    private fun trimSampleRateWindow() {
        val newest = sampleRateWindow.lastOrNull() ?: return
        while (sampleRateWindow.size > 2 && newest - sampleRateWindow.first() > SAMPLE_RATE_WINDOW_NANOS) {
            sampleRateWindow.removeFirst()
        }
    }

    private fun measuredSampleRate(): Float? {
        if (sampleRateWindow.size < 10) return null
        val duration = sampleRateWindow.last() - sampleRateWindow.first()
        if (duration <= 0L) return null
        return ((sampleRateWindow.size - 1) * 1_000_000_000.0 / duration).toFloat()
    }

    private fun updateStandardMetadata(uuid: UUID, value: ByteArray) {
        if (uuid == TrikiProtocol.BATTERY_LEVEL_UUID) {
            value.firstOrNull()?.toInt()?.and(0xFF)?.takeIf { it in 0..100 }?.let(::updateBattery)
            return
        }
        val text = value.toString(StandardCharsets.UTF_8).trimEnd('\u0000').takeIf { it.isNotBlank() }
        mutableState.update { current ->
            val info = when (uuid) {
                TrikiProtocol.MANUFACTURER_NAME_UUID -> current.deviceInfo.copy(manufacturer = text)
                TrikiProtocol.MODEL_NUMBER_UUID -> current.deviceInfo.copy(model = text)
                TrikiProtocol.SERIAL_NUMBER_UUID -> current.deviceInfo.copy(serialNumber = text)
                TrikiProtocol.FIRMWARE_REVISION_UUID -> current.deviceInfo.copy(firmwareRevision = text)
                TrikiProtocol.HARDWARE_REVISION_UUID -> current.deviceInfo.copy(hardwareRevision = text)
                TrikiProtocol.SOFTWARE_REVISION_UUID -> current.deviceInfo.copy(softwareRevision = text)
                else -> current.deviceInfo
            }
            current.copy(deviceInfo = info)
        }
    }

    private fun updateBattery(percent: Int) {
        mutableState.update { it.copy(battery = TrikiBatteryState(percent, System.currentTimeMillis())) }
    }

    @SuppressLint("MissingPermission")
    private fun startRssiPolling(gatt: BluetoothGatt) {
        rssiJob?.cancel()
        rssiJob = scope.launch {
            while (mutableState.value.connectionState == TrikiConnectionState.READY) {
                gatt.readRemoteRssi()
                delay(RSSI_INTERVAL_MILLIS)
            }
        }
    }

    private fun scheduleReconnect() {
        val address = mutableState.value.selectedDevice?.address ?: autoConnectAddress ?: return
        reconnectJob?.cancel()
        val delayMillis = minOf(MAX_RECONNECT_DELAY_MILLIS, BASE_RECONNECT_DELAY_MILLIS * 2.0.pow(reconnectAttempt).toLong())
        reconnectAttempt = minOf(reconnectAttempt + 1, MAX_RECONNECT_ATTEMPT_EXPONENT)
        mutableState.update { it.copy(connectionState = TrikiConnectionState.RECONNECTING, errorMessage = null) }
        logger.log(LogCategory.BLE, "Ponowne połączenie za ${delayMillis / 1_000}s.")
        reconnectJob = scope.launch {
            delay(delayMillis)
            if (!manualDisconnect) startScan(address, reconnecting = true)
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScanInternal() {
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null
        val callback = scanCallback ?: return
        runCatching { bluetoothManager?.adapter?.bluetoothLeScanner?.stopScan(callback) }
        scanCallback = null
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt(specificGatt: BluetoothGatt? = null) {
        val target = specificGatt ?: bluetoothGatt
        runCatching { target?.close() }
        if (target === bluetoothGatt || specificGatt == null) bluetoothGatt = null
    }

    private fun fail(message: String, throwable: Throwable? = null) {
        logger.log(LogCategory.BLE, message, throwable)
        mutableState.update { it.copy(connectionState = TrikiConnectionState.ERROR, errorMessage = message) }
    }

    private fun mapService(service: BluetoothGattService): GattServiceInfo = GattServiceInfo(
        uuid = service.uuid.toString(),
        characteristics = service.characteristics.map { characteristic ->
            GattCharacteristicInfo(
                uuid = characteristic.uuid.toString(),
                properties = characteristicProperties(characteristic.properties),
                descriptors = characteristic.descriptors.map { GattDescriptorInfo(it.uuid.toString()) },
            )
        },
    )

    private fun updateGattValue(uuid: UUID, value: ByteArray) {
        val hex = value.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
        mutableState.update { current ->
            current.copy(
                gattServices = current.gattServices.map { service ->
                    service.copy(
                        characteristics = service.characteristics.map { characteristic ->
                            if (characteristic.uuid.equals(uuid.toString(), true)) characteristic.copy(valueHex = hex)
                            else characteristic
                        },
                    )
                },
            )
        }
    }

    private fun characteristicProperties(properties: Int): Set<String> = buildSet {
        if (properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("READ")
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("WRITE")
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("WRITE_NO_RESPONSE")
        if (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("NOTIFY")
        if (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("INDICATE")
    }

    private fun findCharacteristic(gatt: BluetoothGatt, uuid: UUID): BluetoothGattCharacteristic? =
        gatt.services.asSequence().mapNotNull { it.getCharacteristic(uuid) }.firstOrNull()

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun writeDescriptor(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, value: ByteArray): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value)
        } else {
            descriptor.value = value
            if (gatt.writeDescriptor(descriptor)) BluetoothGatt.GATT_SUCCESS else GATT_REQUEST_REJECTED
        }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun writeCharacteristic(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        writeType: Int,
    ): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        gatt.writeCharacteristic(characteristic, value, writeType)
    } else {
        characteristic.writeType = writeType
        characteristic.value = value
        if (gatt.writeCharacteristic(characteristic)) BluetoothGatt.GATT_SUCCESS else GATT_REQUEST_REJECTED
    }

    private companion object {
        const val TRIKI_NAME_FRAGMENT = "Triki"
        const val SCAN_TIMEOUT_MILLIS = 15_000L
        const val RSSI_INTERVAL_MILLIS = 10_000L
        const val BATTERY_NOTIFY_DELAY_MILLIS = 300L
        const val MAX_RAW_PACKETS = 300
        const val SAMPLE_RATE_WINDOW_NANOS = 2_000_000_000L
        const val BASE_RECONNECT_DELAY_MILLIS = 2_000L
        const val MAX_RECONNECT_DELAY_MILLIS = 60_000L
        const val MAX_RECONNECT_ATTEMPT_EXPONENT = 5
        const val GATT_REQUEST_REJECTED = -1

        val METADATA_UUIDS = setOf(
            TrikiProtocol.BATTERY_LEVEL_UUID,
            TrikiProtocol.MANUFACTURER_NAME_UUID,
            TrikiProtocol.MODEL_NUMBER_UUID,
            TrikiProtocol.SERIAL_NUMBER_UUID,
            TrikiProtocol.FIRMWARE_REVISION_UUID,
            TrikiProtocol.HARDWARE_REVISION_UUID,
            TrikiProtocol.SOFTWARE_REVISION_UUID,
        )
    }
}
