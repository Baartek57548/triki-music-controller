package pl.trikimusic.controller.core.bluetooth

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrikiProtocolDecoderTest {
    @Test
    fun `decodes confirmed little endian frame and scales physical units`() {
        val decoder = TrikiProtocolDecoder(startupFramesToDiscard = 0)
        val frame = frame(1_000, -500, 0, 2048, -1024, 4096)

        val sample = decoder.decode(frame, 1_000_000_000L).single()

        assertEquals(70f, sample.gyroscopeDps.x, 0.0001f)
        assertEquals(-35f, sample.gyroscopeDps.y, 0.0001f)
        assertEquals(1f, sample.accelerometerG.x, 0.0001f)
        assertEquals(-0.5f, sample.accelerometerG.y, 0.0001f)
        assertEquals(2f, sample.accelerometerG.z, 0.0001f)
    }

    @Test
    fun `reassembles split notifications and preserves packet id`() {
        val decoder = TrikiProtocolDecoder(startupFramesToDiscard = 0)
        val frame = frame(1, 2, 3, 4, 5, 6, packetId = 7)

        assertTrue(decoder.decode(frame.copyOfRange(0, 5), 100L).isEmpty())
        val sample = decoder.decode(frame.copyOfRange(5, frame.size), 200L).single()

        assertEquals(7, sample.packetId)
    }

    @Test
    fun `does not discard frames when firmware cycles packet ids zero through fifteen`() {
        val decoder = TrikiProtocolDecoder(startupFramesToDiscard = 0)
        val payload = (0..15).fold(byteArrayOf()) { bytes, packetId ->
            bytes + frame(packetId, 0, 0, 0, 0, 2048, packetId = packetId)
        }

        val samples = decoder.decode(payload, 1_000_000_000L)

        assertEquals(16, samples.size)
        assertEquals((0..15).toList(), samples.map { it.packetId })
        assertEquals(0L, decoder.statistics.droppedBytes)
    }

    @Test
    fun `resynchronizes after garbage and extracts merged frames`() {
        val decoder = TrikiProtocolDecoder(startupFramesToDiscard = 0)
        val first = frame(1, 2, 3, 4, 5, 6)
        val second = frame(7, 8, 9, 10, 11, 12)
        val bytes = byteArrayOf(0x55, 0x66, 0x22, 0x55) + first + second

        val samples = decoder.decode(bytes, 1_000_000_000L)

        assertEquals(2, samples.size)
        assertEquals(4L, decoder.statistics.droppedBytes)
        assertTrue(samples[1].timestampNanos > samples[0].timestampNanos)
    }

    @Test
    fun `drops configured startup noise frames`() {
        val decoder = TrikiProtocolDecoder(startupFramesToDiscard = 2)
        val payload = frame(1, 1, 1, 1, 1, 1) + frame(2, 2, 2, 2, 2, 2) + frame(3, 3, 3, 3, 3, 3)

        val samples = decoder.decode(payload, 1_000_000_000L)

        assertEquals(1, samples.size)
        assertEquals(3, samples.single().rawGyroscope.x.toInt())
        assertEquals(2L, decoder.statistics.discardedStartupFrames)
    }

    @Test
    fun `protocol start command matches observed bytes`() {
        assertArrayEquals(
            byteArrayOf(0x20, 0x10, 0x00, 0xD0.toByte(), 0x07, 0x34, 0x00, 0x03),
            TrikiProtocol.START_STREAM_COMMAND,
        )
    }

    private fun frame(gx: Short, gy: Short, gz: Short, ax: Short, ay: Short, az: Short, packetId: Int = 0): ByteArray =
        ByteBuffer.allocate(TrikiProtocol.FRAME_LENGTH)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(0x22)
            .put(packetId.toByte())
            .putShort(gx)
            .putShort(gy)
            .putShort(gz)
            .putShort(ax)
            .putShort(ay)
            .putShort(az)
            .array()

    private fun frame(gx: Int, gy: Int, gz: Int, ax: Int, ay: Int, az: Int, packetId: Int = 0): ByteArray =
        frame(gx.toShort(), gy.toShort(), gz.toShort(), ax.toShort(), ay.toShort(), az.toShort(), packetId)
}
