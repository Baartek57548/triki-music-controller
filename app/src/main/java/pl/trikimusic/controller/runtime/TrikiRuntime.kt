package pl.trikimusic.controller.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pl.trikimusic.controller.core.bluetooth.TrikiBleManager
import pl.trikimusic.controller.core.bluetooth.TrikiButtonInterpreter
import pl.trikimusic.controller.core.bluetooth.TrikiButtonProtocolMode
import pl.trikimusic.controller.core.bluetooth.ConnectionActivityLease
import pl.trikimusic.controller.core.logging.AppLogger
import pl.trikimusic.controller.core.sensor.SensorFilter
import pl.trikimusic.controller.core.gesture.FullRotationGestureDetector
import pl.trikimusic.controller.core.gesture.HoldGesturePhase
import pl.trikimusic.controller.core.gesture.RotationGestureDirection
import pl.trikimusic.controller.core.gesture.toInvertedCapsuleNavigationAction
import pl.trikimusic.controller.core.volume.GyroscopeVolumeController
import pl.trikimusic.controller.domain.model.AppSettings
import pl.trikimusic.controller.domain.model.ButtonClickEvent
import pl.trikimusic.controller.domain.model.FilteredSensorData
import pl.trikimusic.controller.domain.model.LogCategory
import pl.trikimusic.controller.domain.model.MediaAction
import pl.trikimusic.controller.domain.model.TrikiConnectionState
import pl.trikimusic.controller.domain.model.TrikiSensorData
import pl.trikimusic.controller.domain.repository.SettingsRepository
import pl.trikimusic.controller.domain.usecase.ActionMapper

data class RuntimeState(
    val latestSample: FilteredSensorData? = null,
    val history: List<FilteredSensorData> = emptyList(),
    val lastButtonClick: ButtonClickEvent? = null,
    val lastVolumeChangeTimestampNanos: Long? = null,
    val lastRotationGestureTimestampNanos: Long? = null,
    val lastAction: MediaAction? = null,
    val lastActionError: String? = null,
    val volumeSensorValid: Boolean = false,
    val volumeWithinTiltRange: Boolean = false,
    val volumeAccelerationStable: Boolean = false,
    val volumeTiltStable: Boolean = false,
    val volumeStabilizationProgress: Float = 0f,
    val volumeTiltDegrees: Float = 180f,
    val volumeGyroscopeZDps: Float = 0f,
    val rotationGesturePhase: HoldGesturePhase = HoldGesturePhase.IDLE,
    val rotationGestureDirection: RotationGestureDirection? = null,
    val rotationGestureProgress: Float = 0f,
    val rotationGestureFaceDown: Boolean = false,
    val buttonProtocolMode: TrikiButtonProtocolMode = TrikiButtonProtocolMode.UNKNOWN,
)

class TrikiRuntime(
    private val scope: CoroutineScope,
    private val bleManager: TrikiBleManager,
    settingsRepository: SettingsRepository,
    private val actionMapper: ActionMapper,
    private val logger: AppLogger,
) {
    private val sensorFilter = SensorFilter()
    private val volumeController = GyroscopeVolumeController()
    private val rotationGestureDetector = FullRotationGestureDetector()
    private val buttonInterpreter = TrikiButtonInterpreter()
    private val connectionActivityLease = ConnectionActivityLease()
    private val mutableState = MutableStateFlow(RuntimeState())
    private val mutableButtonEvents = MutableSharedFlow<ButtonClickEvent>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val mutableFilteredSamples = MutableSharedFlow<FilteredSensorData>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private var settings = AppSettings()
    private var reportedButtonProtocolMode = TrikiButtonProtocolMode.UNKNOWN
    private var connectionWasReady = false

    val state: StateFlow<RuntimeState> = mutableState.asStateFlow()
    val buttonEvents = mutableButtonEvents.asSharedFlow()
    val filteredSamples = mutableFilteredSamples.asSharedFlow()

    init {
        scope.launch {
            settingsRepository.settings.collectLatest { value ->
                val calibrationChanged = value.calibration != settings.calibration
                val connectionModeChanged = value.connectOnlyWhenNeeded != settings.connectOnlyWhenNeeded
                settings = value
                bleManager.setConnectOnlyWhenNeeded(value.connectOnlyWhenNeeded)
                bleManager.setRawCaptureEnabled(value.developerMode)
                if (calibrationChanged) resetProcessing()
                else if (connectionModeChanged) connectionActivityLease.reset()
            }
        }
        scope.launch {
            bleManager.samples.collect(::consume)
        }
        scope.launch {
            bleManager.state.collectLatest { bleState ->
                val isReady = bleState.connectionState == TrikiConnectionState.READY
                if (isReady != connectionWasReady) connectionActivityLease.reset()
                connectionWasReady = isReady
                if (
                    !isReady &&
                    mutableState.value.latestSample != null
                ) {
                    resetProcessing()
                }
            }
        }
    }

    fun injectDebugSamples(samples: List<TrikiSensorData>) {
        samples.forEach(::consume)
    }

    fun resetProcessing() {
        sensorFilter.reset()
        volumeController.reset()
        rotationGestureDetector.reset()
        buttonInterpreter.reset()
        connectionActivityLease.reset()
        reportedButtonProtocolMode = TrikiButtonProtocolMode.UNKNOWN
        mutableState.update {
            it.copy(
                history = emptyList(),
                latestSample = null,
                volumeSensorValid = false,
                volumeWithinTiltRange = false,
                volumeAccelerationStable = false,
                volumeTiltStable = false,
                volumeStabilizationProgress = 0f,
                volumeTiltDegrees = 180f,
                volumeGyroscopeZDps = 0f,
                rotationGesturePhase = HoldGesturePhase.IDLE,
                rotationGestureDirection = null,
                rotationGestureProgress = 0f,
                rotationGestureFaceDown = false,
                buttonProtocolMode = TrikiButtonProtocolMode.UNKNOWN,
            )
        }
    }

    private fun consume(sample: TrikiSensorData) {
        val filtered = sensorFilter.process(sample, settings.calibration)
        val buttonEvent = buttonInterpreter.process(sample)
        val rotationGestureResult = rotationGestureDetector.process(filtered)
        val explicitConnectionActivity =
            buttonEvent != null ||
                buttonInterpreter.isPressed ||
                rotationGestureResult.phase in setOf(
                    HoldGesturePhase.HOLDING,
                    HoldGesturePhase.READY,
                    HoldGesturePhase.TRACKING,
                    HoldGesturePhase.COMPLETING,
                    HoldGesturePhase.TRIGGERED,
                )
        if (
            settings.connectOnlyWhenNeeded &&
            connectionActivityLease.observe(filtered, explicitConnectionActivity)
        ) {
            logger.log(LogCategory.BLE, "Brak aktywności przez 12 s; zamykam GATT i czekam na kolejne wybudzenie.")
            scope.launch {
                bleManager.parkUntilWake().onFailure { error ->
                    connectionActivityLease.reset()
                    logger.log(LogCategory.BLE, "Nie udało się przejść w tryb połączenia na żądanie.", error)
                }
            }
        }
        val buttonMode = buttonInterpreter.protocolMode
        if (buttonMode != reportedButtonProtocolMode) {
            reportedButtonProtocolMode = buttonMode
            if (buttonMode != TrikiButtonProtocolMode.UNKNOWN) {
                logger.log(LogCategory.PROTOCOL, "Pole status: ${buttonMode.displayName}.")
            }
        }
        mutableFilteredSamples.tryEmit(filtered)
        mutableState.update { current ->
            current.copy(
                latestSample = filtered,
                history = (current.history + filtered).takeLast(MAX_HISTORY_SAMPLES),
                buttonProtocolMode = buttonMode,
                rotationGesturePhase = rotationGestureResult.phase,
                rotationGestureDirection = rotationGestureResult.direction,
                rotationGestureProgress = rotationGestureResult.stabilizationProgress,
                rotationGestureFaceDown = rotationGestureResult.faceDown,
            )
        }
        if (buttonEvent == null && rotationGestureResult.triggered) {
            volumeController.reset()
            val mediaAction = rotationGestureResult.direction
                ?.toInvertedCapsuleNavigationAction()
                ?: MediaAction.NONE
            val execution = actionMapper.execute(mediaAction)
            logger.log(
                LogCategory.CONTROL,
                "ROTATE_${rotationGestureResult.direction?.name ?: "UNKNOWN"}: ${execution.action.name}",
                execution.result.exceptionOrNull(),
            )
            mutableState.update {
                it.copy(
                    lastRotationGestureTimestampNanos = filtered.source.timestampNanos,
                    lastAction = execution.action,
                    lastActionError = execution.result.exceptionOrNull()?.message,
                )
            }
            return
        }
        if (buttonEvent != null || buttonInterpreter.shouldSuppressMotionControl) {
            volumeController.reset()
        }
        if (buttonEvent != null) {
            mutableButtonEvents.tryEmit(buttonEvent)
            val execution = actionMapper.execute(buttonEvent, settings.activeProfile)
            logger.log(
                LogCategory.CONTROL,
                "BUTTON_${buttonEvent.type.name}: ${execution.action.name}",
                execution.result.exceptionOrNull(),
            )
            mutableState.update {
                it.copy(
                    lastButtonClick = buttonEvent,
                    lastAction = execution.action,
                    lastActionError = execution.result.exceptionOrNull()?.message,
                )
            }
            return
        }
        if (buttonInterpreter.shouldSuppressMotionControl) {
            mutableState.update {
                it.copy(
                    volumeSensorValid = false,
                    volumeWithinTiltRange = false,
                    volumeAccelerationStable = false,
                    volumeTiltStable = false,
                    volumeStabilizationProgress = 0f,
                    volumeTiltDegrees = 180f,
                    volumeGyroscopeZDps = filtered.gyroscopeDps.z,
                )
            }
            return
        }

        val volumeResult = volumeController.process(filtered)
        mutableState.update {
            it.copy(
                volumeSensorValid = volumeResult.sensorValid,
                volumeWithinTiltRange = volumeResult.withinTiltRange,
                volumeAccelerationStable = volumeResult.accelerationStable,
                volumeTiltStable = volumeResult.tiltStable,
                volumeStabilizationProgress = volumeResult.stabilizationProgress,
                volumeTiltDegrees = volumeResult.tiltDegrees,
                volumeGyroscopeZDps = volumeResult.gyroscopeZDps,
            )
        }
        volumeResult.action?.let { action ->
            val execution = actionMapper.execute(action)
            logger.log(
                LogCategory.CONTROL,
                "GYRO_Z=${"%+.1f".format(volumeResult.gyroscopeZDps)} dps: ${execution.action.name}",
                execution.result.exceptionOrNull(),
            )
            mutableState.update {
                it.copy(
                    lastVolumeChangeTimestampNanos = filtered.source.timestampNanos,
                    lastAction = execution.action,
                    lastActionError = execution.result.exceptionOrNull()?.message,
                )
            }
        }
    }

    private companion object {
        const val MAX_HISTORY_SAMPLES = 360
    }
}
