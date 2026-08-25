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
            runtime = runtime(withinRange = true, tilt = 0f),
        )

        val presentation = state.volumeControlPresentation()

        assertEquals(VolumeGateState.NO_DATA, presentation.state)
        assertFalse(presentation.ready)
    }

    @Test
    fun `vertical and upside down poses provide distinct corrective guidance`() {
        val vertical = uiState(runtime = runtime(withinRange = false, tilt = 90f))
        val upsideDown = uiState(runtime = runtime(withinRange = false, tilt = 180f))

        assertEquals(VolumeGateState.OUTSIDE_TILT_RANGE, vertical.volumeControlPresentation().state)
        assertEquals(VolumeGateState.UPSIDE_DOWN, upsideDown.volumeControlPresentation().state)
    }

    @Test
    fun `valid sample in tilt range is ready without stationary state`() {
        val ready = uiState(runtime = runtime(withinRange = true, tilt = 25f))

        val presentation = ready.volumeControlPresentation()

        assertEquals(VolumeGateState.READY, presentation.state)
        assertTrue(presentation.ready)
        assertTrue(presentation.instruction.contains("Nie musisz zatrzymywać"))
    }

    @Test
    fun `invalid IMU sample has dedicated error state`() {
        val invalid = uiState(runtime = runtime(sensorValid = false, withinRange = false, tilt = 180f))

        val presentation = invalid.volumeControlPresentation()

        assertEquals(VolumeGateState.SENSOR_INVALID, presentation.state)
        assertFalse(presentation.ready)
    }

    private fun uiState(
        connectionState: TrikiConnectionState = TrikiConnectionState.READY,
        runtime: RuntimeState,
    ) = MainUiState(
        ble = TrikiBleState(connectionState = connectionState),
        runtime = runtime,
    )

    private fun runtime(
        sensorValid: Boolean = true,
        withinRange: Boolean,
        tilt: Float,
    ) = RuntimeState(
        latestSample = sample(),
        volumeSensorValid = sensorValid,
        volumeWithinTiltRange = withinRange,
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
