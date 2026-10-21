package pl.trikimusic.controller.core.bluetooth

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import pl.trikimusic.controller.core.brightness.EdgePoseBrightnessController
import pl.trikimusic.controller.core.volume.GyroscopeVolumeController
import pl.trikimusic.controller.data.repository.normalized
import pl.trikimusic.controller.domain.model.*

class PowerAndSensitivityTest {
    @Test
    fun `default and migrated settings use Normal thirty minutes and preserve mappings`() {
        val json = Json { ignoreUnknownKeys = true }
        for (legacyEco in listOf(true, false)) {
            val settings = json.decodeFromString(AppSettings.serializer(),
                """{"connectOnlyWhenNeeded":$legacyEco,"knownDeviceAddress":"AA:BB:CC:DD:EE:FF","knownDeviceName":"Triki test","theme":"DARK"}""").normalized()
            assertEquals(ConnectionPowerMode.NORMAL, settings.connectionPowerMode)
            assertEquals(1_800_000_000_000L, settings.connectionPowerMode.idleTimeoutNanos)
            assertTrue(settings.connectOnlyWhenNeeded)
            assertEquals("Triki test", settings.knownDeviceName)
            assertEquals(ThemePreference.DARK, settings.theme)
        }
        assertFalse(AppSettings(connectionPowerMode = ConnectionPowerMode.ALWAYS_ON).connectOnlyWhenNeeded)
    }

    @Test
    fun `each timeout parks only at its own boundary`() {
        for (mode in ConnectionPowerMode.entries.filter { it.idleMinutes != null }) {
            val timeout = mode.idleMinutes!! * 60_000_000_000L
            assertEquals(timeout, mode.idleTimeoutNanos)
            val lease = ConnectionActivityLease(timeout)
            assertFalse(lease.observe(0L, false))
            assertFalse(lease.observe(timeout - 1L, false))
            assertTrue(lease.observe(timeout, false))
            assertFalse(lease.observe(timeout + 1L, false))
        }
    }

    @Test
    fun `changing timeout starts fresh idle window and invalid settings recover`() {
        val lease = ConnectionActivityLease(120L)
        lease.observe(0L, false)
        lease.configureTimeout(1_800L)
        assertFalse(lease.observe(150L, false))
        assertFalse(lease.observe(1_949L, false))
        assertTrue(lease.observe(1_950L, false))
        val settings = AppSettings(volumeSensitivityPercent = 0, brightnessSensitivityPercent = 500).normalized()
        assertEquals(100, settings.volumeSensitivityPercent)
        assertEquals(100, settings.brightnessSensitivityPercent)
    }

    @Test
    fun `sensitivities independently change reaction without amplifying noise`() {
        assertTrue(volumeSteps(0.5f, 60f) < volumeSteps(1f, 60f))
        assertTrue(volumeSteps(1f, 60f) < volumeSteps(2f, 60f))
        assertEquals(0, volumeSteps(2f, 10f))
        assertTrue(brightnessChange(0.5f) < brightnessChange(1f))
        assertTrue(brightnessChange(1f) < brightnessChange(2f))
    }

    @Test
    fun `controllers reject nonfinite and out of range sensitivity`() {
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, 0.49f, 2.01f)) {
            assertTrue(runCatching { GyroscopeVolumeController().sensitivity = value }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { EdgePoseBrightnessController().sensitivity = value }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    private fun volumeSteps(gain: Float, speed: Float): Int {
        val controller = GyroscopeVolumeController(GyroscopeVolumeController.Configuration(tiltStabilizationMillis = 0L)).apply { sensitivity = gain }
        return (0 until 150).count { controller.process(sample(it * 20_000_000L, Vector3(0f, 0f, -1f), speed)).action == MediaAction.VOLUME_UP }
    }

    private fun brightnessChange(gain: Float): Float {
        val controller = EdgePoseBrightnessController(50f).apply { sensitivity = gain }
        return (0 until 100).map { controller.process(sample(it * 20_000_000L, Vector3(0f, 1f, 0f), 20f), true) }.last().brightnessPercent - 50f
    }

    private fun sample(time: Long, acceleration: Vector3, speed: Float): FilteredSensorData {
        val source = TrikiSensorData(time / 20_000_000L, time, Vector3(0f, 0f, speed), acceleration, RawVector3(0, 0, 0), RawVector3(0, 0, 0), 0)
        return FilteredSensorData(source, source.gyroscopeDps, acceleration, OrientationData())
    }
}
