package pl.trikimusic.controller.core.bluetooth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeAdvertisementGateTest {
    @Test
    fun `continuous advertisements never look like a new wake`() {
        val gate = WakeAdvertisementGate(requiredSilenceMillis = 5_000L)
        gate.reset(0L)

        assertFalse(gate.observeAdvertisement(1_000L))
        assertFalse(gate.observeAdvertisement(4_500L))
        assertFalse(gate.observeAdvertisement(9_000L))
    }

    @Test
    fun `first advertisement after silence starts connection`() {
        val gate = WakeAdvertisementGate(requiredSilenceMillis = 5_000L)
        gate.reset(1_000L)

        assertTrue(gate.observeAdvertisement(6_000L))
    }

    @Test
    fun `timer can arm gate before next advertisement`() {
        val gate = WakeAdvertisementGate(requiredSilenceMillis = 5_000L)
        gate.reset(2_000L)

        assertFalse(gate.tryArm(6_999L))
        assertTrue(gate.tryArm(7_000L))
        assertTrue(gate.isArmed)
        assertTrue(gate.observeAdvertisement(7_100L))
    }

    @Test
    fun `timestamp rollback safely restarts silence window`() {
        val gate = WakeAdvertisementGate(requiredSilenceMillis = 5_000L)
        gate.reset(10_000L)

        assertFalse(gate.observeAdvertisement(2_000L))
        assertFalse(gate.tryArm(6_999L))
        assertTrue(gate.tryArm(7_000L))
    }
}
