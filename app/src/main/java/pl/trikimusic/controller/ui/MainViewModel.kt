package pl.trikimusic.controller.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import pl.trikimusic.controller.AppContainer
import pl.trikimusic.controller.BuildConfig
import pl.trikimusic.controller.domain.model.AppLogEntry
import pl.trikimusic.controller.domain.model.AppSettings
import pl.trikimusic.controller.domain.model.AppUpdateInfo
import pl.trikimusic.controller.domain.model.ButtonClickType
import pl.trikimusic.controller.domain.model.CalibrationProfile
import pl.trikimusic.controller.domain.model.GattServiceInfo
import pl.trikimusic.controller.domain.model.MediaAction
import pl.trikimusic.controller.domain.model.MediaSessionState
import pl.trikimusic.controller.domain.model.RawBlePacket
import pl.trikimusic.controller.domain.model.LogCategory
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

enum class UpdateStage {
    IDLE,
    CHECKING,
    AVAILABLE,
    DOWNLOADING,
    AWAITING_INSTALL_PERMISSION,
    READY_TO_INSTALL,
    ERROR,
}

data class UpdateUiState(
    val stage: UpdateStage = UpdateStage.IDLE,
    val info: AppUpdateInfo? = null,
    val downloadProgress: Float = 0f,
    val errorMessage: String? = null,
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
    val update: UpdateUiState = UpdateUiState(),
    val settingsLoaded: Boolean = false,
)

class MainViewModel(
    application: Application,
    private val container: AppContainer,
) : AndroidViewModel(application) {
    private val mutablePermissions = MutableStateFlow(container.permissionManager.state())
    private val mutableUserMessage = MutableStateFlow<String?>(null)
    private val mutableCalibration = MutableStateFlow(CalibrationUiState())
    private val mutableUpdate = MutableStateFlow(UpdateUiState())
    private val mutableSettings = MutableStateFlow<AppSettings?>(null)
    private var calibrationJob: Job? = null
    private var updateCheckJob: Job? = null
    private var updateDownloadJob: Job? = null
    private var downloadedUpdateFile: File? = null
    private var automaticUpdateCheckStarted = false
    private var autoConnectRequested = false
    private var rawRecordingStartedAtMillis: Long? = null
    private var frozenRawCapture: List<RawBlePacket>? = null

    val calibration: StateFlow<CalibrationUiState> = mutableCalibration

    private val coreState = combine(
        mutableSettings,
        container.bleManager.state,
        container.runtime.state,
        container.mediaController.state,
        container.bleManager.rawPackets,
    ) { settings, ble, runtime, media, packets ->
        MainUiState(
            settings = settings ?: AppSettings(),
            ble = ble,
            runtime = runtime,
            media = media,
            rawPackets = packets,
            settingsLoaded = settings != null,
        )
    }

    val uiState: StateFlow<MainUiState> = combine(
        coreState,
        mutablePermissions,
        container.logger.entries,
        mutableUserMessage,
        mutableUpdate,
    ) { core, permissions, logs, message, update ->
        core.copy(permissions = permissions, logs = logs, userMessage = message, update = update)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    init {
        viewModelScope.launch {
            container.settingsRepository.settings.collectLatest { settings ->
                mutableSettings.value = settings
                if (settings.knownDeviceAddress != null) autoConnectIfPossible()
                if (
                    settings.onboardingComplete &&
                    !BuildConfig.DEBUG &&
                    !automaticUpdateCheckStarted
                ) {
                    automaticUpdateCheckStarted = true
                    checkForUpdates(showNoUpdateMessage = false)
                }
            }
        }
        viewModelScope.launch {
            container.bleManager.state.collect { bleState ->
                val connectedDevice = bleState.selectedDevice
                if (bleState.connectionState == TrikiConnectionState.READY && connectedDevice != null) {
                    val settings = currentSettings()
                    if (
                        !settings.knownDeviceAddress.equals(connectedDevice.address, ignoreCase = true) ||
                        settings.knownDeviceName != connectedDevice.name
                    ) {
                        runCatching {
                            container.settingsRepository.rememberDevice(connectedDevice.address, connectedDevice.name)
                        }.onFailure(::showError)
                    }
                }
            }
        }
    }

    fun refreshSystemState() {
        mutablePermissions.value = container.permissionManager.state()
        container.mediaController.refresh()
        autoConnectIfPossible()
        resumePendingUpdateInstallation()
    }

    fun completeOnboarding() = launchHandled { container.settingsRepository.completeOnboarding() }

    fun startScan() {
        container.bleManager.startScan(knownAddress = currentSettings().knownDeviceAddress).onFailure(::showError)
    }

    fun connect(device: TrikiDevice) {
        container.bleManager.connect(device)
            .onSuccess {
                if (currentSettings().backgroundEnabled) startBackgroundService(showErrorToUser = true)
            }
            .onFailure(::showError)
    }

    fun disconnect() {
        container.bleManager.disconnect()
        TrikiForegroundService.stop(getApplication())
    }

    fun disableAutoConnect() = launchHandled {
        container.bleManager.disconnect()
        TrikiForegroundService.stop(getApplication())
        container.settingsRepository.setBackgroundEnabled(false)
    }

    fun forgetDevice() = launchHandled {
        disconnect()
        container.settingsRepository.forgetDevice()
    }

    fun performMediaAction(action: MediaAction) {
        container.mediaController.execute(action).onFailure(::showError)
    }

    fun setButtonMapping(click: ButtonClickType, action: MediaAction) = launchHandled {
        container.settingsRepository.setButtonMapping(currentSettings().activeProfileId, click, action)
    }

    fun setDeveloperMode(enabled: Boolean) = launchHandled { container.settingsRepository.setDeveloperMode(enabled) }

    fun setBackgroundEnabled(enabled: Boolean) = launchHandled {
        container.settingsRepository.setBackgroundEnabled(enabled)
        val settings = currentSettings()
        if (enabled && settings.knownDeviceAddress != null) {
            startBackgroundService(showErrorToUser = true)
            container.bleManager.autoConnectKnown(settings.knownDeviceAddress, settings.knownDeviceName)
            autoConnectRequested = true
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

    fun checkForUpdates(showNoUpdateMessage: Boolean = true) {
        if (updateCheckJob?.isActive == true || updateDownloadJob?.isActive == true) return
        updateCheckJob = viewModelScope.launch {
            mutableUpdate.value = UpdateUiState(stage = UpdateStage.CHECKING)
            try {
                val update = container.updateManager.checkForUpdate(BuildConfig.VERSION_NAME)
                mutableUpdate.value = if (update == null) {
                    if (showNoUpdateMessage) mutableUserMessage.value = "Masz najnowszą wersję aplikacji."
                    UpdateUiState()
                } else {
                    UpdateUiState(stage = UpdateStage.AVAILABLE, info = update)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                container.logger.log(
                    LogCategory.UPDATE,
                    "Nie udało się sprawdzić aktualizacji.",
                    error,
                )
                if (showNoUpdateMessage) {
                    mutableUpdate.value = UpdateUiState(
                        stage = UpdateStage.ERROR,
                        errorMessage = error.message ?: "Nie udało się sprawdzić aktualizacji.",
                    )
                } else {
                    mutableUpdate.value = UpdateUiState()
                }
            }
        }
    }

    fun downloadAvailableUpdate() {
        val update = mutableUpdate.value.info ?: return
        if (updateDownloadJob?.isActive == true) return
        updateDownloadJob = viewModelScope.launch {
            mutableUpdate.value = UpdateUiState(stage = UpdateStage.DOWNLOADING, info = update)
            try {
                val apk = container.updateManager.downloadAndVerify(
                    update = update,
                    currentVersionCode = BuildConfig.VERSION_CODE.toLong(),
                    onProgress = { progress ->
                        mutableUpdate.value = UpdateUiState(
                            stage = UpdateStage.DOWNLOADING,
                            info = update,
                            downloadProgress = progress,
                        )
                    },
                )
                downloadedUpdateFile = apk
                installDownloadedUpdate()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                container.logger.log(
                    LogCategory.UPDATE,
                    "Nie udało się pobrać aktualizacji.",
                    error,
                )
                mutableUpdate.value = UpdateUiState(
                    stage = UpdateStage.ERROR,
                    info = update,
                    errorMessage = error.message ?: "Nie udało się pobrać aktualizacji.",
                )
            }
        }
    }

    fun requestUpdateInstallPermission() {
        if (container.updateManager.canRequestPackageInstalls()) {
            installDownloadedUpdate()
            return
        }
        container.updateManager.openInstallPermissionSettings().onFailure(::showError)
    }

    fun installDownloadedUpdate() {
        val update = mutableUpdate.value.info ?: return
        val apk = downloadedUpdateFile
        if (apk == null || !apk.isFile) {
            mutableUpdate.value = UpdateUiState(
                stage = UpdateStage.ERROR,
                info = update,
                errorMessage = "Pobrany plik aktualizacji nie jest już dostępny.",
            )
            return
        }
        if (!container.updateManager.canRequestPackageInstalls()) {
            mutableUpdate.value = UpdateUiState(
                stage = UpdateStage.AWAITING_INSTALL_PERMISSION,
                info = update,
                downloadProgress = 1f,
            )
            return
        }
        mutableUpdate.value = UpdateUiState(
            stage = UpdateStage.READY_TO_INSTALL,
            info = update,
            downloadProgress = 1f,
        )
        container.updateManager.launchInstaller(apk).onFailure { error ->
            mutableUpdate.value = UpdateUiState(
                stage = UpdateStage.ERROR,
                info = update,
                downloadProgress = 1f,
                errorMessage = error.message ?: "Nie udało się uruchomić instalatora.",
            )
        }
    }

    fun dismissUpdate() {
        updateDownloadJob?.cancel()
        updateDownloadJob = null
        container.updateManager.deleteDownloadedUpdate(downloadedUpdateFile)
        downloadedUpdateFile = null
        mutableUpdate.value = UpdateUiState()
    }

    private fun resumePendingUpdateInstallation() {
        if (
            mutableUpdate.value.stage == UpdateStage.AWAITING_INSTALL_PERMISSION &&
            container.updateManager.canRequestPackageInstalls()
        ) {
            installDownloadedUpdate()
        }
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
        if (!BuildConfig.DEBUG || !currentSettings().developerMode) {
            showError(IllegalStateException("Generator przycisku wymaga buildu debug i trybu deweloperskiego."))
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
        val settings = currentSettings()
        val address = settings.knownDeviceAddress ?: return
        if (
            !mutablePermissions.value.connectGranted ||
            !mutablePermissions.value.bluetoothEnabled ||
            !mutablePermissions.value.bluetoothSupported
        ) return
        autoConnectRequested = true
        if (settings.backgroundEnabled) startBackgroundService(showErrorToUser = false)
        container.bleManager.autoConnectKnown(address, settings.knownDeviceName)
    }

    private fun startBackgroundService(showErrorToUser: Boolean) {
        runCatching { TrikiForegroundService.start(getApplication()) }
            .onFailure { error ->
                container.logger.log(
                    LogCategory.SERVICE,
                    "Android nie pozwolił uruchomić autołączenia w tle.",
                    error,
                )
                if (showErrorToUser) showError(error)
            }
    }

    private fun currentSettings(): AppSettings = mutableSettings.value ?: container.settings.value

    private fun launchHandled(block: suspend () -> Unit) {
        viewModelScope.launch { runCatching { block() }.onFailure(::showError) }
    }

    private fun showError(error: Throwable) {
        mutableUserMessage.value = error.message ?: "Wystąpił nieoczekiwany błąd."
    }

    override fun onCleared() {
        calibrationJob?.cancel()
        updateCheckJob?.cancel()
        updateDownloadJob?.cancel()
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
