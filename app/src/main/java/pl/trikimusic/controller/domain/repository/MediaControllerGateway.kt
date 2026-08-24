package pl.trikimusic.controller.domain.repository

import kotlinx.coroutines.flow.StateFlow
import pl.trikimusic.controller.domain.model.MediaAction
import pl.trikimusic.controller.domain.model.MediaSessionState

interface MediaControllerGateway {
    val state: StateFlow<MediaSessionState>

    fun refresh()
    fun execute(action: MediaAction): Result<Unit>
}
