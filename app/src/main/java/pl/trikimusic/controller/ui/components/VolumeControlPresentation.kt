package pl.trikimusic.controller.ui.components

import pl.trikimusic.controller.domain.model.TrikiConnectionState
import pl.trikimusic.controller.ui.MainUiState

enum class VolumeGateState {
    NO_DATA,
    SENSOR_INVALID,
    UPSIDE_DOWN,
    OUTSIDE_TILT_RANGE,
    SUDDEN_MOTION,
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
        "Sprawdź połączenie",
        "Nie otrzymuję prawidłowych danych ruchu z Triki.",
        false,
    )

    runtime.volumeTiltDegrees >= UPSIDE_DOWN_TILT_DEGREES -> VolumeControlPresentation(
        VolumeGateState.UPSIDE_DOWN,
        "Tryb zmiany utworu",
        "Ustabilizuj odwrócone Triki, a następnie obróć je o ${settings.rotationAngleDegrees}°.",
        false,
    )

    !runtime.volumeWithinTiltRange -> VolumeControlPresentation(
        VolumeGateState.OUTSIDE_TILT_RANGE,
        "Ustaw Triki prawie poziomo",
        "Utrzymuj kapsel górną stroną do góry w zakresie 0–25°.",
        false,
    )

    !runtime.volumeAccelerationStable -> VolumeControlPresentation(
        VolumeGateState.SUDDEN_MOTION,
        "Ustabilizuj Triki",
        "Gwałtowny ruch przerwał przygotowanie. Trzymaj kapsel spokojnie przez 2 sekundy.",
        false,
    )

    !runtime.volumeTiltStable -> VolumeControlPresentation(
        VolumeGateState.STABILIZING,
        "Przygotowywanie sterowania…",
        "Trzymaj Triki prawie poziomo i unikaj gwałtownych ruchów.",
        false,
    )

    else -> VolumeControlPresentation(
        VolumeGateState.READY,
        "Gotowe",
        "Obracaj kapsel łagodnie wokół osi Z, aby zmieniać głośność.",
        true,
    )
}

private const val UPSIDE_DOWN_TILT_DEGREES = 135f
