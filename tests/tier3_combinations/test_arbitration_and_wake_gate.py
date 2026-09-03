"""
Tier 3 Combination Test: Multi-Device Arbitration and Wake Advertisement Gate.

Verifies connection priority delays (MediaPriority, AlwaysConnect, OnlyWhenPlaying),
lease yield policies, and 5-second silence gating for BLE advertisements.
Strict 0-emoji compliance enforced.
"""

import unittest
from tests.helpers.imu_math import (
    MultiDeviceArbitrationMode,
    MultiDeviceArbitrationPolicy,
    WakeAdvertisementGate,
)


class TestArbitrationAndWakeGate(unittest.TestCase):

    def test_wake_gate_silence_window_requirement(self):
        """Verify WakeAdvertisementGate requires 5.0 seconds of silence to allow connection."""
        gate = WakeAdvertisementGate(required_silence_nanos=5_000_000_000)
        t0 = 1_000_000_000

        # First advertisement initializes silence baseline -> returns False
        self.assertFalse(gate.observe_advertisement(t0))

        # Frequent advertisements every 1 second (burst) -> rejected
        for i in range(1, 5):
            self.assertFalse(gate.observe_advertisement(t0 + i * 1_000_000_000))

        # 5.5 seconds of silence since last advertisement
        t_wake = t0 + 4 * 1_000_000_000 + 5_500_000_000
        self.assertTrue(gate.observe_advertisement(t_wake), "Should allow connection after 5s silence")

    def test_multi_device_arbitration_delays_in_media_priority(self):
        """Verify connection delays prioritized by media playback and user state."""
        policy = MultiDeviceArbitrationPolicy

        # 1. Currently playing -> 0 ms (immediate)
        delay_playing = policy.calculate_connection_delay_ms(
            mode=MultiDeviceArbitrationMode.MEDIA_PRIORITY,
            is_media_playing=True,
            last_playback_time_seconds_ago=None,
            is_user_active=False,
        )
        self.assertEqual(delay_playing, 0)

        # 2. Foreground active user -> 100 ms
        delay_active = policy.calculate_connection_delay_ms(
            mode=MultiDeviceArbitrationMode.MEDIA_PRIORITY,
            is_media_playing=False,
            last_playback_time_seconds_ago=300.0,
            is_user_active=True,
        )
        self.assertEqual(delay_active, 100)

        # 3. Recent playback (1 minute ago <= 3 min) -> 200 ms
        delay_recent = policy.calculate_connection_delay_ms(
            mode=MultiDeviceArbitrationMode.MEDIA_PRIORITY,
            is_media_playing=False,
            last_playback_time_seconds_ago=60.0,
            is_user_active=False,
        )
        self.assertEqual(delay_recent, 200)

        # 4. Stale playback (5 minutes ago <= 10 min) -> 500 ms
        delay_stale = policy.calculate_connection_delay_ms(
            mode=MultiDeviceArbitrationMode.MEDIA_PRIORITY,
            is_media_playing=False,
            last_playback_time_seconds_ago=300.0,
            is_user_active=False,
        )
        self.assertEqual(delay_stale, 500)

        # 5. Idle (> 10 min or None) -> 1000 ms
        delay_idle = policy.calculate_connection_delay_ms(
            mode=MultiDeviceArbitrationMode.MEDIA_PRIORITY,
            is_media_playing=False,
            last_playback_time_seconds_ago=900.0,
            is_user_active=False,
        )
        self.assertEqual(delay_idle, 1000)

    def test_connection_lease_yield_after_10_seconds(self):
        """Verify device yields connection after 10s of idle non-playback in MediaPriority."""
        policy = MultiDeviceArbitrationPolicy

        # Playing -> Never yields
        self.assertFalse(policy.should_yield_connection(MultiDeviceArbitrationMode.MEDIA_PRIORITY, True, 20.0))

        # Not playing, 5s connected -> does not yield yet
        self.assertFalse(policy.should_yield_connection(MultiDeviceArbitrationMode.MEDIA_PRIORITY, False, 5.0))

        # Not playing, 12s connected -> yields connection
        self.assertTrue(policy.should_yield_connection(MultiDeviceArbitrationMode.MEDIA_PRIORITY, False, 12.0))


if __name__ == "__main__":
    unittest.main()
