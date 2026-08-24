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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import pl.trikimusic.controller.AppContainer
import pl.trikimusic.controller.BuildConfig
import pl.trikimusic.controller.core.gesture.GestureCaptureExporter
import pl.trikimusic.controller.domain.model.AppLogEntry
import pl.trikimusic.controller.domain.model.AppSettings
import pl.trikimusic.controller.domain.model.ButtonClickType
import pl.trikimusic.controller.domain.model.CalibrationProfile
import pl.trikimusic.controller.domain.model.FilteredSensorData
import pl.trikimusic.controller.domain.model.GattServiceInfo
import pl.trikimusic.controller.domain.model.GestureFeatureVector
import pl.trikimusic.controller.domain.model.GestureThresholds
import pl.trikimusic.controller.domain.model.GestureType
import pl.trikimusic.controller.domain.model.MediaAction
import pl.trikimusic.controller.domain.model.MediaSessionState
import pl.trikimusic.controller.domain.model.MIN_PERSONALIZED_SAMPLES_PER_GESTURE
import pl.trikimusic.controller.domain.model.RawBlePacket
import pl.trikimusic.controller.domain.model.SensitivityLevel
import pl.trikimusic.controller.domain.model.ThemePreference
import pl.trikimusic.controller.domain.model.TrikiBleState
import pl.trikimusic.controller.domain.model.TrikiConnectionState
import pl.trikimusic.controller.domain.model.TrikiDevice
import pl.trikimusic.controller.domain.model.TrikiSensorData
import pl.trikimusic.controller.domain.model.defaultProfiles
import pl.trikimusic.controller.domain.model.thresholds
import pl.trikimusic.controller.core.gesture.CalibrationCalculator
import pl.trikimusic.controller.core.gesture.GestureRecordingAnalyzer
import pl.trikimusic.controller.core.gesture.GestureFeatureExtractor
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

data class TrainerUiState(
    val selectedGesture: GestureType = GestureType.LEAN,
    val recording: Boolean = false,
    val sampleCount: Int = 0,
    val durationMillis: Long = 0L,
    val detectedGesture: GestureType? = null,
    val confidence: Float? = null,
    val peakGyroscopeDps: Float = 0f,
    val accelerationRangeG: ClosedFloatingPointRange<Float>? = null,
    val accepted: Boolean = false,
    val featureQuality: Float = 0f,
    val featureReady: Boolean = false,
    val learnedSampleCount: Int = 0,
    val message: String? = null,
)

data class GestureWizardUiState(
    val active: Boolean = false,
    val currentIndex: Int = 0,
    val selectedAction: MediaAction = MediaAction.NONE,
    val configuredActions: Map<GestureType, MediaAction> = emptyMap(),
    val verifiedGestures: Set<GestureType> = emptySet(),
    val skippedGestures: Set<GestureType> = emptySet(),
    val saving: Boolean = false,
    val summaryVisible: Boolean = false,
    val finishing: Boolean = false,
    val completionSaved: Boolean = false,
) {
    val currentGesture: GestureType
        get() = GestureType.entries[currentIndex.coerceIn(0, GestureType.entries.lastIndex)]

    val isLastGesture: Boolean
        get() = currentIndex == GestureType.entries.lastIndex
}

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
    private val mutableTrainer = MutableStateFlow(TrainerUiState())
    private val mutableGestureWizard = MutableStateFlow(GestureWizardUiState())
    private var calibrationJob: Job? = null
    private var trainerJob: Job? = null
    private val trainerSamples = mutableListOf<FilteredSensorData>()
    private val trainerAnalyzer = GestureRecordingAnalyzer()
    private val trainerFeatureExtractor = GestureFeatureExtractor()
    private var pendingTrainerFeatures: GestureFeatureVector? = null
    private var autoConnectRequested = false
    private var rawRecordingStartedAtMillis: Long? = null
    private var frozenRawCapture: List<RawBlePacket>? = null

    val calibration: StateFlow<CalibrationUiState> = mutableCalibration
    val trainer: StateFlow<TrainerUiState> = mutableTrainer
    val gestureWizard: StateFlow<GestureWizardUiState> = mutableGestureWizard

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

    fun setMapping(gesture: GestureType, action: MediaAction) = launchHandled {
        container.settingsRepository.setGestureMapping(container.settings.value.activeProfileId, gesture, action)
    }

    fun setButtonMapping(click: ButtonClickType, action: MediaAction) = launchHandled {
        container.settingsRepository.setButtonMapping(container.settings.value.activeProfileId, click, action)
    }

    fun setActiveProfile(profileId: String) = launchHandled { container.settingsRepository.setActiveProfile(profileId) }

    fun createProfile(name: String) = launchHandledResult { container.settingsRepository.createProfile(name) }

    fun copyActiveProfile(name: String) = launchHandledResult {
        container.settingsRepository.copyProfile(container.settings.value.activeProfileId, name)
    }

    fun renameActiveProfile(name: String) = launchHandledResult {
        container.settingsRepository.renameProfile(container.settings.value.activeProfileId, name)
    }

    fun deleteActiveProfile() = launchHandledResult {
        container.settingsRepository.deleteProfile(container.settings.value.activeProfileId)
    }

    fun setSensitivity(level: SensitivityLevel) = launchHandled { container.settingsRepository.setSensitivity(level) }

    fun setAdvancedThresholds(thresholds: GestureThresholds) = launchHandled {
        container.settingsRepository.setAdvancedThresholds(thresholds)
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

    fun selectTrainerGesture(gesture: GestureType) {
        cancelTrainerRecording(resetState = false)
        pendingTrainerFeatures = null
        mutableTrainer.value = TrainerUiState(
            selectedGesture = gesture,
            learnedSampleCount = container.settings.value.personalizedGestureModel.sampleCountFor(gesture),
        )
    }

    fun startTrainer() {
        val selected = mutableTrainer.value.selectedGesture
        val learnedSampleCount = maxOf(
            mutableTrainer.value.learnedSampleCount,
            container.settings.value.personalizedGestureModel.sampleCountFor(selected),
        )
        if (container.bleManager.state.value.connectionState != TrikiConnectionState.READY) {
            mutableTrainer.value = TrainerUiState(
                selectedGesture = selected,
                learnedSampleCount = learnedSampleCount,
                message = "Najpierw połącz Triki i poczekaj na dane IMU.",
            )
            return
        }
        if (!container.settings.value.calibration.isValid) {
            mutableTrainer.value = TrainerUiState(
                selectedGesture = selected,
                learnedSampleCount = learnedSampleCount,
                message = "Najpierw wykonaj kalibrację nieruchomego Triki w zakładce Device.",
            )
            return
        }

        cancelTrainerRecording(resetState = false)
        trainerSamples.clear()
        pendingTrainerFeatures = null
        // A short pre-roll gives the detector a real neutral baseline even when Start is tapped
        // immediately before the movement.
        trainerSamples += container.runtime.state.value.history.takeLast(TRAINER_PREROLL_SAMPLES)
        container.runtime.setGestureActionsSuspended(true)
        mutableTrainer.value = TrainerUiState(
            selectedGesture = selected,
            learnedSampleCount = learnedSampleCount,
            recording = true,
            sampleCount = trainerSamples.size,
            durationMillis = captureDurationMillis(),
            message = "Nagrywanie trwa. Wykonaj jeden gest, zatrzymaj Triki i naciśnij Stop.",
        )
        trainerJob = viewModelScope.launch {
            withTimeoutOrNull(TRAINER_MAX_DURATION_MILLIS) {
                container.runtime.filteredSamples.collect { sample ->
                    if (
                        trainerSamples.size < TRAINER_MAX_SAMPLES &&
                        trainerSamples.lastOrNull()?.source?.timestampNanos != sample.source.timestampNanos
                    ) {
                        trainerSamples += sample
                    }
                    mutableTrainer.update {
                        it.copy(
                            sampleCount = trainerSamples.size,
                            durationMillis = captureDurationMillis(),
                        )
                    }
                }
            }
            if (mutableTrainer.value.recording) {
                finishTrainerRecording(autoStopped = true)
            }
        }
    }

    fun stopTrainer() {
        if (!mutableTrainer.value.recording) return
        trainerJob?.cancel()
        trainerJob = null
        finishTrainerRecording(autoStopped = false)
    }

    fun cancelTrainer() {
        val selected = mutableTrainer.value.selectedGesture
        cancelTrainerRecording(resetState = false)
        pendingTrainerFeatures = null
        mutableTrainer.value = TrainerUiState(
            selectedGesture = selected,
            learnedSampleCount = container.settings.value.personalizedGestureModel.sampleCountFor(selected),
        )
    }

    fun learnTrainerSample() {
        val current = mutableTrainer.value
        val features = pendingTrainerFeatures
        if (!current.featureReady || features == null || current.recording || current.accepted) {
            if (!current.accepted) {
                mutableTrainer.update { it.copy(message = "Najpierw nagraj próbkę o dobrej jakości.") }
            }
            return
        }
        viewModelScope.launch {
            runCatching {
                container.settingsRepository.saveGestureTrainingSample(current.selectedGesture, features)
            }.onSuccess {
                pendingTrainerFeatures = null
                val count = (current.learnedSampleCount + 1).coerceAtMost(5)
                mutableTrainer.update {
                    it.copy(
                        accepted = true,
                        featureReady = false,
                        learnedSampleCount = count,
                        message = if (count >= MIN_PERSONALIZED_SAMPLES_PER_GESTURE) {
                            "Model nauczył się tego gestu z $count próbek. Możesz dodać kolejną w innej pozycji kapsla."
                        } else {
                            "Zapisano pierwszą próbkę. Nagraj jeszcze jedną w innej typowej pozycji kapsla."
                        },
                    )
                }
            }.onFailure(::showError)
        }
    }

    fun trainerCaptureCsv(): String {
        val trainerState = mutableTrainer.value
        check(!trainerState.recording) { "Najpierw zatrzymaj nagranie." }
        val settings = container.settings.value
        return GestureCaptureExporter.toCsv(
            samples = trainerSamples.toList(),
            expectedGesture = trainerState.selectedGesture,
            detectedGesture = trainerState.detectedGesture,
            confidence = trainerState.confidence,
            featureQuality = trainerState.featureQuality,
            thresholds = settings.sensitivity.thresholds(settings.advancedThresholds),
            calibration = settings.calibration,
        )
    }

    fun clearTrainerSamples() {
        val gesture = mutableTrainer.value.selectedGesture
        launchHandled {
            container.settingsRepository.clearGestureTraining(gesture)
            pendingTrainerFeatures = null
            mutableTrainer.value = TrainerUiState(
                selectedGesture = gesture,
                message = "Usunięto spersonalizowane próbki tego gestu.",
            )
        }
    }

    fun beginGestureWizard() {
        if (mutableGestureWizard.value.active) return
        cancelTrainerRecording(resetState = false)
        val firstGesture = GestureType.entries.first()
        val activeProfile = container.settings.value.activeProfile
        mutableGestureWizard.value = GestureWizardUiState(
            active = true,
            selectedAction = activeProfile.actionFor(firstGesture),
            configuredActions = GestureType.entries.associateWith(activeProfile::actionFor),
        )
        mutableTrainer.value = TrainerUiState(
            selectedGesture = firstGesture,
            learnedSampleCount = container.settings.value.personalizedGestureModel.sampleCountFor(firstGesture),
        )
    }

    fun selectGestureWizardAction(action: MediaAction) {
        mutableGestureWizard.update { current ->
            if (current.active && !current.saving && !current.finishing) {
                current.copy(selectedAction = action)
            } else {
                current
            }
        }
    }

    fun saveGestureWizardStep(verified: Boolean) {
        val wizard = mutableGestureWizard.value
        if (!wizard.active || wizard.saving || wizard.finishing || wizard.summaryVisible) return
        val gesture = wizard.currentGesture
        val action = wizard.selectedAction
        mutableGestureWizard.value = wizard.copy(saving = true)
        viewModelScope.launch {
            runCatching {
                container.settingsRepository.setGestureMapping(
                    container.settings.value.activeProfileId,
                    gesture,
                    action,
                )
            }.onSuccess {
                val completed = if (verified) {
                    wizard.verifiedGestures + gesture
                } else {
                    wizard.verifiedGestures - gesture
                }
                val skipped = if (verified) {
                    wizard.skippedGestures - gesture
                } else {
                    wizard.skippedGestures + gesture
                }
                val configuredActions = wizard.configuredActions + (gesture to action)
                if (wizard.isLastGesture) {
                    mutableGestureWizard.value = wizard.copy(
                        configuredActions = configuredActions,
                        verifiedGestures = completed,
                        skippedGestures = skipped,
                        saving = false,
                        summaryVisible = true,
                    )
                } else {
                    val nextIndex = wizard.currentIndex + 1
                    val nextGesture = GestureType.entries[nextIndex]
                    mutableGestureWizard.value = wizard.copy(
                        currentIndex = nextIndex,
                        selectedAction = configuredActions.getValue(nextGesture),
                        configuredActions = configuredActions,
                        verifiedGestures = completed,
                        skippedGestures = skipped,
                        saving = false,
                    )
                    selectTrainerGesture(nextGesture)
                }
            }.onFailure { error ->
                mutableGestureWizard.update { it.copy(saving = false) }
                showError(error)
            }
        }
    }

    fun previousGestureWizardStep() {
        val wizard = mutableGestureWizard.value
        if (!wizard.active || wizard.saving || wizard.finishing) return
        if (wizard.summaryVisible) {
            val gesture = GestureType.entries.last()
            mutableGestureWizard.value = wizard.copy(
                currentIndex = GestureType.entries.lastIndex,
                selectedAction = wizard.configuredActions.getValue(gesture),
                summaryVisible = false,
            )
            selectTrainerGesture(gesture)
            return
        }
        if (wizard.currentIndex == 0) return
        val previousIndex = wizard.currentIndex - 1
        val gesture = GestureType.entries[previousIndex]
        mutableGestureWizard.value = wizard.copy(
            currentIndex = previousIndex,
            selectedAction = wizard.configuredActions.getValue(gesture),
        )
        selectTrainerGesture(gesture)
    }

    fun finishGestureWizard() {
        val wizard = mutableGestureWizard.value
        if (!wizard.active || !wizard.summaryVisible || wizard.finishing) return
        mutableGestureWizard.value = wizard.copy(finishing = true)
        viewModelScope.launch {
            runCatching { container.settingsRepository.completeGestureWizard() }
                .onSuccess { mutableGestureWizard.update { it.copy(completionSaved = true) } }
                .onFailure { error ->
                    mutableGestureWizard.update { it.copy(finishing = false) }
                    showError(error)
                }
        }
    }

    fun endGestureWizard() {
        cancelTrainerRecording(resetState = false)
        mutableTrainer.value = TrainerUiState()
        mutableGestureWizard.value = GestureWizardUiState()
    }

    private fun finishTrainerRecording(autoStopped: Boolean) {
        val selected = mutableTrainer.value.selectedGesture
        trainerJob = null
        container.runtime.setGestureActionsSuspended(false)
        val thresholds = container.settings.value.sensitivity.thresholds(
            container.settings.value.advancedThresholds,
        )
        val result = trainerAnalyzer.analyze(
            samples = trainerSamples.toList(),
            thresholds = thresholds,
            personalizedModel = container.settings.value.personalizedGestureModel,
        )
        val featureResult = trainerFeatureExtractor.extract(trainerSamples.toList())
        pendingTrainerFeatures = featureResult.features.takeIf { featureResult.qualityAccepted }
        val detected = result.events.firstOrNull { it.type == selected } ?: result.strongestEvent
        val learnedSampleCount = maxOf(
            mutableTrainer.value.learnedSampleCount,
            container.settings.value.personalizedGestureModel.sampleCountFor(selected),
        )
        val message = when {
            result.sampleCount < MIN_TRAINER_SAMPLES || result.durationMillis < MIN_TRAINER_DURATION_MILLIS ->
                "Nagranie jest za krótkie. Zachowaj krótki bezruch przed lub po jednym ruchu."

            !featureResult.qualityAccepted -> featureResult.message

            detected == null ->
                "Próbka jest dobra do uczenia. Bieżący detektor jej nie rozpoznał, ale możesz przypisać jej wybrany gest."

            detected.type == selected && autoStopped ->
                "Próbka jest dobra do uczenia i zgodna z detektorem. Nagranie zatrzymano automatycznie."

            detected.type == selected -> "Próbka jest dobra do uczenia i zgodna z detektorem."
            else -> "Próbka jest dobra do uczenia wybranego gestu. Bieżący detektor wskazał: ${detected.type.displayName}."
        }
        mutableTrainer.value = TrainerUiState(
            selectedGesture = selected,
            sampleCount = result.sampleCount,
            durationMillis = result.durationMillis,
            detectedGesture = detected?.type,
            confidence = detected?.confidence,
            peakGyroscopeDps = result.peakGyroscopeDps,
            accelerationRangeG = result.minimumAccelerationG..result.maximumAccelerationG,
            featureQuality = featureResult.qualityScore,
            featureReady = featureResult.qualityAccepted,
            learnedSampleCount = learnedSampleCount,
            message = message,
        )
    }

    private fun cancelTrainerRecording(resetState: Boolean) {
        trainerJob?.cancel()
        trainerJob = null
        trainerSamples.clear()
        pendingTrainerFeatures = null
        container.runtime.setGestureActionsSuspended(false)
        if (resetState) mutableTrainer.value = TrainerUiState()
    }

    private fun captureDurationMillis(): Long {
        val first = trainerSamples.firstOrNull()?.source?.timestampNanos ?: return 0L
        val last = trainerSamples.lastOrNull()?.source?.timestampNanos ?: return 0L
        return ((last - first) / 1_000_000L).coerceAtLeast(0L)
    }

    fun emitFakeGesture(gesture: GestureType) {
        if (!BuildConfig.DEBUG || !container.settings.value.developerMode) {
            showError(IllegalStateException("Generator Fake Triki wymaga buildu debug i Developer Mode."))
            return
        }
        container.runtime.injectDebugSamples(container.fakeTrikiDataSource.generate(gesture))
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

    private fun launchHandledResult(block: suspend () -> Result<*>) {
        viewModelScope.launch { runCatching { block().getOrThrow() }.onFailure(::showError) }
    }

    private fun showError(error: Throwable) {
        mutableUserMessage.value = error.message ?: "Wystąpił nieoczekiwany błąd."
    }

    override fun onCleared() {
        calibrationJob?.cancel()
        cancelTrainerRecording(resetState = true)
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
        const val TRAINER_MAX_DURATION_MILLIS = 15_000L
        const val TRAINER_PREROLL_SAMPLES = 45
        const val TRAINER_MAX_SAMPLES = 2_000
        const val MIN_TRAINER_SAMPLES = 55
        const val MIN_TRAINER_DURATION_MILLIS = 650L
    }
}
