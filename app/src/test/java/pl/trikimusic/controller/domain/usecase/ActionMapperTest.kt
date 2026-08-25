package pl.trikimusic.controller.domain.usecase

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.trikimusic.controller.domain.model.ControlProfile
import pl.trikimusic.controller.domain.model.ButtonClickEvent
import pl.trikimusic.controller.domain.model.ButtonClickType
import pl.trikimusic.controller.domain.model.ButtonMapping
import pl.trikimusic.controller.domain.model.MediaAction
import pl.trikimusic.controller.domain.model.MediaSessionState
import pl.trikimusic.controller.domain.repository.MediaControllerGateway

class ActionMapperTest {
    @Test
    fun `none action does not call media gateway`() {
        val gateway = FakeGateway()
        val mapper = ActionMapper(gateway)

        val execution = mapper.execute(MediaAction.NONE)

        assertEquals(MediaAction.NONE, execution.action)
        assertTrue(gateway.actions.isEmpty())
    }

    @Test
    fun `maps physical button click using active profile`() {
        val gateway = FakeGateway()
        val mapper = ActionMapper(gateway)
        val profile = ControlProfile(
            id = "buttons",
            name = "Buttons",
            buttonMappings = listOf(ButtonMapping(ButtonClickType.DOUBLE, MediaAction.NEXT)),
        )

        val execution = mapper.execute(ButtonClickEvent(ButtonClickType.DOUBLE, 2L), profile)

        assertEquals(MediaAction.NEXT, execution.action)
        assertEquals(listOf(MediaAction.NEXT), gateway.actions)
        assertTrue(execution.result.isSuccess)
    }

    private class FakeGateway : MediaControllerGateway {
        override val state = MutableStateFlow(MediaSessionState())
        val actions = mutableListOf<MediaAction>()
        override fun refresh() = Unit
        override fun execute(action: MediaAction): Result<Unit> {
            actions += action
            return Result.success(Unit)
        }
    }
}
