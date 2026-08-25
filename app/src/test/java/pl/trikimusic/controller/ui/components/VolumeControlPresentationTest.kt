package pl.trikimusic.controller.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.trikimusic.controller.domain.model.FilteredSensorData
import pl.trikimusic.controller.domain.model.OrientationData
import pl.trikimusic.controller.domain.model.RawVector3
import pl.trikimusic.controller.domain.model.TrikiBleState
import pl.trikimusic.controller.domain.model.TrikiConnectionState
import pl.trikimusic.controller.domain.model.TrikiSensorData
import pl.trikimusic.controller.domain.model.Vector3
import pl.trikimusic.controller.runtime.RuntimeState
import pl.trikimusic.controller.ui.MainUiState

class VolumeControlPresentationTest {
    @Test
    fun `disconnected state takes priority over stale sensor readiness`() {
        val state = uiState(
            connectionState = TrikiConnectionState.DISCONNECTED,
            runtime = runtime(armed = true, level = true, still = true, tilt = 0f),
        )

        val presentation = state.volumeControlPresentation()

        assertEquals(VolumeGateState.NO_DATA, presentation.state)
        assertFalse(presentation.ready)
    }

    @Test
    fun `vertical and upside down poses provide distinct corrective guidance`() {
        val vertical = uiState(runtime = runtime(level = false, tilt = 90f))
        val upsideDown = uiState(runtime = runtime(level = false, tilt = 180f))

        assertEquals(VolumeGateState.NOT_LEVEL, vertical.volumeControlPresentation().state)
        assertEquals(VolumeGateState.UPSIDE_DOWN, upsideDown.volumeControlPresentation().state)
    }

    @Test
    fun `arming and ready are represented as separate states`() {
        val arming = uiState(runtime = runtime(level = true, still = true, progress = 0.5f))
        val ready = uiState(runtime = runtime(armed = true, level = true, still = false, progress = 1f))

        assertEquals(VolumeGateState.ARMING, arming.volumeControlPresentation().state)
        assertEquals(VolumeGateState.READY, ready.volumeControlPresentation().state)
        assertTrue(ready.volumeControlPresentation().ready)
    }

    private fun uiState(
        connectionState: TrikiConnectionState = TrikiConnectionState.READY,
        runtime: RuntimeState,
    ) = MainUiState(
        ble = TrikiBleState(connectionState = connectionState),
        runtime = runtime,
    )

    private fun runtime(
        armed: Boolean = false,
        level: Boolean,
        still: Boolean = false,
        progress: Float = 0f,
        tilt: Float = 0f,
    ) = RuntimeState(
        latestSample = sample(),
        volumeAccelerometerWithinTolerance = true,
        volumeOrientationLevel = level,
        volumeStillEnoughToArm = still,
        volumeControlArmed = armed,
        volumeArmingProgress = progress,
        volumeTiltDegrees = tilt,
    )

    private fun sample(): FilteredSensorData {
        val source = TrikiSensorData(
            frameIndex = 1L,
            timestampNanos = 1L,
            gyroscopeDps = Vector3(0f, 0f, 0f),
            accelerometerG = Vector3(0f, 0f, -1f),
            rawGyroscope = RawVector3(0, 0, 0),
            rawAccelerometer = RawVector3(0, 0, 0),
            status = 0,
        )
        return FilteredSensorData(
            source = source,
            gyroscopeDps = source.gyroscopeDps,
            accelerometerG = source.accelerometerG,
            orientation = OrientationData(),
        )
    }
}
