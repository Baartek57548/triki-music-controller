package pl.trikimusic.controller.core.brightness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.trikimusic.controller.domain.model.FilteredSensorData
import pl.trikimusic.controller.domain.model.OrientationData
import pl.trikimusic.controller.domain.model.RawVector3
import pl.trikimusic.controller.domain.model.TrikiSensorData
import pl.trikimusic.controller.domain.model.Vector3

class EdgePoseBrightnessControllerTest {

    private fun sample(
        timestampNanos: Long,
        acc: Vector3,
        gyroZ: Float = 0f,
    ): FilteredSensorData {
        val source = TrikiSensorData(
            frameIndex = timestampNanos / 20_000_000L,
            timestampNanos = timestampNanos,
            gyroscopeDps = Vector3(0f, 0f, gyroZ),
            accelerometerG = acc,
            rawGyroscope = RawVector3(0, 0, 0),
            rawAccelerometer = RawVector3(0, 0, 0),
            status = 0,
        )
        return FilteredSensorData(source, source.gyroscopeDps, source.accelerometerG, OrientationData())
    }

    @Test
    fun flatPoseIsNotActive() {
        val controller = EdgePoseBrightnessController(50f)
        val res = controller.process(sample(1_000_000_000L, Vector3(0f, 0f, 1f)))

        assertFalse(res.active)
        assertFalse(res.ready)
        assertEquals(50f, res.brightnessPercent, 0.01f)
    }

    @Test
    fun edgePoseStabilizesAfter150msWithoutButton() {
        val controller = EdgePoseBrightnessController(50f)
        val t0 = 1_000_000_000L

        // Edge pose: Acc Z is 0, Acc Y is 1.0g
        val res0 = controller.process(sample(t0, Vector3(0f, 1f, 0f)), isButtonPressed = false)
        assertTrue(res0.active)
        assertFalse(res0.ready)

        // After 80ms -> still stabilizing
        val res80 = controller.process(sample(t0 + 80_000_000L, Vector3(0f, 1f, 0f)), isButtonPressed = false)
        assertTrue(res80.active)
        assertFalse(res80.ready)
        assertTrue(res80.stabilizationProgress >= 0.5f)

        // After 150ms -> Ready when button held
        val res150 = controller.process(sample(t0 + 150_000_000L, Vector3(0f, 1f, 0f)), isButtonPressed = true)
        assertTrue(res150.active)
        assertTrue(res150.ready)
    }

    @Test
    fun buttonPressBypassesStabilizationTimer() {
        val controller = EdgePoseBrightnessController(50f)
        val t0 = 1_000_000_000L

        // First sample initializes stabilization at t0
        val res0 = controller.process(sample(t0, Vector3(0f, 1f, 0f)), isButtonPressed = true)
        assertTrue(res0.active)

        // On the second sample at t0 + 20ms (well before 150ms), with isButtonPressed = true,
        // the stabilization wait is bypassed and the controller is ready immediately
        val res20 = controller.process(sample(t0 + 20_000_000L, Vector3(0f, 1f, 0f)), isButtonPressed = true)
        assertTrue(res20.active)
        assertTrue(res20.ready)
        assertEquals(50f, res20.brightnessPercent, 0.01f)
    }

    @Test
    fun enterAndExitHysteresisPreventsEdgeFlicker() {
        val controller = EdgePoseBrightnessController(50f)
        val t0 = 1_000_000_000L

        // Acc Z = 0.50g exceeds enter threshold (0.45g) -> not active
        val outside = controller.process(sample(t0, Vector3(0f, 0.866f, 0.50f)))
        assertFalse(outside.active)

        // Acc Z = 0.40g is below enter threshold (0.45g) -> enters edge pose
        val inside = controller.process(sample(t0 + 20_000_000L, Vector3(0f, 0.916f, 0.40f)))
        assertTrue(inside.active)

        // Acc Z rises to 0.55g (between enter 0.45g and exit 0.60g) -> remains inside due to hysteresis
        val hysteresisHold = controller.process(sample(t0 + 40_000_000L, Vector3(0f, 0.835f, 0.55f)))
        assertTrue(hysteresisHold.active)

        // Acc Z rises to 0.65g (> exit threshold 0.60g) -> exits edge pose
        val exited = controller.process(sample(t0 + 60_000_000L, Vector3(0f, 0.760f, 0.65f)))
        assertFalse(exited.active)

        // While outside, Acc Z at 0.55g (> enter threshold 0.45g) cannot re-enter
        val cannotReenter = controller.process(sample(t0 + 80_000_000L, Vector3(0f, 0.835f, 0.55f)))
        assertFalse(cannotReenter.active)
    }

    @Test
    fun rotationInEdgePoseAdjustsBrightnessWith2_5DegreesPerPercent() {
        val controller = EdgePoseBrightnessController(50f)
        val t0 = 1_000_000_000L

        controller.process(sample(t0, Vector3(0f, 1f, 0f)))
        controller.process(sample(t0 + 150_000_000L, Vector3(0f, 1f, 0f)))

        // Rotate at +180 deg/s for 0.1s -> 18 degrees -> 18 / 2.5 = 7.2 -> 7% increase -> 57%
        val res = controller.process(sample(t0 + 250_000_000L, Vector3(0f, 1f, 0f), 180f))

        assertTrue(res.active)
        assertTrue(res.ready)
        assertEquals(57f, res.brightnessPercent, 0.01f)
        assertEquals(7f, res.deltaPercent, 0.01f)
    }

    @Test
    fun gyroscopeDeadbandIgnoresNoiseUnder3Dps() {
        val controller = EdgePoseBrightnessController(50f)
        val t0 = 1_000_000_000L

        controller.process(sample(t0, Vector3(0f, 1f, 0f)))
        val res = controller.process(sample(t0 + 100_000_000L, Vector3(0f, 1f, 0f), 2.5f))

        assertTrue(res.active)
        assertTrue(res.ready)
        assertEquals(50f, res.brightnessPercent, 0.01f)
        assertEquals(0f, res.deltaPercent, 0.01f)
    }

    @Test
    fun transmissionGapClampsDtTo20ms() {
        val controller = EdgePoseBrightnessController(50f)
        val t0 = 1_000_000_000L

        controller.process(sample(t0, Vector3(0f, 1f, 0f)))
        // 500ms gap (> 250ms max gap) -> dt clamped to 0.02s
        // 100 deg/s * 0.02s = 2.0 degrees (< 2.5 deg/% -> 0 steps)
        val res = controller.process(sample(t0 + 500_000_000L, Vector3(0f, 1f, 0f), 100f))

        assertTrue(res.active)
        assertTrue(res.ready)
        assertEquals(50f, res.brightnessPercent, 0.01f)
        assertEquals(0f, res.deltaPercent, 0.01f)
    }

    @Test
    fun buttonMustBeHeldToAdjustBrightness() {
        val controller = EdgePoseBrightnessController(50f)
        val t0 = 1_000_000_000L

        controller.process(sample(t0, Vector3(0f, 1f, 0f)))
        controller.process(sample(t0 + 150_000_000L, Vector3(0f, 1f, 0f)))

        // Rotate at +180 deg/s without pressing button -> brightness should NOT change
        val resWithoutButton = controller.process(
            sample(t0 + 250_000_000L, Vector3(0f, 1f, 0f), 180f),
            isButtonPressed = false,
        )
        assertTrue(resWithoutButton.active)
        assertFalse(resWithoutButton.ready)
        assertEquals(50f, resWithoutButton.brightnessPercent, 0.01f)
        assertEquals(0f, resWithoutButton.deltaPercent, 0.01f)

        // Rotate with button pressed -> brightness changes
        val resWithButton = controller.process(
            sample(t0 + 350_000_000L, Vector3(0f, 1f, 0f), 180f),
            isButtonPressed = true,
        )
        assertTrue(resWithButton.active)
        assertTrue(resWithButton.ready)
        assertTrue(resWithButton.brightnessPercent > 50f)
    }

    @Test
    fun resetClearsStateAndHysteresis() {
        val controller = EdgePoseBrightnessController(50f)
        val t0 = 1_000_000_000L

        controller.process(sample(t0, Vector3(0f, 1f, 0f)))
        controller.reset()

        // Acc Z = 0.55g would be accepted only if currently in edge pose (due to hysteresis)
        // Since reset() was called, isCurrentlyInEdge is false, so 0.55g > 0.45g is rejected
        val res = controller.process(sample(t0 + 100_000_000L, Vector3(0f, 0.835f, 0.55f)))
        assertFalse(res.active)
    }
}
