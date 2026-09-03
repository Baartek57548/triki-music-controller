"""
Tier 2 Boundary Test: Connection Loss and Stream Gap Recovery.

Verifies that time gaps (>250ms/300ms) reset stabilization timers, clamp time deltas,
prevent huge accumulated angle spikes, and safely restore algorithm state.
Strict 0-emoji compliance enforced.
"""

import unittest
from tests.helpers.imu_math import (
    AirMouseController,
    EdgePoseBrightnessController,
    FullRotationGestureDetector,
    GyroscopeVolumeController,
    TrikiButtonInterpreter,
    Vector3,
)
from tests.helpers.synthetic_data import create_filtered_sample


class TestConnectionLossAndGaps(unittest.TestCase):

    def test_volume_controller_resets_after_stream_gap(self):
        """Verify volume controller requires fresh 2s stabilization after >250ms gap."""
        ctrl = GyroscopeVolumeController()
        t0 = 1_000_000_000

        # Stabilize for 2.0s
        for i in range(101):
            ctrl.process(create_filtered_sample(t0 + i * 20_000_000, Vector3(0, 0, 0), Vector3(0, 0, -1.0)))

        # Verify stable
        res_stable = ctrl.process(create_filtered_sample(t0 + 2_020_000_000, Vector3(0, 0, 0), Vector3(0, 0, -1.0)))
        self.assertTrue(res_stable.active)

        # 500 ms stream gap (connection hiccup)
        gap_sample = create_filtered_sample(t0 + 2_520_000_000, Vector3(0, 0, 50.0), Vector3(0, 0, -1.0))
        res_gap = ctrl.process(gap_sample)
        self.assertFalse(res_gap.active)
        self.assertIsNone(res_gap.action)

    def test_edge_brightness_clamps_dt_on_stream_gap(self):
        """Verify brightness controller clamps dt to 20ms (0.02s) when gap > 250ms."""
        ctrl = EdgePoseBrightnessController(initial_brightness_percent=50.0)
        t0 = 1_000_000_000

        ctrl.process(create_filtered_sample(t0, Vector3(0, 0, 0), Vector3(0, 1.0, 0)), is_button_pressed=True)

        # 1.0s gap at 100 dps -> without clamping would be 100 deg = 40% jump!
        # With clamping to 0.02s -> 100 * 0.02 = 2.0 deg (< 2.5 deg/% -> 0% jump)
        res_gap = ctrl.process(
            create_filtered_sample(t0 + 1_000_000_000, Vector3(0, 0, 100.0), Vector3(0, 1.0, 0)),
            is_button_pressed=True,
        )
        self.assertEqual(res_gap.delta_percent, 0.0)
        self.assertEqual(res_gap.brightness_percent, 50.0)

    def test_air_mouse_clears_momentum_on_gap(self):
        """Verify Air Mouse resets smoothed velocity and subpixel buffers on gap > 250ms."""
        mouse = AirMouseController(is_active=True)
        t0 = 1_000_000_000

        # Rapid movement
        mouse.process(create_filtered_sample(t0, Vector3(0, 0, -50.0), Vector3(0, 0.5, 0)))
        mouse.process(create_filtered_sample(t0 + 20_000_000, Vector3(0, 0, -50.0), Vector3(0, 0.5, 0)))

        # Stream gap of 500 ms with gentle movement
        out = mouse.process(create_filtered_sample(t0 + 520_000_000, Vector3(0, 0, 0.0), Vector3(0, 0.5, 0)))
        self.assertEqual(out.delta_x, 0)
        self.assertEqual(out.delta_y, 0)


if __name__ == "__main__":
    unittest.main()
