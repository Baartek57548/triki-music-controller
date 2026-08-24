package pl.trikimusic.controller.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pl.trikimusic.controller.core.bluetooth.TrikiBleManager
import pl.trikimusic.controller.core.gesture.GestureEngine
import pl.trikimusic.controller.core.gesture.SensorFilter
import pl.trikimusic.controller.core.logging.AppLogger
import pl.trikimusic.controller.domain.model.AppSettings
import pl.trikimusic.controller.domain.model.FilteredSensorData
import pl.trikimusic.controller.domain.model.GestureEvent
import pl.trikimusic.controller.domain.model.LogCategory
import pl.trikimusic.controller.domain.model.MediaAction
import pl.trikimusic.controller.domain.model.TrikiSensorData
import pl.trikimusic.controller.domain.model.thresholds
import pl.trikimusic.controller.domain.repository.SettingsRepository
import pl.trikimusic.controller.domain.usecase.ActionMapper

data class RuntimeState(
    val latestSample: FilteredSensorData? = null,
    val history: List<FilteredSensorData> = emptyList(),
    val lastGesture: GestureEvent? = null,
    val lastAction: MediaAction? = null,
    val lastActionError: String? = null,
)

class TrikiRuntime(
    private val scope: CoroutineScope,
    private val bleManager: TrikiBleManager,
    settingsRepository: SettingsRepository,
    private val actionMapper: ActionMapper,
    private val logger: AppLogger,
) {
    private val sensorFilter = SensorFilter()
    private val gestureEngine = GestureEngine()
    private val mutableState = MutableStateFlow(RuntimeState())
    private val mutableEvents = MutableSharedFlow<GestureEvent>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private var settings = AppSettings()

    val state: StateFlow<RuntimeState> = mutableState.asStateFlow()
    val events: SharedFlow<GestureEvent> = mutableEvents.asSharedFlow()

    init {
        scope.launch {
            settingsRepository.settings.collectLatest { value ->
                val calibrationChanged = value.calibration != settings.calibration
                val sensitivityChanged = value.sensitivity != settings.sensitivity || value.advancedThresholds != settings.advancedThresholds
                settings = value
                if (calibrationChanged || sensitivityChanged) resetProcessing()
            }
        }
        scope.launch {
            bleManager.samples.collect(::consume)
        }
    }

    fun injectDebugSamples(samples: List<TrikiSensorData>) {
        samples.forEach(::consume)
    }

    fun resetProcessing() {
        sensorFilter.reset()
        gestureEngine.reset()
        mutableState.update { it.copy(history = emptyList(), latestSample = null) }
    }

    private fun consume(sample: TrikiSensorData) {
        val thresholds = settings.sensitivity.thresholds(settings.advancedThresholds)
        val filtered = sensorFilter.process(sample, settings.calibration, thresholds)
        mutableState.update { current ->
            current.copy(
                latestSample = filtered,
                history = (current.history + filtered).takeLast(MAX_HISTORY_SAMPLES),
            )
        }
        gestureEngine.process(filtered, thresholds).forEach { event ->
            mutableEvents.tryEmit(event)
            val execution = actionMapper.execute(event, settings.activeProfile)
            logger.log(
                LogCategory.GESTURE,
                "${event.type.name}: ${execution.action.name}, confidence=${"%.2f".format(event.confidence)}",
                execution.result.exceptionOrNull(),
            )
            mutableState.update {
                it.copy(
                    lastGesture = event,
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
