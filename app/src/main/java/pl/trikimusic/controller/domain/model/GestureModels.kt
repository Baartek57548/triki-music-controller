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
enum class ButtonClickType(
    val clickCount: Int,
    val displayName: String,
) {
    SINGLE(1, "Jeden klik"),
    DOUBLE(2, "Dwa kliknięcia"),
    TRIPLE(3, "Trzy kliknięcia"),
}

data class ButtonClickEvent(
    val type: ButtonClickType,
    val timestampNanos: Long,
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
data class ButtonMapping(
    val click: ButtonClickType,
    val action: MediaAction,
)

fun defaultButtonMappings(): List<ButtonMapping> = listOf(
    ButtonMapping(ButtonClickType.SINGLE, MediaAction.PLAY_PAUSE),
    ButtonMapping(ButtonClickType.DOUBLE, MediaAction.NEXT),
    ButtonMapping(ButtonClickType.TRIPLE, MediaAction.PREVIOUS),
)

@Serializable
data class ControlProfile(
    val id: String,
    val name: String,
    val mappings: List<GestureMapping>,
    val builtIn: Boolean = false,
    val buttonMappings: List<ButtonMapping> = defaultButtonMappings(),
) {
    fun actionFor(gesture: GestureType): MediaAction =
        mappings.firstOrNull { it.gesture == gesture }?.action ?: MediaAction.NONE

    fun actionFor(click: ButtonClickType): MediaAction =
        buttonMappings.firstOrNull { it.click == click }?.action ?: MediaAction.NONE
}
