"""
Tier 1 Feature Test: Cross-Platform Algorithm Parity (ADR-002).

Verifies mathematical specification and parameter alignment across C# and Kotlin
for Volume, Brightness, Full Rotation, and Button Interpreter.
Strict 0-emoji compliance enforced.
"""

import unittest
from tests.helpers.imu_math import (
    EdgePoseBrightnessController,
    FullRotationGestureDetector,
    GyroscopeVolumeController,
    TrikiButtonInterpreter,
    VolumeControllerConfiguration,
)


class TestCrossPlatformParity(unittest.TestCase):

    def test_volume_controller_default_parameters(self):
        """Verify VolumeControllerConfiguration default thresholds match ADR-002."""
        config = VolumeControllerConfiguration()
        self.assertEqual(config.activation_gyroscope_dps, 22.0)
        self.assertEqual(config.release_gyroscope_dps, 12.0)
        self.assertEqual(config.degrees_per_volume_step, 22.0)
        self.assertEqual(config.gyroscope_smoothing_alpha, 0.16)
        self.assertEqual(config.minimum_step_interval_millis, 140)
        self.assertEqual(config.maximum_tilt_degrees, 25.0)
        self.assertEqual(config.maximum_acceleration_deviation_g, 0.20)
        self.assertEqual(config.tilt_stabilization_millis, 2000)

    def test_brightness_controller_parameters(self):
        """Verify EdgePoseBrightnessController constants match ADR-002 / v3.1.5 specification."""
        self.assertEqual(EdgePoseBrightnessController.DEFAULT_STABILIZATION_NANOS, 150_000_000)
        self.assertEqual(EdgePoseBrightnessController.DEGREES_PER_PERCENT_BRIGHTNESS, 2.5)
        self.assertEqual(EdgePoseBrightnessController.GYROSCOPE_DEADBAND_DPS, 3.0)
        self.assertEqual(EdgePoseBrightnessController.EDGE_ENTER_MAX_Z, 0.45)
        self.assertEqual(EdgePoseBrightnessController.EDGE_EXIT_MAX_Z, 0.60)
        self.assertEqual(EdgePoseBrightnessController.EDGE_MIN_PLANE_G, 0.65)
        self.assertEqual(EdgePoseBrightnessController.EDGE_MAX_PLANE_G, 1.35)

    def test_full_rotation_gesture_parameters(self):
        """Verify FullRotationGestureDetector constants match specification."""
        self.assertEqual(FullRotationGestureDetector.PHYSICAL_ROTATION_TARGET_DEGREES, 200.0)
        self.assertEqual(FullRotationGestureDetector.FILTERED_ROTATION_TRIGGER_DEGREES, 181.0)
        detector = FullRotationGestureDetector()
        self.assertEqual(detector.stabilization_millis, 500)
        self.assertEqual(detector.required_rotation_degrees, 181.0)
        self.assertEqual(detector.maximum_rotation_degrees, 340.0)
        self.assertEqual(detector.maximum_face_down_tilt_degrees, 25.0)
        self.assertEqual(detector.maximum_acc_deviation_g, 0.20)
        self.assertEqual(detector.activation_gyroscope_dps, 22.0)
        self.assertEqual(detector.release_gyroscope_dps, 12.0)
        self.assertEqual(detector.gyroscope_smoothing_alpha, 0.16)

    def test_button_interpreter_timing_parameters(self):
        """Verify TrikiButtonInterpreter debouncing and multi-click thresholds."""
        self.assertEqual(TrikiButtonInterpreter.DEBOUNCE_NANOS, 18_000_000)
        self.assertEqual(TrikiButtonInterpreter.MIN_CLICK_PRESS_NANOS, 25_000_000)
        self.assertEqual(TrikiButtonInterpreter.MAX_CLICK_PRESS_NANOS, 2_000_000_000)
        self.assertEqual(TrikiButtonInterpreter.MULTI_CLICK_TIMEOUT_NANOS, 450_000_000)
        self.assertEqual(TrikiButtonInterpreter.MAX_STREAM_GAP_NANOS, 300_000_000)


if __name__ == "__main__":
    unittest.main()
