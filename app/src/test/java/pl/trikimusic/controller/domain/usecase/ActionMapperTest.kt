package pl.trikimusic.controller.domain.usecase

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.trikimusic.controller.domain.model.ControlProfile
import pl.trikimusic.controller.domain.model.ButtonClickEvent
import pl.trikimusic.controller.domain.model.ButtonClickType
import pl.trikimusic.controller.domain.model.ButtonMapping
import pl.trikimusic.controller.domain.model.GestureEvent
import pl.trikimusic.controller.domain.model.GestureMapping
import pl.trikimusic.controller.domain.model.GestureType
import pl.trikimusic.controller.domain.model.MediaAction
import pl.trikimusic.controller.domain.model.MediaSessionState
import pl.trikimusic.controller.domain.repository.MediaControllerGateway

class ActionMapperTest {
    @Test
    fun `maps gesture using active profile and delegates action`() {
        val gateway = FakeGateway()
        val mapper = ActionMapper(gateway)
        val profile = ControlProfile(
            "test",
            "Test",
            listOf(GestureMapping(GestureType.SLIDE, MediaAction.NEXT)),
        )

        val execution = mapper.execute(GestureEvent(GestureType.SLIDE, 1L, 1f, 30f), profile)

        assertEquals(MediaAction.NEXT, execution.action)
        assertEquals(listOf(MediaAction.NEXT), gateway.actions)
        assertTrue(execution.result.isSuccess)
    }

    @Test
    fun `none mapping does not call media gateway`() {
        val gateway = FakeGateway()
        val mapper = ActionMapper(gateway)
        val profile = ControlProfile("empty", "Empty", emptyList())

        val execution = mapper.execute(GestureEvent(GestureType.FLIP, 1L, 1f, 1f), profile)

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
            mappings = emptyList(),
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
