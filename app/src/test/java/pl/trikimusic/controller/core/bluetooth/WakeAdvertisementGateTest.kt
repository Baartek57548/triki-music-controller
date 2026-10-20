package pl.trikimusic.controller.core.bluetooth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeAdvertisementGateTest {
    @Test
    fun `continuous advertisements respect initial parking delay`() {
        val gate = WakeAdvertisementGate(requiredSilenceMillis = 5_000L)
        gate.reset(0L)

        assertFalse(gate.observeAdvertisement(1_000L))
        assertFalse(gate.observeAdvertisement(4_500L))
        assertFalse(gate.observeAdvertisement(9_000L))
    }

    @Test
    fun `continuous advertisements cannot block reconnect beyond fifteen seconds`() {
        val gate = WakeAdvertisementGate()
        gate.reset(0L)
        for (now in 1_000L until 15_000L step 1_000L) {
            assertFalse(gate.observeAdvertisement(now))
        }
        assertTrue(gate.observeAdvertisement(15_000L))
    }

    @Test
    fun `timer arms after maximum wait with continuous advertisements`() {
        val gate = WakeAdvertisementGate()
        gate.reset(0L)
        for (now in 1_000L until 15_000L step 1_000L) {
            assertFalse(gate.observeAdvertisement(now))
            assertFalse(gate.tryArm(now))
        }
        assertTrue(gate.tryArm(15_000L))
        assertTrue(gate.observeAdvertisement(15_100L))
    }

    @Test
    fun `startup and unexpected disconnect accept first advertisement`() {
        val gate = WakeAdvertisementGate()
        gate.reset(1_000L, waitForSilence = false)
        assertTrue(gate.isArmed)
        assertTrue(gate.observeAdvertisement(1_001L))
        gate.reset(2_000L, waitForSilence = true)
        assertFalse(gate.isArmed)
        assertFalse(gate.observeAdvertisement(2_001L))
    }

    @Test
    fun `clock rollback and reset restart maximum wait`() {
        val gate = WakeAdvertisementGate(requiredSilenceMillis = 5_000L, maximumWaitMillis = 15_000L)
        gate.reset(10_000L)
        assertFalse(gate.observeAdvertisement(2_000L))
        for (now in 3_000L until 17_000L step 1_000L) {
            assertFalse(gate.observeAdvertisement(now))
        }
        assertTrue(gate.observeAdvertisement(17_000L))
        gate.reset(18_000L)
        assertFalse(gate.observeAdvertisement(18_001L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `maximum wait cannot be shorter than silence window`() {
        WakeAdvertisementGate(requiredSilenceMillis = 5_000L, maximumWaitMillis = 4_999L)
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
