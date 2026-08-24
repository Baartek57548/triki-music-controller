package pl.trikimusic.controller.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
enum class GestureType(val displayName: String) {
    @SerialName("TILT_LEFT")
    LEAN("Przechylenie w dowolną stronę"),
    @SerialName("TILT_RIGHT")
    SLIDE("Płaskie przesunięcie"),
    SHAKE("Potrząśnięcie"),
    DOUBLE_SHAKE("Podwójne potrząśnięcie"),
    FLIP("Odwrócenie"),
    ROTATE_LEFT("Obrót w lewo"),
    ROTATE_RIGHT("Obrót w prawo"),
    @SerialName("THROW_UP")
    TAP("Stuknięcie / odstawienie"),
}

data class GestureEvent(
    val type: GestureType,
    val timestampNanos: Long,
    val confidence: Float,
    val magnitude: Float,
)

@Serializable
enum class MediaAction(val displayName: String) {
    PLAY("Play"),
    PAUSE("Pause"),
    PLAY_PAUSE("Play / Pause"),
    NEXT("Następny utwór"),
    PREVIOUS("Poprzedni utwór"),
    VOLUME_UP("Głośniej"),
    VOLUME_DOWN("Ciszej"),
    MUTE("Wycisz"),
    UNMUTE("Wyłącz wyciszenie"),
    STOP("Stop"),
    NONE("Brak akcji"),
}

@Serializable
data class GestureMapping(
    val gesture: GestureType,
    val action: MediaAction,
)

@Serializable
data class ControlProfile(
    val id: String,
    val name: String,
    val mappings: List<GestureMapping>,
    val builtIn: Boolean = false,
) {
    fun actionFor(gesture: GestureType): MediaAction =
        mappings.firstOrNull { it.gesture == gesture }?.action ?: MediaAction.NONE
}
