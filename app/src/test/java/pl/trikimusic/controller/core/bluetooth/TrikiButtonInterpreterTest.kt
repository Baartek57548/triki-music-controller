package pl.trikimusic.controller.core.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.trikimusic.controller.domain.model.ButtonClickType
import pl.trikimusic.controller.domain.model.RawVector3
import pl.trikimusic.controller.domain.model.TrikiSensorData
import pl.trikimusic.controller.domain.model.Vector3

class TrikiButtonInterpreterTest {
    @Test
    fun `rolling packet ids can never become button clicks`() {
        val fixture = Fixture()

        repeat(4) { cycle ->
            (0..15).forEach { status -> fixture.feed(status, frameIndex = cycle * 16L + status) }
        }
        fixture.feed(0, frames = 40)

        assertEquals(TrikiButtonProtocolMode.SEQUENCE_COUNTER, fixture.interpreter.protocolMode)
        assertTrue(fixture.events.isEmpty())
        assertFalse(fixture.interpreter.shouldSuppressMotionControl)
    }

    @Test
    fun `alternating zero one packet ids remain unknown and silent`() {
        val fixture = Fixture()

        repeat(80) { index -> fixture.feed(index and 1) }

        assertEquals(TrikiButtonProtocolMode.UNKNOWN, fixture.interpreter.protocolMode)
        assertTrue(fixture.events.isEmpty())
    }

    @Test
    fun `single click is emitted after the multi click window`() {
        val fixture = Fixture().apply { identifyButtonFirmware() }

        fixture.click()
        assertTrue(fixture.interpreter.shouldSuppressMotionControl)
        fixture.feed(0, frames = 25)

        assertEquals(listOf(ButtonClickType.SINGLE), fixture.events)
        assertFalse(fixture.interpreter.shouldSuppressMotionControl)
    }

    @Test
    fun `two clicks produce only next style double event`() {
        val fixture = Fixture().apply { identifyButtonFirmware() }

        fixture.click(releaseFrames = 7)
        fixture.click()
        fixture.feed(0, frames = 25)

        assertEquals(listOf(ButtonClickType.DOUBLE), fixture.events)
    }

    @Test
    fun `third click completes triple event immediately`() {
        val fixture = Fixture().apply { identifyButtonFirmware() }

        fixture.click(releaseFrames = 7)
        fixture.click(releaseFrames = 7)
        fixture.click(releaseFrames = 2)

        assertEquals(listOf(ButtonClickType.TRIPLE), fixture.events)
        assertFalse(fixture.interpreter.shouldSuppressMotionControl)
    }

    @Test
    fun `contact bounce and long hold are ignored`() {
        val fixture = Fixture().apply { identifyButtonFirmware() }

        repeat(16) { index -> fixture.feed(index and 1) }
        fixture.feed(0, frames = 30)
        fixture.feed(1, frames = 110)
        fixture.feed(0, frames = 30)

        assertTrue(fixture.events.isEmpty())
    }

    @Test
    fun `consumed hold cannot become a single click after release`() {
        val fixture = Fixture().apply { identifyButtonFirmware() }

        fixture.feed(1, frames = 30)
        assertTrue(fixture.interpreter.isPressed)
        assertTrue(fixture.interpreter.consumeCurrentHold())
        fixture.feed(0, frames = 3)
        fixture.feed(0, frames = 25)

        assertTrue(fixture.events.isEmpty())
        assertFalse(fixture.interpreter.shouldSuppressMotionControl)
    }

    @Test
    fun `checkAndConsumeHoldDuration consumes hold when duration is reached`() {
        val fixture = Fixture().apply { identifyButtonFirmware() }

        // Press and hold for 20 frames (approx 384 ms)
        fixture.feed(1, frames = 20)
        assertTrue(fixture.interpreter.isPressed)

        // 500 ms required -> not yet reached (has been ~384 ms)
        assertFalse(fixture.interpreter.checkAndConsumeHoldDuration(fixture.currentTimestamp, 500_000_000L))

        // Feed another 10 frames -> total ~576 ms
        fixture.feed(1, frames = 10)
        assertTrue(fixture.interpreter.checkAndConsumeHoldDuration(fixture.currentTimestamp, 500_000_000L))

        // Subsequent call returns false because it was already consumed
        assertFalse(fixture.interpreter.checkAndConsumeHoldDuration(fixture.currentTimestamp, 500_000_000L))

        // Releasing after hold consumed produces no click events
        fixture.feed(0, frames = 25)
        assertTrue(fixture.events.isEmpty())
        assertFalse(fixture.interpreter.shouldSuppressMotionControl)
    }

    @Test
    fun `checkAndConsumeHoldDuration returns false when not pressed`() {
        val fixture = Fixture().apply { identifyButtonFirmware() }
        assertFalse(fixture.interpreter.isPressed)
        assertFalse(fixture.interpreter.checkAndConsumeHoldDuration(fixture.currentTimestamp, 100_000_000L))
    }

    @Test
    fun `unexpected status cancels a pending click and selects counter mode`() {
        val fixture = Fixture().apply { identifyButtonFirmware() }

        fixture.click()
        fixture.feed(2)
        fixture.feed(0, frames = 40)

        assertEquals(TrikiButtonProtocolMode.SEQUENCE_COUNTER, fixture.interpreter.protocolMode)
        assertTrue(fixture.events.isEmpty())
        assertFalse(fixture.interpreter.shouldSuppressMotionControl)
    }

    @Test
    fun `stream gap clears partial click and redetects protocol`() {
        val fixture = Fixture().apply { identifyButtonFirmware() }

        fixture.feed(1, frames = 3)
        fixture.advance(400_000_000L)
        assertNull(fixture.feed(0))
        fixture.feed(0, frames = 12)

        assertEquals(TrikiButtonProtocolMode.BUTTON_FLAG, fixture.interpreter.protocolMode)
        assertTrue(fixture.events.isEmpty())
    }

    private class Fixture {
        val interpreter = TrikiButtonInterpreter()
        val events = mutableListOf<ButtonClickType>()
        var timestampNanos = 0L
            private set
        val currentTimestamp: Long get() = timestampNanos
        private var nextFrameIndex = 0L

        fun identifyButtonFirmware() {
            feed(0, frames = 12)
            assertEquals(TrikiButtonProtocolMode.BUTTON_FLAG, interpreter.protocolMode)
        }

        fun click(releaseFrames: Int = 2) {
            feed(1, frames = 3)
            feed(0, frames = releaseFrames)
        }

        fun advance(nanos: Long) {
            timestampNanos += nanos
        }

        fun feed(status: Int, frames: Int = 1, frameIndex: Long? = null): ButtonClickType? {
            var latest: ButtonClickType? = null
            repeat(frames) { offset ->
                timestampNanos += SAMPLE_PERIOD_NANOS
                val event = interpreter.process(sample(status, frameIndex?.plus(offset) ?: nextFrameIndex++))
                if (event != null) {
                    latest = event.type
                    events += event.type
                }
            }
            return latest
        }

        private fun sample(status: Int, frameIndex: Long): TrikiSensorData = TrikiSensorData(
            frameIndex = frameIndex,
            timestampNanos = timestampNanos,
            gyroscopeDps = Vector3(0f, 0f, 0f),
            accelerometerG = Vector3(0f, 0f, -1f),
            rawGyroscope = RawVector3(0, 0, 0),
            rawAccelerometer = RawVector3(0, 0, 2_048),
            status = status,
        )
    }

    private companion object {
        const val SAMPLE_PERIOD_NANOS = 19_230_769L
    }
}
