"""
Tier 2 Boundary Test: Rapid Orientation and State Machine Transitions.

Verifies state machine resets, timer re-initializations, and zero false-positive
triggers when the device is flipped rapidly between upright, inverted, and edge poses.
Strict 0-emoji compliance enforced.
"""

import unittest
from tests.helpers.imu_math import (
    EdgePoseBrightnessController,
    FullRotationGestureDetector,
    GyroscopeVolumeController,
    HoldGesturePhase,
    Vector3,
)
from tests.helpers.synthetic_data import create_filtered_sample


class TestRapidStateTransitions(unittest.TestCase):

    def test_rapid_flipping_prevents_false_volume_triggers(self):
        """Verify alternating face-up and face-down poses reset stabilization."""
        ctrl = GyroscopeVolumeController()
        t = 1_000_000_000

        # Alternate orientations every 100 ms (less than 2000 ms stabilization)
        for i in range(50):
            is_face_up = (i % 2 == 0)
            acc = Vector3(0, 0, -1.0) if is_face_up else Vector3(0, 0, 1.0)
            sample = create_filtered_sample(
                timestamp_nanos=t + i * 100_000_000,
                gyro=Vector3(0, 0, 100.0),  # Fast spin
                accel=acc,
            )
            res = ctrl.process(sample)
            self.assertIsNone(res.action, f"False action triggered at iteration {i}")
            self.assertFalse(res.active)

    def test_rapid_edge_entry_and_exit_hysteresis(self):
        """Verify edge brightness controller handles rapid enter/exit transitions."""
        ctrl = EdgePoseBrightnessController(initial_brightness_percent=50.0)
        t = 1_000_000_000

        # Enters edge (Acc Z = 0.40g <= 0.45g enter threshold)
        res_enter = ctrl.process(create_filtered_sample(t, Vector3(0, 0, 0), Vector3(0, 0.916, 0.40)))
        self.assertTrue(res_enter.active)

        # Exits edge (Acc Z = 0.65g > 0.60g exit threshold)
        res_exit = ctrl.process(create_filtered_sample(t + 20_000_000, Vector3(0, 0, 0), Vector3(0, 0.760, 0.65)))
        self.assertFalse(res_exit.active)

        # Re-attempts enter with 0.55g (between enter 0.45 and exit 0.60) -> should NOT enter
        res_rejected = ctrl.process(create_filtered_sample(t + 40_000_000, Vector3(0, 0, 0), Vector3(0, 0.835, 0.55)))
        self.assertFalse(res_rejected.active)

    def test_full_rotation_reset_on_face_up_flip(self):
        """Verify full rotation gesture immediately resets when flipped face-up."""
        detector = FullRotationGestureDetector()
        t = 1_000_000_000

        # Stabilize face down (Acc Z = 1.0g) for 600 ms (30 samples)
        for i in range(30):
            detector.process(create_filtered_sample(t + i * 20_000_000, Vector3(0, 0, 0), Vector3(0, 0, 1.0)))

        # Start rotating: feed multiple samples so EMA ramps above 22 dps activation threshold
        for i in range(1, 6):
            res_rot = detector.process(create_filtered_sample(t + 600_000_000 + i * 20_000_000, Vector3(0, 0, 50.0), Vector3(0, 0, 1.0)))

        self.assertEqual(res_rot.phase, HoldGesturePhase.TRACKING)

        # Suddenly flip face up (Acc Z = -1.0g)
        res_flip = detector.process(create_filtered_sample(t + 720_000_000, Vector3(0, 0, 50.0), Vector3(0, 0, -1.0)))
        self.assertEqual(res_flip.phase, HoldGesturePhase.HOLDING)
        self.assertEqual(res_flip.stabilization_progress, 0.0)
        self.assertFalse(res_flip.face_down)


if __name__ == "__main__":
    unittest.main()
