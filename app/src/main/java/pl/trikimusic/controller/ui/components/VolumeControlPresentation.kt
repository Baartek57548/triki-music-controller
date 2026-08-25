package pl.trikimusic.controller.ui.components

import pl.trikimusic.controller.domain.model.TrikiConnectionState
import pl.trikimusic.controller.ui.MainUiState

enum class VolumeGateState {
    NO_DATA,
    ACCELERATION_OUTSIDE_TOLERANCE,
    UPSIDE_DOWN,
    NOT_LEVEL,
    MOVING,
    ARMING,
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

    !runtime.volumeAccelerometerWithinTolerance -> VolumeControlPresentation(
        VolumeGateState.ACCELERATION_OUTSIDE_TOLERANCE,
        "Wykryto ruch urządzenia",
        "Odłóż Triki na stabilną powierzchnię i przestań go poruszać.",
        false,
    )

    runtime.volumeTiltDegrees >= UPSIDE_DOWN_TILT_DEGREES -> VolumeControlPresentation(
        VolumeGateState.UPSIDE_DOWN,
        "Triki jest odwrócone",
        "Połóż kapsel górną stroną do góry.",
        false,
    )

    !runtime.volumeOrientationLevel -> VolumeControlPresentation(
        VolumeGateState.NOT_LEVEL,
        "Połóż Triki bardziej płasko",
        "Regulacja działa przy przechyle do około 25°; teraz ${runtime.volumeTiltDegrees.rounded()}°.",
        false,
    )

    runtime.volumeControlArmed -> VolumeControlPresentation(
        VolumeGateState.READY,
        "Regulator gotowy",
        "Obracaj kapsel płasko wokół osi Z: dodatnia wartość podgłaśnia, ujemna ścisza.",
        true,
    )

    !runtime.volumeStillEnoughToArm -> VolumeControlPresentation(
        VolumeGateState.MOVING,
        "Nie poruszaj Triki",
        "Zatrzymaj urządzenie; odliczanie zacznie się, gdy żyroskop będzie nieruchomy.",
        false,
    )

    else -> VolumeControlPresentation(
        VolumeGateState.ARMING,
        "Stabilizacja ${runtime.volumeArmingProgress.percent()}%",
        "Nie dotykaj Triki przez około sekundę.",
        false,
    )
}

private fun Float.percent(): Int = (coerceIn(0f, 1f) * 100f).toInt().coerceIn(0, 100)

private fun Float.rounded(): Int = if (isFinite()) Math.round(this) else 180

private const val UPSIDE_DOWN_TILT_DEGREES = 135f
