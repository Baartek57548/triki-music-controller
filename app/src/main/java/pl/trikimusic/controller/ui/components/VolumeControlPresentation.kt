package pl.trikimusic.controller.ui.components

import pl.trikimusic.controller.domain.model.TrikiConnectionState
import pl.trikimusic.controller.ui.MainUiState

enum class VolumeGateState {
    NO_DATA,
    SENSOR_INVALID,
    UPSIDE_DOWN,
    OUTSIDE_TILT_RANGE,
    STABILIZING,
    READY,
}

data class VolumeControlPresentation(
    val state: VolumeGateState,
    val title: String,
    val instruction: String,
    val ready: Boolean,
)

fun MainUiState.volumeControlPresentation(): VolumeControlPresentation = when {
    ble.connectionState != TrikiConnectionState.READY || runtime.latestSample == null -> VolumeControlPresentation(
        VolumeGateState.NO_DATA,
        "Regulator nieaktywny",
        "Połącz Triki, aby rozpocząć sterowanie głośnością.",
        false,
    )

    !runtime.volumeSensorValid -> VolumeControlPresentation(
        VolumeGateState.SENSOR_INVALID,
        "Brak prawidłowych danych IMU",
        "Sprawdź połączenie z Triki i transmisję czujników.",
        false,
    )

    runtime.volumeTiltDegrees >= UPSIDE_DOWN_TILT_DEGREES -> VolumeControlPresentation(
        VolumeGateState.UPSIDE_DOWN,
        "Triki jest odwrócone",
        "Ustaw kapsel górną stroną do góry.",
        false,
    )

    !runtime.volumeWithinTiltRange -> VolumeControlPresentation(
        VolumeGateState.OUTSIDE_TILT_RANGE,
        "Przechył poza zakresem",
        "Utrzymuj Triki w zakresie 0–25°; teraz ${runtime.volumeTiltDegrees.rounded()}°.",
        false,
    )

    !runtime.volumeTiltStable -> VolumeControlPresentation(
        VolumeGateState.STABILIZING,
        "Stabilizacja kąta ${runtime.volumeStabilizationProgress.percent()}%",
        "Utrzymuj przechył 0–25° przez 2 sekundy. Kapsel może być w ruchu.",
        false,
    )

    else -> VolumeControlPresentation(
        VolumeGateState.READY,
        "Regulator gotowy",
        "Obracaj kapsel wokół osi Z. Nie musisz zatrzymywać go ani odkładać na powierzchnię.",
        true,
    )
}

private fun Float.rounded(): Int = if (isFinite()) Math.round(this) else 180

private fun Float.percent(): Int = (coerceIn(0f, 1f) * 100f).toInt().coerceIn(0, 100)

private const val UPSIDE_DOWN_TILT_DEGREES = 135f
