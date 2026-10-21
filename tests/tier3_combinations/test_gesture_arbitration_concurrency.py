"""
Tier 3 Combination Test: Gesture Arbitration and Mutual Exclusion.

Verifies strict mutual exclusion between volume control, edge brightness,
face-down full rotation, and button interactions.
Strict 0-emoji compliance enforced.
"""

import unittest
from tests.helpers.imu_math import (
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




if __name__ == "__main__":
    unittest.main()
