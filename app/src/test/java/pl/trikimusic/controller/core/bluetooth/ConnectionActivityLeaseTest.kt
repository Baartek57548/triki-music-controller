package pl.trikimusic.controller.core.bluetooth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionActivityLeaseTest {
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
