"""
Tier 5 Adversarial Test: IMU Float Hardening & Non-Finite Number Stress.

Comprehensive stress testing against:
- Non-finite floats (NaN, +Infinity, -Infinity) across all axes
- Subnormal (denormal) floating point numbers (1e-45, -1e-45, 1e-38)
- Extreme magnitude numbers (1e38, -1e38, float max/min)
- Zero-magnitude vectors and zero-dt sample timestamps
- Stream poisoning and state recovery validation
Strict 0-emoji compliance enforced.
"""

from __future__ import annotations
import math
import struct
import unittest
from typing import List

from tests.helpers.imu_math import (
    AirMouseController,
    EdgePoseBrightnessController,
    FullRotationGestureDetector,
    GyroscopeVolumeController,
    HoldGesturePhase,
    MediaAction,
    TrikiProtocolDecoder,
    Vector3,
)
from tests.helpers.synthetic_data import create_filtered_sample


class TestAdversarialImuFloats(unittest.TestCase):

    def setUp(self):
        self.volume_ctrl = GyroscopeVolumeController()
        self.brightness_ctrl = EdgePoseBrightnessController(initial_brightness_percent=50.0)
        self.rotation_detector = FullRotationGestureDetector()
        self.mouse_ctrl = AirMouseController(is_active=True)

    # --------------------------------------------------------------------------
    # 1. Non-Finite Floats (NaN, +Inf, -Inf) in All Axes
    # --------------------------------------------------------------------------

    def test_volume_controller_adversarial_nonfinite_matrix(self):
        """Stress test GyroscopeVolumeController with all permutations of NaN and Infs."""
        adversarial_values = [
            float("nan"),
            float("inf"),
            float("-inf"),
            1e38,
            -1e38,
            1.401298464324817e-45,  # Subnormal min positive float
            -1.401298464324817e-45,
            0.0,
            -0.0,
        ]

        t = 1_000_000_000
        for val in adversarial_values:
            # Gyro non-finite on X, Y, Z
            for axis in ("x", "y", "z"):
                gyro_vec = Vector3(
                    val if axis == "x" else 0.0,
                    val if axis == "y" else 0.0,
                    val if axis == "z" else 0.0,
                )
                sample = create_filtered_sample(t, gyro=gyro_vec, accel=Vector3(0.0, 0.0, -1.0))
                res = self.volume_ctrl.process(sample)
                t += 20_000_000

                if not math.isfinite(val) and axis == "z":
                    self.assertFalse(res.sensor_valid, f"Expected sensor_valid=False for gyro.z={val}")
                    self.assertFalse(res.active, f"Expected active=False for gyro.z={val}")
                    self.assertIsNone(res.action, f"Expected action=None for gyro.z={val}")
                self.assertTrue(math.isfinite(res.tilt_degrees))
                self.assertTrue(math.isfinite(res.gyroscope_z_dps))

            # Accel non-finite on X, Y, Z
            for axis in ("x", "y", "z"):
                accel_vec = Vector3(
                    val if axis == "x" else 0.0,
                    val if axis == "y" else 0.0,
                    val if axis == "z" else -1.0,
                )
                sample = create_filtered_sample(t, gyro=Vector3(0.0, 0.0, 50.0), accel=accel_vec)
                res = self.volume_ctrl.process(sample)
                t += 20_000_000

                if not math.isfinite(val):
                    self.assertFalse(res.sensor_valid, f"Expected sensor_valid=False for accel.{axis}={val}")
                    self.assertFalse(res.active, f"Expected active=False for accel.{axis}={val}")
                    self.assertIsNone(res.action, f"Expected action=None for accel.{axis}={val}")
                self.assertTrue(math.isfinite(res.tilt_degrees))
                self.assertTrue(math.isfinite(res.gyroscope_z_dps))

    def test_brightness_controller_adversarial_nonfinite_inputs(self):
        """Stress test EdgePoseBrightnessController with NaN and Infs."""
        adversarial_values = [float("nan"), float("inf"), float("-inf"), 1e38, -1e38, 1e-45]

        t = 1_000_000_000
        # First stabilize at edge pose
        for i in range(15):
            self.brightness_ctrl.process(
                create_filtered_sample(t, Vector3(0.0, 0.0, 0.0), Vector3(0.0, 1.0, 0.0)),
                is_button_pressed=True,
            )
            t += 20_000_000

        self.assertTrue(self.brightness_ctrl.process(
            create_filtered_sample(t, Vector3(0.0, 0.0, 0.0), Vector3(0.0, 1.0, 0.0)),
            is_button_pressed=True,
        ).ready)

        # Now feed non-finite values into gyro and accel
        for val in adversarial_values:
            t += 20_000_000
            sample_bad_gyro = create_filtered_sample(t, Vector3(0.0, 0.0, val), Vector3(0.0, 1.0, 0.0))
            res = self.brightness_ctrl.process(sample_bad_gyro, is_button_pressed=True)
            self.assertTrue(math.isfinite(res.brightness_percent))
            self.assertTrue(0.0 <= res.brightness_percent <= 100.0)
            self.assertTrue(math.isfinite(res.delta_percent))

            t += 20_000_000
            sample_bad_accel = create_filtered_sample(t, Vector3(0.0, 0.0, 50.0), Vector3(val, val, val))
            res_acc = self.brightness_ctrl.process(sample_bad_accel, is_button_pressed=True)
            self.assertTrue(math.isfinite(res_acc.brightness_percent))
            self.assertTrue(0.0 <= res_acc.brightness_percent <= 100.0)

    def test_full_rotation_adversarial_nonfinite_rejection(self):
        """Stress test FullRotationGestureDetector against NaN, Inf, and subnormals."""
        adversarial_values = [float("nan"), float("inf"), float("-inf"), 1e38, -1e38, 1e-45]

        t = 1_000_000_000
        for val in adversarial_values:
            t += 20_000_000
            sample = create_filtered_sample(t, Vector3(0.0, 0.0, val), Vector3(0.0, 0.0, 1.0))
            res = self.rotation_detector.process(sample)
            self.assertFalse(res.triggered, f"Accidental trigger on val={val}")
            self.assertTrue(math.isfinite(res.accumulated_rotation_degrees))
            self.assertTrue(math.isfinite(res.gyroscope_z_dps))

            t += 20_000_000
            sample_acc = create_filtered_sample(t, Vector3(0.0, 0.0, 50.0), Vector3(val, 0.0, 1.0))
            res_acc = self.rotation_detector.process(sample_acc)
            self.assertFalse(res_acc.triggered, f"Accidental trigger on accel val={val}")

    def test_air_mouse_adversarial_nonfinite_safety(self):
        """Stress test AirMouseController against NaN, Infs, and extreme values."""
        nonfinite_values = [float("nan"), float("inf"), float("-inf")]

        t = 1_000_000_000
        for val in nonfinite_values:
            t += 20_000_000
            sample = create_filtered_sample(t, Vector3(val, val, val), Vector3(0.0, 0.0, 1.0))
            out = self.mouse_ctrl.process(sample)
            self.assertEqual(out.delta_x, 0)
            self.assertEqual(out.delta_y, 0)
            self.assertEqual(out.scroll_delta, 0)

    # --------------------------------------------------------------------------
    # 2. Subnormal Numbers & Underflow Stress
    # --------------------------------------------------------------------------

    def test_subnormal_numbers_do_not_cause_fpe_or_lockups(self):
        """Ensure subnormal numbers near float32 zero are handled gracefully."""
        subnormal_pos = 1.401298464324817e-45
        subnormal_neg = -1.401298464324817e-45

        sample = create_filtered_sample(
            timestamp_nanos=1_000_000_000,
            gyro=Vector3(subnormal_pos, subnormal_neg, subnormal_pos),
            accel=Vector3(subnormal_neg, subnormal_pos, -1.0),
        )

        vol_res = self.volume_ctrl.process(sample)
        self.assertTrue(vol_res.sensor_valid)

        bright_res = self.brightness_ctrl.process(sample, is_button_pressed=True)
        self.assertTrue(math.isfinite(bright_res.brightness_percent))

        rot_res = self.rotation_detector.process(sample)
        self.assertFalse(rot_res.triggered)

        mouse_out = self.mouse_ctrl.process(sample)
        self.assertEqual(mouse_out.delta_x, 0)
        self.assertEqual(mouse_out.delta_y, 0)

    # --------------------------------------------------------------------------
    # 3. Stream Poisoning & Immediate Recovery
    # --------------------------------------------------------------------------

    def test_stream_poisoning_and_graceful_recovery(self):
        """Inject 50 consecutive poison (NaN/Inf) samples, then verify clean recovery."""
        # 1. Warm up volume controller in stable face-up state for 1 second
        t = 1_000_000_000
        for i in range(50):
            t += 20_000_000
            self.volume_ctrl.process(create_filtered_sample(t, Vector3(0, 0, 0), Vector3(0, 0, -1.0)))

        # 2. Poison the stream with 50 corrupted samples
        for i in range(50):
            t += 20_000_000
            poison = create_filtered_sample(
                t,
                Vector3(float("nan"), float("inf"), float("-inf")),
                Vector3(float("nan"), 0.0, float("nan")),
            )
            res_poison = self.volume_ctrl.process(poison)
            self.assertFalse(res_poison.sensor_valid)
            self.assertFalse(res_poison.active)

        # 3. Stream returns to normal: should require fresh 2.0s stabilization and then work perfectly
        for i in range(101):
            t += 20_000_000
            res = self.volume_ctrl.process(create_filtered_sample(t, Vector3(0, 0, 0), Vector3(0, 0, -1.0)))

        self.assertTrue(res.active, "Controller failed to recover after poison stream")
        self.assertTrue(res.tilt_stable)

        # 4. Now rotate and verify VolumeUp triggers
        action_emitted = None
        for i in range(30):
            t += 20_000_000
            res = self.volume_ctrl.process(create_filtered_sample(t, Vector3(0, 0, 60.0), Vector3(0, 0, -1.0)))
            if res.action is not None:
                action_emitted = res.action
                break

        self.assertEqual(action_emitted, MediaAction.VOLUME_UP, "Volume control did not trigger after recovery")

    # --------------------------------------------------------------------------
    # 4. Zero Time Delta and Timestamp Jumps
    # --------------------------------------------------------------------------

    def test_zero_time_delta_does_not_divide_by_zero(self):
        """Samples arriving with identical timestamps (dt = 0) must not cause div-by-zero."""
        t = 1_000_000_000
        s1 = create_filtered_sample(t, Vector3(0, 0, 50.0), Vector3(0, 0, -1.0))
        s2 = create_filtered_sample(t, Vector3(0, 0, 50.0), Vector3(0, 0, -1.0))  # identical timestamp

        self.volume_ctrl.process(s1)
        res2 = self.volume_ctrl.process(s2)
        self.assertIsNotNone(res2)

        self.brightness_ctrl.process(s1, is_button_pressed=True)
        bright_res2 = self.brightness_ctrl.process(s2, is_button_pressed=True)
        self.assertEqual(bright_res2.delta_percent, 0.0)

        self.mouse_ctrl.process(s1)
        mouse_out = self.mouse_ctrl.process(s2)
        self.assertIsNotNone(mouse_out)


if __name__ == "__main__":
    unittest.main()
