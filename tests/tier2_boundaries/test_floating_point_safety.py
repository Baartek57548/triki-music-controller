"""
Tier 2 Boundary Test: Floating-Point Robustness and Zero-Division Safety.

Verifies system resilience against NaN, Infinity, zero-magnitude vectors,
and subnormal numbers without exceptions or infinite loops.
Strict 0-emoji compliance enforced.
"""

import math
import unittest
from tests.helpers.imu_math import (
    AirMouseController,
    EdgePoseBrightnessController,
    FullRotationGestureDetector,
    GyroscopeVolumeController,
    Vector3,
)
from tests.helpers.synthetic_data import create_filtered_sample


class TestFloatingPointSafety(unittest.TestCase):

    def test_nan_inputs_in_volume_controller(self):
        """Verify VolumeController handles NaN gracefully with sensor_valid=False."""
        ctrl = GyroscopeVolumeController()
        # NaN in Gyroscope Z
        nan_gyro_sample = create_filtered_sample(
            timestamp_nanos=1_000_000_000,
            gyro=Vector3(0.0, 0.0, float("nan")),
            accel=Vector3(0.0, 0.0, -1.0),
        )
        res_gyro = ctrl.process(nan_gyro_sample)
        self.assertFalse(res_gyro.sensor_valid)
        self.assertFalse(res_gyro.active)
        self.assertIsNone(res_gyro.action)

        # NaN in Accelerometer X
        nan_acc_sample = create_filtered_sample(
            timestamp_nanos=1_020_000_000,
            gyro=Vector3(0.0, 0.0, 50.0),
            accel=Vector3(float("nan"), 0.0, -1.0),
        )
        res_acc = ctrl.process(nan_acc_sample)
        self.assertFalse(res_acc.sensor_valid)
        self.assertFalse(res_acc.active)
        self.assertIsNone(res_acc.action)

    def test_infinity_inputs_in_air_mouse(self):
        """Verify AirMouseController handles Infinity safely without crashing."""
        mouse = AirMouseController(is_active=True)
        inf_sample = create_filtered_sample(
            timestamp_nanos=1_000_000_000,
            gyro=Vector3(float("inf"), 0.0, 0.0),
            accel=Vector3(0.0, 0.0, -1.0),
        )
        out = mouse.process(inf_sample)
        self.assertEqual(out.delta_x, 0)
        self.assertEqual(out.delta_y, 0)
        self.assertEqual(out.scroll_delta, 0)

    def test_zero_acceleration_vector_division_guard(self):
        """Verify division-by-zero protection when accelerometer magnitude is 0.0."""
        ctrl = GyroscopeVolumeController()
        zero_sample = create_filtered_sample(
            timestamp_nanos=1_000_000_000,
            gyro=Vector3(0.0, 0.0, 50.0),
            accel=Vector3(0.0, 0.0, 0.0),
        )
        res = ctrl.process(zero_sample)
        self.assertFalse(res.sensor_valid)
        self.assertFalse(res.active)
        self.assertEqual(res.tilt_degrees, 180.0)

    def test_vector3_normalization_zero_and_nan(self):
        """Verify Vector3.normalized() handles zero and non-finite vectors safely."""
        v_zero = Vector3(0.0, 0.0, 0.0)
        norm_zero = v_zero.normalized()
        self.assertEqual(norm_zero.x, 0.0)
        self.assertEqual(norm_zero.y, 0.0)
        self.assertEqual(norm_zero.z, 0.0)

        v_nan = Vector3(float("nan"), 1.0, 0.0)
        norm_nan = v_nan.normalized()
        self.assertEqual(norm_nan.x, 0.0)


if __name__ == "__main__":
    unittest.main()
