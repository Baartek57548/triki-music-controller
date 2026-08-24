package pl.trikimusic.controller.core.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.trikimusic.controller.domain.model.FilteredSensorData
import pl.trikimusic.controller.domain.model.GestureThresholds
import pl.trikimusic.controller.domain.model.GestureType
import pl.trikimusic.controller.domain.model.OrientationData
import pl.trikimusic.controller.domain.model.RawVector3
import pl.trikimusic.controller.domain.model.TrikiSensorData
import pl.trikimusic.controller.domain.model.Vector3

class GestureEngineTest {
    private val thresholds = GestureThresholds()

    @Test
    fun `tilt uses hysteresis and cooldown to prevent repeated actions`() {
        val engine = GestureEngine()
        val events = mutableListOf<GestureType>()
        var time = 0L
        repeat(30) {
            time += PERIOD
            events += engine.process(filtered(time, roll = 36f), thresholds).map { it.type }
        }
        repeat(20) {
            time += PERIOD
            engine.process(filtered(time, roll = 0f), thresholds)
        }
        time += 700_000_000L
        events += engine.process(filtered(time, roll = 36f), thresholds).map { it.type }

        assertEquals(listOf(GestureType.TILT_RIGHT, GestureType.TILT_RIGHT), events)
    }

    @Test
    fun `rotation requires sustained samples`() {
        val engine = GestureEngine()
        val events = mutableListOf<GestureType>()
        repeat(5) { index ->
            events += engine.process(filtered(index * PERIOD, gyro = Vector3(0f, 0f, -430f)), thresholds).map { it.type }
        }
        assertEquals(listOf(GestureType.ROTATE_LEFT), events)
    }

    @Test
    fun `single shake is delayed until double shake window expires`() {
        val engine = GestureEngine()
        val events = mutableListOf<GestureType>()
        var time = 0L
        repeat(4) {
            time += PERIOD
            events += engine.process(shake(time), thresholds).map { it.type }
        }
        assertTrue(events.isEmpty())
        time += 500_000_000L
        events += engine.process(filtered(time), thresholds).map { it.type }
        assertEquals(listOf(GestureType.SHAKE), events)
    }

    @Test
    fun `two shake pulses emit only double shake`() {
        val engine = GestureEngine()
        val events = mutableListOf<GestureType>()
        var time = 0L
        repeat(4) {
            time += PERIOD
            events += engine.process(shake(time), thresholds).map { it.type }
        }
        repeat(4) {
            time += PERIOD
            engine.process(filtered(time), thresholds)
        }
        repeat(4) {
            time += PERIOD
            events += engine.process(shake(time), thresholds).map { it.type }
        }
        assertEquals(listOf(GestureType.DOUBLE_SHAKE), events)
    }

    @Test
    fun `free fall emits throw up once`() {
        val engine = GestureEngine()
        val events = (0 until 6).flatMap { index ->
            engine.process(filtered(index * PERIOD, accel = Vector3(0f, 0f, 0.08f)), thresholds)
        }
        assertEquals(1, events.count { it.type == GestureType.THROW_UP })
    }

    private fun shake(time: Long): FilteredSensorData = filtered(
        time,
        gyro = Vector3(340f, 280f, 90f),
        accel = Vector3(0.45f, 0f, 1.15f),
    )

    private fun filtered(
        time: Long,
        roll: Float = 0f,
        gyro: Vector3 = Vector3(0f, 0f, 0f),
        accel: Vector3 = Vector3(0f, 0f, 1f),
    ): FilteredSensorData {
        val source = TrikiSensorData(
            0,
            time,
            gyro,
            accel,
            RawVector3(0, 0, 0),
            RawVector3(0, 0, 0),
            0,
        )
        return FilteredSensorData(source, gyro, accel, OrientationData(roll = roll))
    }

    private companion object {
        const val PERIOD = 10_000_000L
    }
}
