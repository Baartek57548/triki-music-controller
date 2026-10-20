package pl.trikimusic.controller.core.bluetooth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertEquals
import pl.trikimusic.controller.core.gesture.FullRotationGestureDetector
import pl.trikimusic.controller.core.sensor.SensorFilter
import pl.trikimusic.controller.domain.model.CalibrationProfile
import pl.trikimusic.controller.domain.model.RawVector3
import pl.trikimusic.controller.domain.model.TrikiSensorData
import pl.trikimusic.controller.domain.model.Vector3

class ConnectionActivityLeaseTest {
    @Test
    fun `stationary filtered samples and gesture detector park in either orientation`() {
        for (gravityZ in listOf(-1f, 1f)) {
            val lease = ConnectionActivityLease()
            val filter = SensorFilter()
            val detector = FullRotationGestureDetector()
            val parks = mutableListOf<Long>()
            repeat(6_501) { index ->
                val timestamp = index * 20_000_000L
                val source = TrikiSensorData(index.toLong(), timestamp, Vector3(0f, 0f, 0f),
                    Vector3(0f, 0f, gravityZ), RawVector3(0, 0, 0), RawVector3(0, 0, 0), 0)
                val sample = filter.process(source, CalibrationProfile())
                val gesture = detector.process(sample)
                if (lease.observe(sample, false, false, gesture.phase)) parks += timestamp
            }
            assertEquals(listOf(ConnectionActivityLease.DEFAULT_IDLE_TIMEOUT_NANOS), parks)
        }
    }

    @Test
    fun `default lease stays connected for two minutes and renews on activity`() {
        val lease = ConnectionActivityLease()
        assertFalse(lease.observe(0L, active = false))
        assertFalse(lease.observe(12_000_000_000L, active = false))
        assertFalse(lease.observe(119_999_999_999L, active = false))
        assertFalse(lease.observe(120_000_000_000L, active = true))
        assertFalse(lease.observe(239_999_999_999L, active = false))
        assertTrue(lease.observe(240_000_000_000L, active = false))
    }

    @Test
    fun `parks exactly once after idle timeout`() {
        val lease = ConnectionActivityLease(idleTimeoutNanos = 12_000L)

        assertFalse(lease.observe(1_000L, active = false))
        assertFalse(lease.observe(12_999L, active = false))
        assertTrue(lease.observe(13_000L, active = false))
        assertFalse(lease.observe(20_000L, active = false))
    }

    @Test
    fun `activity renews the connection lease`() {
        val lease = ConnectionActivityLease(idleTimeoutNanos = 12_000L)

        assertFalse(lease.observe(1_000L, active = false))
        assertFalse(lease.observe(10_000L, active = true))
        assertFalse(lease.observe(21_999L, active = false))
        assertTrue(lease.observe(22_000L, active = false))
    }

    @Test
    fun `reset and timestamp rollback require a fresh idle window`() {
        val lease = ConnectionActivityLease(idleTimeoutNanos = 12_000L)

        assertFalse(lease.observe(20_000L, active = false))
        assertFalse(lease.observe(5_000L, active = false))
        assertFalse(lease.observe(16_999L, active = false))
        assertTrue(lease.observe(17_000L, active = false))
        lease.reset()
        assertFalse(lease.observe(50_000L, active = false))
    }
}
