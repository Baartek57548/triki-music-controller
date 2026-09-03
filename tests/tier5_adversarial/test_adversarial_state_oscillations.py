"""
Tier 5 Adversarial Test: State Machine Rapid Oscillations & Hysteresis Stress.

Stress testing:
- High-frequency orientation transitions (50 Hz flipping between upright, inverted, and 90° edge)
- Edge pose entry/exit hysteresis boundaries (0.45g enter / 0.60g exit)
- Tilt angle threshold stability around 25.0 degrees
- Button contact chatter & rapid debounce gating
- Multi-state concurrency & mutex validation
Strict 0-emoji compliance enforced.
"""

from __future__ import annotations
import math
import unittest

from tests.helpers.imu_math import (
    ButtonClickType,
    EdgePoseBrightnessController,
    FullRotationGestureDetector,
    GyroscopeVolumeController,
    HoldGesturePhase,
    MediaAction,
    TrikiButtonInterpreter,
    TrikiSensorData,
    Vector3,
)
from tests.helpers.synthetic_data import create_filtered_sample


class TestAdversarialStateOscillations(unittest.TestCase):

    def test_rapid_upright_inverted_oscillation_prevents_false_triggers(self):
        """50 Hz flipping between face-up and face-down must completely suppress all gestures."""
        vol_ctrl = GyroscopeVolumeController()
        rot_detector = FullRotationGestureDetector()

        t = 1_000_000_000
        # 100 cycles of alternating face-up and face-down at 20ms intervals (2 seconds of rapid flip)
        for i in range(100):
            is_face_up = (i % 2 == 0)
            acc = Vector3(0.0, 0.0, -1.0 if is_face_up else 1.0)
            gyro = Vector3(0.0, 0.0, 100.0)  # fast rotation present!

            sample = create_filtered_sample(t, gyro, acc)
            vol_res = vol_ctrl.process(sample)
            rot_res = rot_detector.process(sample)

            self.assertIsNone(vol_res.action, f"False volume action on flip frame {i}")
            self.assertFalse(vol_res.active, f"False volume active on flip frame {i}")
            self.assertFalse(rot_res.triggered, f"False full rotation trigger on flip frame {i}")

            t += 20_000_000

    def test_rapid_edge_pose_3way_cycle_prevents_false_brightness_change(self):
        """Rapid 3-way cycling (Upright -> Edge -> Inverted -> Upright) must not accumulate brightness steps."""
        bright_ctrl = EdgePoseBrightnessController(initial_brightness_percent=50.0)
        vol_ctrl = GyroscopeVolumeController()

        t = 1_000_000_000
        # Cycle through 3 poses every 20ms for 300 frames
        for i in range(300):
            phase = i % 3
            if phase == 0:
                acc = Vector3(0.0, 0.0, -1.0)  # Upright
            elif phase == 1:
                acc = Vector3(0.0, 1.0, 0.0)   # 90° Edge
            else:
                acc = Vector3(0.0, 0.0, 1.0)   # Inverted

            # Fast rotation present
            sample = create_filtered_sample(t, Vector3(0.0, 0.0, 80.0), acc)
            bright_res = bright_ctrl.process(sample, is_button_pressed=True)
            vol_res = vol_ctrl.process(sample)

            self.assertEqual(bright_res.delta_percent, 0.0, f"False brightness step at frame {i}")
            self.assertEqual(bright_res.brightness_percent, 50.0, f"Brightness modified at frame {i}")
            self.assertIsNone(vol_res.action, f"False volume action at frame {i}")

            t += 20_000_000

    def test_edge_pose_hysteresis_exact_boundary_jitter(self):
        """Micro-movements across 0.45g and 0.60g Z boundaries must adhere to hysteresis state."""
        ctrl = EdgePoseBrightnessController(initial_brightness_percent=50.0)
        t = 1_000_000_000

        # 1. Outside edge pose: AccZ = 0.46g (> 0.45g enter threshold), Plane = 0.88g
        res1 = ctrl.process(create_filtered_sample(t, Vector3(0, 0, 0), Vector3(0.88, 0, 0.46)), is_button_pressed=True)
        self.assertFalse(res1.active, "Should be inactive when AccZ > 0.45g enter threshold")

        # 2. Cross into edge pose: AccZ = 0.44g (<= 0.45g enter threshold)
        t += 20_000_000
        res2 = ctrl.process(create_filtered_sample(t, Vector3(0, 0, 0), Vector3(0.89, 0, 0.44)), is_button_pressed=True)
        self.assertTrue(res2.active, "Should be active when AccZ <= 0.45g enter threshold")

        # 3. Move to AccZ = 0.55g: should REMAIN active because exit threshold is 0.60g!
        t += 20_000_000
        res3 = ctrl.process(create_filtered_sample(t, Vector3(0, 0, 0), Vector3(0.83, 0, 0.55)), is_button_pressed=True)
        self.assertTrue(res3.active, "Hysteresis failed: exited edge pose at AccZ=0.55g (< 0.60g exit threshold)")

        # 4. Move to AccZ = 0.61g (> 0.60g exit threshold): should EXIT
        t += 20_000_000
        res4 = ctrl.process(create_filtered_sample(t, Vector3(0, 0, 0), Vector3(0.79, 0, 0.61)), is_button_pressed=True)
        self.assertFalse(res4.active, "Should exit edge pose when AccZ > 0.60g exit threshold")

        # 5. Move back to AccZ = 0.55g: should REMAIN INACTIVE (enter requires <= 0.45g)
        t += 20_000_000
        res5 = ctrl.process(create_filtered_sample(t, Vector3(0, 0, 0), Vector3(0.83, 0, 0.55)), is_button_pressed=True)
        self.assertFalse(res5.active, "Hysteresis failed: entered edge pose at AccZ=0.55g (> 0.45g enter threshold)")

    def test_volume_tilt_angle_boundary_precision(self):
        """Precision verification of 25.0 degree maximum tilt boundary for volume control."""
        ctrl = GyroscopeVolumeController()
        t = 1_000_000_000

        # Stabilize at exactly 24.9 degrees for 2 seconds
        rad_in = math.radians(24.9)
        acc_in = Vector3(math.sin(rad_in), 0.0, -math.cos(rad_in))
        for i in range(105):
            t += 20_000_000
            res = ctrl.process(create_filtered_sample(t, Vector3(0, 0, 0), acc_in))

        self.assertTrue(res.active, "24.9 deg tilt should allow volume stabilization")
        self.assertTrue(res.within_tilt_range)

        # Micro tilt to 25.2 degrees (> 25.0 deg threshold)
        t += 20_000_000
        rad_out = math.radians(25.2)
        acc_out = Vector3(math.sin(rad_out), 0.0, -math.cos(rad_out))
        res_out = ctrl.process(create_filtered_sample(t, Vector3(0, 0, 0), acc_out))

        self.assertFalse(res_out.within_tilt_range, "25.2 deg tilt should exceed 25.0 deg range")
        self.assertFalse(res_out.active)

    def test_button_interpreter_contact_chatter_rejection(self):
        """Rapid 0/1 bouncing (< 18ms debounce) must be filtered without phantom clicks."""
        interpreter = TrikiButtonInterpreter()
        t = 1_000_000_000

        # Protocol discovery
        for i in range(15):
            t += 20_000_000
            sample = TrikiSensorData(i, t, Vector3(0, 0, 0), Vector3(0, 0, -1), None, None, 0)
            interpreter.process(sample)

        # Rapid contact chatter: flip button every 3 ms for 100 ms (chatter noise)
        for i in range(33):
            t += 3_000_000
            raw_state = (i % 2)
            sample = TrikiSensorData(100 + i, t, Vector3(0, 0, 0), Vector3(0, 0, -1), None, None, raw_state)
            event = interpreter.process(sample)
            self.assertIsNone(event, f"Phantom click emitted during chatter at sample {i}")

        # Chatter stops at unpressed (0) for 100 ms
        for i in range(10):
            t += 10_000_000
            sample = TrikiSensorData(200 + i, t, Vector3(0, 0, 0), Vector3(0, 0, -1), None, None, 0)
            event = interpreter.process(sample)
            self.assertIsNone(event)

        self.assertFalse(interpreter.is_pressed)

    def test_button_hold_consumption_suppresses_click_on_release(self):
        """Holding button for 4s, consuming hold, then releasing must NOT generate a click."""
        interpreter = TrikiButtonInterpreter()
        t = 1_000_000_000

        # Protocol discovery
        for i in range(15):
            t += 20_000_000
            interpreter.process(TrikiSensorData(i, t, Vector3(0, 0, 0), Vector3(0, 0, -1), None, None, 0))

        # Press button and hold
        t += 20_000_000
        interpreter.process(TrikiSensorData(100, t, Vector3(0, 0, 0), Vector3(0, 0, -1), None, None, 1))
        t += 20_000_000
        interpreter.process(TrikiSensorData(101, t, Vector3(0, 0, 0), Vector3(0, 0, -1), None, None, 1))

        self.assertTrue(interpreter.is_pressed)

        # Hold for 4.2 seconds
        for i in range(210):
            t += 20_000_000
            interpreter.process(TrikiSensorData(102 + i, t, Vector3(0, 0, 0), Vector3(0, 0, -1), None, None, 1))

        # Consume hold
        consumed = interpreter.consume_current_hold()
        self.assertTrue(consumed)

        # Release button after hold
        t += 20_000_000
        release_event = interpreter.process(TrikiSensorData(400, t, Vector3(0, 0, 0), Vector3(0, 0, -1), None, None, 0))
        self.assertIsNone(release_event, "Released hold must not generate click event")

        # Wait past multi-click timeout (500 ms)
        t += 500_000_000
        timeout_event = interpreter.process(TrikiSensorData(401, t, Vector3(0, 0, 0), Vector3(0, 0, -1), None, None, 0))
        self.assertIsNone(timeout_event)


if __name__ == "__main__":
    unittest.main()
