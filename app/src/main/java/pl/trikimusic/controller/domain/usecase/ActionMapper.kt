package pl.trikimusic.controller.domain.usecase

import pl.trikimusic.controller.domain.model.ControlProfile
import pl.trikimusic.controller.domain.model.ButtonClickEvent
import pl.trikimusic.controller.domain.model.GestureEvent
import pl.trikimusic.controller.domain.model.MediaAction
import pl.trikimusic.controller.domain.repository.MediaControllerGateway

class ActionMapper(
    private val mediaController: MediaControllerGateway,
) {
    fun execute(event: GestureEvent, profile: ControlProfile): ActionExecution {
        val action = profile.actionFor(event.type)
        if (action == MediaAction.NONE) return ActionExecution(action, Result.success(Unit))
        return ActionExecution(action, mediaController.execute(action))
    }

    fun execute(event: ButtonClickEvent, profile: ControlProfile): ActionExecution {
        val action = profile.actionFor(event.type)
        if (action == MediaAction.NONE) return ActionExecution(action, Result.success(Unit))
        return ActionExecution(action, mediaController.execute(action))
    }
}

data class ActionExecution(
    val action: MediaAction,
    val result: Result<Unit>,
)
