"""
Tier 3 Combination Test: Gesture Arbitration and Mutual Exclusion.

Verifies strict mutual exclusion between volume control, edge brightness,
face-down full rotation, air mouse, and button interactions.
Strict 0-emoji compliance enforced.
"""

import unittest
from tests.helpers.imu_math import (
    AirMouseController,
    EdgePoseBrightnessController,
    FullRotationGestureDetector,
    GyroscopeVolumeController,
    HoldGesturePhase,
    TrikiButtonInterpreter,
    Vector3,
)
from tests.helpers.synthetic_data import create_filtered_sample


class TestGestureArbitrationConcurrency(unittest.TestCase):

    def setUp(self):
        self.vol_ctrl = GyroscopeVolumeController()
        self.bright_ctrl = EdgePoseBrightnessController(50.0)
        self.rot_detector = FullRotationGestureDetector()
        self.air_mouse = AirMouseController(is_active=True)
        self.button_interp = TrikiButtonInterpreter()

    def test_upright_orientation_exclusivity(self):
        """When device is upright face-up on table, only volume controller can activate."""
        t0 = 1_000_000_000
        # Face up: Acc Z = -1.0g
        sample = create_filtered_sample(t0, Vector3(0, 0, 50.0), Vector3(0, 0, -1.0))

        res_bright = self.bright_ctrl.process(sample)
        self.assertFalse(res_bright.active, "Brightness should be inactive when face up")

        res_rot = self.rot_detector.process(sample)
        self.assertEqual(res_rot.phase, HoldGesturePhase.HOLDING, "Full rotation should be inactive when face up")
        self.assertFalse(res_rot.face_down)

        out_mouse = self.air_mouse.process(sample)
        self.assertEqual(out_mouse.delta_x, 0, "Air mouse should be suppressed on table rest")
        self.assertEqual(out_mouse.delta_y, 0)

    def test_edge_pose_orientation_exclusivity(self):
        """When device is resting on 90-degree edge, volume and full rotation must be inactive."""
        t0 = 1_000_000_000
        # Edge 90: Acc Y = 1.0g, Acc Z = 0.0g
        sample = create_filtered_sample(t0, Vector3(0, 0, 50.0), Vector3(0, 1.0, 0.0))

        res_vol = self.vol_ctrl.process(sample)
        self.assertFalse(res_vol.within_tilt_range, "Volume should be inactive at 90 deg edge")
        self.assertFalse(res_vol.active)

        res_rot = self.rot_detector.process(sample)
        self.assertFalse(res_rot.face_down, "Full rotation should be inactive at 90 deg edge")

        res_bright = self.bright_ctrl.process(sample, is_button_pressed=True)
        self.assertTrue(res_bright.active, "Brightness should be active on 90 deg edge")

        out_mouse = self.air_mouse.process(sample)
        self.assertTrue(out_mouse.is_scroll_mode, "Air mouse should enter scroll mode on 90 deg edge")

    def test_air_mouse_click_transient_suppression(self):
        """Verifies 90ms motion suppression during physical button clicks in Air Mouse mode."""
        t0 = 1_000_000_000
        # Free-air sample (Acc Y = 0.5g, Acc Z = 0.0g)
        sample = create_filtered_sample(t0, Vector3(30.0, 0, -30.0), Vector3(0, 0.5, 0.0))

        # Motion active
        out1 = self.air_mouse.process(sample)
        self.assertTrue(out1.delta_x != 0 or out1.delta_y != 0)

        # Button click occurs at t0 + 20ms
        self.air_mouse.notify_click_transient(t0 + 20_000_000)

        # During suppression window (t0 + 50ms < t0 + 110ms) -> 0 delta
        sample_during = create_filtered_sample(t0 + 50_000_000, Vector3(50.0, 0, -50.0), Vector3(0, 0.5, 0.0))
        out_suppressed = self.air_mouse.process(sample_during)
        self.assertEqual(out_suppressed.delta_x, 0)
        self.assertEqual(out_suppressed.delta_y, 0)

        # After suppression window (t0 + 130ms > t0 + 110ms) -> motion resumes
        sample_after = create_filtered_sample(t0 + 130_000_000, Vector3(50.0, 0, -50.0), Vector3(0, 0.5, 0.0))
        out_resumed = self.air_mouse.process(sample_after)
        self.assertTrue(out_resumed.delta_x != 0 or out_resumed.delta_y != 0)


if __name__ == "__main__":
    unittest.main()
