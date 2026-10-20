package pl.trikimusic.controller.data.repository

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import pl.trikimusic.controller.domain.model.AppSettings
import pl.trikimusic.controller.domain.model.ButtonClickType
import pl.trikimusic.controller.domain.model.ButtonMapping
import pl.trikimusic.controller.domain.model.MediaAction
import pl.trikimusic.controller.domain.model.defaultProfiles

class SettingsNormalizerTest {
    @Test
    fun `saved built-in navigation mappings survive the production read normalizer`() {
        val profile = defaultProfiles().single().copy(buttonMappings = listOf(
            ButtonMapping(ButtonClickType.SINGLE, MediaAction.PLAY_PAUSE),
            ButtonMapping(ButtonClickType.DOUBLE, MediaAction.NEXT),
            ButtonMapping(ButtonClickType.TRIPLE, MediaAction.PREVIOUS),
        ))
        val settings = AppSettings(profiles = listOf(profile))
        val persisted = Json.encodeToString(AppSettings.serializer(), settings)
        val restored = Json.decodeFromString(AppSettings.serializer(), persisted).normalized()
        assertEquals(settings.profiles, restored.profiles)
    }

    @Test
    fun `invalid device address and rotation use safe defaults`() {
        val restored = AppSettings(knownDeviceAddress = "invalid", knownDeviceName = "Broken", rotationAngleDegrees = 0).normalized()
        assertNull(restored.knownDeviceAddress)
        assertNull(restored.knownDeviceName)
        assertEquals(200, restored.rotationAngleDegrees)
    }
}
