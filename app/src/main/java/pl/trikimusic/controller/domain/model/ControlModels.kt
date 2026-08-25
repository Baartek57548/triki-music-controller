package pl.trikimusic.controller.domain.model

import kotlinx.serialization.Serializable

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
    PLAY("Odtwórz"),
    PAUSE("Wstrzymaj"),
    PLAY_PAUSE("Odtwórz / wstrzymaj"),
    NEXT("Następny utwór"),
    PREVIOUS("Poprzedni utwór"),
    LIKE("Polub utwór"),
    DISLIKE("Odrzuć utwór"),
    VOLUME_UP("Głośniej"),
    VOLUME_DOWN("Ciszej"),
    MUTE("Wycisz"),
    UNMUTE("Wyłącz wyciszenie"),
    STOP("Zatrzymaj"),
    NONE("Brak akcji"),
}

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
    val builtIn: Boolean = false,
    val buttonMappings: List<ButtonMapping> = defaultButtonMappings(),
) {
    fun actionFor(click: ButtonClickType): MediaAction =
        buttonMappings.firstOrNull { it.click == click }?.action ?: MediaAction.NONE
}
