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
    fun edgePoseStabilizesAfter400ms() {
        val controller = EdgePoseBrightnessController(50f)
        val t0 = 1_000_000_000L

        val res0 = controller.process(sample(t0, Vector3(0f, 1f, 0f)))
        assertTrue(res0.active)
        assertFalse(res0.ready)

        val res200 = controller.process(sample(t0 + 200_000_000L, Vector3(0f, 1f, 0f)))
        assertTrue(res200.active)
        assertFalse(res200.ready)
        assertTrue(res200.stabilizationProgress >= 0.5f)

        val res400 = controller.process(sample(t0 + 400_000_000L, Vector3(0f, 1f, 0f)))
        assertTrue(res400.active)
        assertTrue(res400.ready)
    }

    @Test
    fun rotationInEdgePoseAdjustsBrightness() {
        val controller = EdgePoseBrightnessController(50f)
        val t0 = 1_000_000_000L

        controller.process(sample(t0, Vector3(0f, 1f, 0f)))
        controller.process(sample(t0 + 400_000_000L, Vector3(0f, 1f, 0f)))

        val res = controller.process(sample(t0 + 500_000_000L, Vector3(0f, 1f, 0f), 180f))

        assertTrue(res.active)
        assertTrue(res.ready)
        assertTrue(res.brightnessPercent >= 55f)
    }

    @Test
    fun buttonMustBeHeldToAdjustBrightness() {
        val controller = EdgePoseBrightnessController(50f)
        val t0 = 1_000_000_000L

        controller.process(sample(t0, Vector3(0f, 1f, 0f)))
        controller.process(sample(t0 + 400_000_000L, Vector3(0f, 1f, 0f)))

        val resWithoutButton = controller.process(
            sample(t0 + 500_000_000L, Vector3(0f, 1f, 0f), 180f),
            isButtonPressed = false,
        )
        assertTrue(resWithoutButton.active)
        assertFalse(resWithoutButton.ready)
        assertEquals(50f, resWithoutButton.brightnessPercent, 0.01f)
        assertEquals(0f, resWithoutButton.deltaPercent, 0.01f)

        val resWithButton = controller.process(
            sample(t0 + 600_000_000L, Vector3(0f, 1f, 0f), 180f),
            isButtonPressed = true,
        )
        assertTrue(resWithButton.active)
        assertTrue(resWithButton.ready)
        assertTrue(resWithButton.brightnessPercent > 50f)
    }
}
