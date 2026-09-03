package pl.trikimusic.controller.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.trikimusic.controller.domain.model.AppSettings
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
            runtime = runtime(withinRange = true, stable = true, tilt = 0f),
        )

        val presentation = state.volumeControlPresentation()

        assertEquals(VolumeGateState.NO_DATA, presentation.state)
        assertFalse(presentation.ready)
    }

    @Test
    fun `vertical and upside down poses provide distinct corrective guidance`() {
        val vertical = uiState(runtime = runtime(withinRange = false, tilt = 90f))
        val upsideDown = uiState(
            settings = AppSettings(rotationAngleDegrees = 200),
            runtime = runtime(withinRange = false, tilt = 180f),
        )
        val upsideDownCustom = uiState(
            settings = AppSettings(rotationAngleDegrees = 315),
            runtime = runtime(withinRange = false, tilt = 180f),
        )

        assertEquals(VolumeGateState.OUTSIDE_TILT_RANGE, vertical.volumeControlPresentation().state)

        val presentationDefault = upsideDown.volumeControlPresentation()
        assertEquals(VolumeGateState.UPSIDE_DOWN, presentationDefault.state)
        assertTrue(presentationDefault.instruction.contains("200°"))

        val presentationCustom = upsideDownCustom.volumeControlPresentation()
        assertEquals(VolumeGateState.UPSIDE_DOWN, presentationCustom.state)
        assertTrue(presentationCustom.instruction.contains("315°"))
    }

    @Test
    fun `valid sample in tilt range is ready without resting on a surface`() {
        val ready = uiState(runtime = runtime(withinRange = true, stable = true, tilt = 25f))

        val presentation = ready.volumeControlPresentation()

        assertEquals(VolumeGateState.READY, presentation.state)
        assertTrue(presentation.ready)
        assertTrue(presentation.instruction.contains("łagodnie"))
    }

    @Test
    fun `valid angle reports stabilization guidance`() {
        val stabilizing = uiState(
            runtime = runtime(withinRange = true, stable = false, progress = 0.5f, tilt = 12f),
        )

        val presentation = stabilizing.volumeControlPresentation()

        assertEquals(VolumeGateState.STABILIZING, presentation.state)
        assertEquals("Przygotowywanie sterowania…", presentation.title)
        assertTrue(presentation.instruction.contains("unikaj gwałtownych ruchów"))
        assertFalse(presentation.ready)
    }

    @Test
    fun `invalid IMU sample has dedicated error state`() {
        val invalid = uiState(runtime = runtime(sensorValid = false, withinRange = false, tilt = 180f))

        val presentation = invalid.volumeControlPresentation()

        assertEquals(VolumeGateState.SENSOR_INVALID, presentation.state)
        assertFalse(presentation.ready)
    }

    @Test
    fun `sudden movement has dedicated restart guidance`() {
        val movement = uiState(
            runtime = runtime(withinRange = true, accelerationStable = false, stable = false, tilt = 8f),
        )

        val presentation = movement.volumeControlPresentation()

        assertEquals(VolumeGateState.SUDDEN_MOTION, presentation.state)
        assertTrue(presentation.instruction.contains("2 sekundy"))
        assertFalse(presentation.ready)
    }

    private fun uiState(
        connectionState: TrikiConnectionState = TrikiConnectionState.READY,
        settings: AppSettings = AppSettings(),
        runtime: RuntimeState,
    ) = MainUiState(
        settings = settings,
        ble = TrikiBleState(connectionState = connectionState),
        runtime = runtime,
    )

    private fun runtime(
        sensorValid: Boolean = true,
        withinRange: Boolean,
        accelerationStable: Boolean = true,
        stable: Boolean = false,
        progress: Float = 0f,
        tilt: Float,
    ) = RuntimeState(
        latestSample = sample(),
        volumeSensorValid = sensorValid,
        volumeWithinTiltRange = withinRange,
        volumeAccelerationStable = accelerationStable,
        volumeTiltStable = stable,
        volumeStabilizationProgress = progress,
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
