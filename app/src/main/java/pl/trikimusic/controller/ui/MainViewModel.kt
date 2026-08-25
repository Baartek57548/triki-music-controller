package pl.trikimusic.controller.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import pl.trikimusic.controller.AppContainer
import pl.trikimusic.controller.BuildConfig
import pl.trikimusic.controller.domain.model.AppLogEntry
import pl.trikimusic.controller.domain.model.AppSettings
import pl.trikimusic.controller.domain.model.ButtonClickType
import pl.trikimusic.controller.domain.model.CalibrationProfile
import pl.trikimusic.controller.domain.model.GattServiceInfo
import pl.trikimusic.controller.domain.model.MediaAction
import pl.trikimusic.controller.domain.model.MediaSessionState
import pl.trikimusic.controller.domain.model.RawBlePacket
import pl.trikimusic.controller.domain.model.ThemePreference
import pl.trikimusic.controller.domain.model.TrikiBleState
import pl.trikimusic.controller.domain.model.TrikiConnectionState
import pl.trikimusic.controller.domain.model.TrikiDevice
import pl.trikimusic.controller.domain.model.TrikiSensorData
import pl.trikimusic.controller.core.sensor.CalibrationCalculator
import pl.trikimusic.controller.core.permissions.PermissionState
import pl.trikimusic.controller.runtime.RuntimeState
import pl.trikimusic.controller.service.TrikiForegroundService

data class CalibrationUiState(
    val running: Boolean = false,
    val progress: Float = 0f,
    val sampleCount: Int = 0,
    val result: CalibrationProfile? = null,
    val error: String? = null,
)

data class MainUiState(
    val settings: AppSettings = AppSettings(),
    val ble: TrikiBleState = TrikiBleState(),
    val runtime: RuntimeState = RuntimeState(),
    val media: MediaSessionState = MediaSessionState(),
    val rawPackets: List<RawBlePacket> = emptyList(),
    val permissions: PermissionState = PermissionState(false, false, false, false, false, false, false),
    val logs: List<AppLogEntry> = emptyList(),
    val userMessage: String? = null,
)

class MainViewModel(
    application: Application,
    private val container: AppContainer,
) : AndroidViewModel(application) {
    private val mutablePermissions = MutableStateFlow(container.permissionManager.state())
    private val mutableUserMessage = MutableStateFlow<String?>(null)
    private val mutableCalibration = MutableStateFlow(CalibrationUiState())
    private var calibrationJob: Job? = null
    private var autoConnectRequested = false
    private var rawRecordingStartedAtMillis: Long? = null
    private var frozenRawCapture: List<RawBlePacket>? = null

    val calibration: StateFlow<CalibrationUiState> = mutableCalibration

    private val coreState = combine(
        container.settings,
        container.bleManager.state,
        container.runtime.state,
        container.mediaController.state,
        container.bleManager.rawPackets,
    ) { settings, ble, runtime, media, packets ->
        MainUiState(settings, ble, runtime, media, packets)
    }

    val uiState: StateFlow<MainUiState> = combine(
        coreState,
        mutablePermissions,
        container.logger.entries,
        mutableUserMessage,
    ) { core, permissions, logs, message ->
        core.copy(permissions = permissions, logs = logs, userMessage = message)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    init {
        viewModelScope.launch {
            container.settings.collectLatest { settings ->
                if (settings.knownDeviceAddress != null) autoConnectIfPossible()
            }
        }
    }

    fun refreshSystemState() {
        mutablePermissions.value = container.permissionManager.state()
        container.mediaController.refresh()
        autoConnectIfPossible()
    }

    fun completeOnboarding() = launchHandled { container.settingsRepository.completeOnboarding() }

    fun startScan() {
        container.bleManager.startScan().onFailure(::showError)
    }

    fun connect(device: TrikiDevice) {
        container.bleManager.connect(device).onFailure(::showError)
        launchHandled { container.settingsRepository.rememberDevice(device.address, device.name) }
        if (container.settings.value.backgroundEnabled) TrikiForegroundService.start(getApplication())
    }

    fun disconnect() {
        container.bleManager.disconnect()
        TrikiForegroundService.stop(getApplication())
    }

    fun forgetDevice() = launchHandled {
        disconnect()
        container.settingsRepository.forgetDevice()
    }

    fun performMediaAction(action: MediaAction) {
        container.mediaController.execute(action).onFailure(::showError)
    }

    fun setButtonMapping(click: ButtonClickType, action: MediaAction) = launchHandled {
        container.settingsRepository.setButtonMapping(container.settings.value.activeProfileId, click, action)
    }

    fun setDeveloperMode(enabled: Boolean) = launchHandled { container.settingsRepository.setDeveloperMode(enabled) }

    fun setBackgroundEnabled(enabled: Boolean) = launchHandled {
        container.settingsRepository.setBackgroundEnabled(enabled)
        if (enabled && container.bleManager.state.value.connectionState != TrikiConnectionState.DISCONNECTED) {
            TrikiForegroundService.start(getApplication())
        } else if (!enabled) {
            TrikiForegroundService.stop(getApplication())
        }
    }

    fun setTheme(theme: ThemePreference) = launchHandled { container.settingsRepository.setTheme(theme) }

    fun setLed(enabled: Boolean) {
        container.bleManager.setLed(enabled).onFailure(::showError)
    }

    fun clearRawPackets() = container.bleManager.clearRawPackets()

    fun startRawRecording() {
        container.bleManager.clearRawPackets()
        frozenRawCapture = null
        rawRecordingStartedAtMillis = System.currentTimeMillis()
    }

    fun stopRawRecording() {
        val startedAt = rawRecordingStartedAtMillis ?: return
        frozenRawCapture = container.bleManager.rawPackets.value.filter { it.timestampMillis >= startedAt }
        rawRecordingStartedAtMillis = null
    }

    fun clearLogs() = container.logger.clear()

    fun dismissMessage() {
        mutableUserMessage.value = null
    }

    fun startCalibration() {
        if (container.bleManager.state.value.connectionState != TrikiConnectionState.READY) {
            showError(IllegalStateException("Najpierw połącz Triki i poczekaj na dane IMU."))
            return
        }
        calibrationJob?.cancel()
        calibrationJob = viewModelScope.launch {
            mutableCalibration.value = CalibrationUiState(running = true)
            val samples = mutableListOf<TrikiSensorData>()
            val started = System.nanoTime()
            withTimeoutOrNull(CALIBRATION_TIMEOUT_MILLIS) {
                container.bleManager.samples
                    .takeWhile { System.nanoTime() - started < CALIBRATION_DURATION_NANOS }
                    .collect { sample ->
                        samples += sample
                        val progress = ((System.nanoTime() - started).toDouble() / CALIBRATION_DURATION_NANOS)
                            .toFloat()
                            .coerceIn(0f, 1f)
                        mutableCalibration.value = CalibrationUiState(true, progress, samples.size)
                    }
            }
            runCatching {
                val capturedDuration = if (samples.size >= 2) {
                    samples.last().timestampNanos - samples.first().timestampNanos
                } else {
                    0L
                }
                require(capturedDuration >= MIN_CALIBRATION_CAPTURE_NANOS) {
                    "Strumień przerwał się przed zebraniem pełnej kalibracji."
                }
                CalibrationCalculator.calculate(samples, System.currentTimeMillis())
            }
                .onSuccess { profile ->
                    container.settingsRepository.saveCalibration(profile)
                    mutableCalibration.value = CalibrationUiState(
                        running = false,
                        progress = 1f,
                        sampleCount = samples.size,
                        result = profile,
                    )
                }
                .onFailure { error ->
                    mutableCalibration.value = CalibrationUiState(error = error.message ?: "Kalibracja nie powiodła się.")
                }
        }
    }

    fun resetCalibrationState() {
        calibrationJob?.cancel()
        mutableCalibration.value = CalibrationUiState()
    }

    fun emitFakeButtonClicks(clickCount: Int) {
        if (!BuildConfig.DEBUG || !container.settings.value.developerMode) {
            showError(IllegalStateException("Generator przycisku wymaga buildu debug i Developer Mode."))
            return
        }
        container.runtime.injectDebugSamples(container.fakeTrikiDataSource.generateButtonClicks(clickCount))
    }

    fun rawCaptureText(): String {
        val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME.withZone(ZoneId.systemDefault())
        val packets = frozenRawCapture ?: container.bleManager.rawPackets.value
        return buildString {
            appendLine("Triki Music BLE capture")
            appendLine("exported=${formatter.format(Instant.now())}")
            appendLine("packets=${packets.size}")
            packets.forEach { packet ->
                append(formatter.format(Instant.ofEpochMilli(packet.timestampMillis)))
                append("  ")
                append(packet.characteristicUuid)
                append("  HEX=")
                append(packet.hex)
                append("  DEC=")
                appendLine(packet.decimal)
            }
        }
    }

    fun diagnosticsText(): String = buildString {
        appendLine("Triki Music diagnostics")
        appendLine("state=${container.bleManager.state.value.connectionState}")
        appendLine("device=${container.bleManager.state.value.selectedDevice}")
        appendLine("sampleRateHz=${container.bleManager.state.value.measuredSampleRateHz}")
        appendLine("lastFrameMillis=${container.bleManager.state.value.lastFrameMillis}")
        appendLine("gattServices=${container.bleManager.state.value.gattServices.size}")
        container.logger.entries.value.forEach { entry ->
            appendLine("${entry.timestampMillis} [${entry.category}] ${entry.message}")
        }
    }

    private fun autoConnectIfPossible() {
        if (autoConnectRequested) return
        val address = container.settings.value.knownDeviceAddress ?: return
        if (
            !mutablePermissions.value.bluetoothPermissionsGranted ||
            !mutablePermissions.value.bluetoothEnabled ||
            !mutablePermissions.value.legacyLocationServicesEnabled
        ) return
        autoConnectRequested = true
        if (container.settings.value.backgroundEnabled) TrikiForegroundService.start(getApplication())
        container.bleManager.autoConnectKnown(address)
    }

    private fun launchHandled(block: suspend () -> Unit) {
        viewModelScope.launch { runCatching { block() }.onFailure(::showError) }
    }

    private fun showError(error: Throwable) {
        mutableUserMessage.value = error.message ?: "Wystąpił nieoczekiwany błąd."
    }

    override fun onCleared() {
        calibrationJob?.cancel()
        super.onCleared()
    }

    class Factory(
        private val application: Application,
        private val container: AppContainer,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(MainViewModel::class.java))
            return MainViewModel(application, container) as T
        }
    }

    private companion object {
        const val CALIBRATION_DURATION_NANOS = 3_000_000_000L
        const val CALIBRATION_TIMEOUT_MILLIS = 6_000L
        const val MIN_CALIBRATION_CAPTURE_NANOS = 2_500_000_000L
    }
}
