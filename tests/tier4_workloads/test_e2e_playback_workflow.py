"""
Tier 4 Workload Test: Full End-to-End User Playback Workflow Simulation.

Simulates a complete, multi-stage user session covering BLE wake gating, stream startup,
rotary volume adjustment, inverted track skipping, edge brightness control,
air mouse navigation, and clean disconnection.
Strict 0-emoji compliance enforced.
"""

import unittest
from tests.helpers.imu_math import (
    AirMouseController,
    EdgePoseBrightnessController,
    FullRotationGestureDetector,
    GyroscopeVolumeController,
    HoldGesturePhase,
    MediaAction,
    RotationGestureDirection,
    TrikiButtonInterpreter,
    TrikiProtocolDecoder,
    Vector3,
    WakeAdvertisementGate,
)
from tests.helpers.synthetic_data import create_filtered_sample, encode_raw_frame


class TestE2ePlaybackWorkflow(unittest.TestCase):

    def test_full_session_lifecycle(self):
        """Execute a comprehensive, simulated user playback and interaction session."""
        t_current = 1_000_000_000

        # -------------------------------------------------------------------
        # Phase 1: Wake Advertisement Gate & Connection Initiation
        # -------------------------------------------------------------------
        wake_gate = WakeAdvertisementGate()
        # Initialize silence baseline
        wake_gate.observe_advertisement(t_current)
        # Device sleeps for 6.0 seconds (6_000_000_000 ns)
        t_current += 6_000_000_000
        # User picks up capsule -> wake advertisement emitted
        allowed = wake_gate.observe_advertisement(t_current)
        self.assertTrue(allowed, "Connection should be allowed after 6s silence")

        # -------------------------------------------------------------------
        # Phase 2: BLE NUS Stream Startup & Warmup Frame Discard
        # -------------------------------------------------------------------
        decoder = TrikiProtocolDecoder(startup_frames_to_discard=20)
        # Generate 20 startup frames
        for i in range(20):
            frame = encode_raw_frame(0, 0, 0, 0, 0, 0, -1.0)
            t_current += 20_000_000
            samples = decoder.decode(frame, t_current)
            self.assertEqual(len(samples), 0, "Startup frames must be discarded")
        self.assertEqual(decoder.statistics.discarded_startup_frames, 20)

        # -------------------------------------------------------------------
        # Phase 3: Upright Rotary Volume Adjustment
        # -------------------------------------------------------------------
        vol_ctrl = GyroscopeVolumeController()
        # Stabilize face up on table for 2.0s (100 samples at 50 Hz)
        for i in range(105):
            t_current += 20_000_000
            sample = create_filtered_sample(t_current, Vector3(0, 0, 0), Vector3(0, 0, -1.0), frame_index=i)
            vol_ctrl.process(sample)

        # Rotate clockwise: 60 dps for 2.0s (100 samples -> produces >= 2 VolumeUp steps)
        volume_up_actions = []
        for i in range(100):
            t_current += 20_000_000
            sample = create_filtered_sample(t_current, Vector3(0, 0, 60.0), Vector3(0, 0, -1.0))
            res = vol_ctrl.process(sample)
            if res.action is not None:
                volume_up_actions.append(res.action)

        self.assertGreaterEqual(len(volume_up_actions), 2, "Should emit at least 2 VolumeUp steps")
        self.assertTrue(all(a == MediaAction.VOLUME_UP for a in volume_up_actions))

        # Rotate counter-clockwise: -60 dps for 1.0s (50 samples -> produces >= 1 VolumeDown step)
        volume_down_actions = []
        for i in range(50):
            t_current += 20_000_000
            sample = create_filtered_sample(t_current, Vector3(0, 0, -60.0), Vector3(0, 0, -1.0))
            res = vol_ctrl.process(sample)
            if res.action is not None:
                volume_down_actions.append(res.action)

        self.assertGreaterEqual(len(volume_down_actions), 1, "Should emit at least 1 VolumeDown step")
        self.assertTrue(all(a == MediaAction.VOLUME_DOWN for a in volume_down_actions))

        # -------------------------------------------------------------------
        # Phase 4: Inverted Track Skip (Face-Down 200 deg Rotation)
        # -------------------------------------------------------------------
        rot_detector = FullRotationGestureDetector()
        # Flip face down (Acc Z = +1.0g) and stabilize for 500 ms (25 samples)
        for i in range(30):
            t_current += 20_000_000
            sample = create_filtered_sample(t_current, Vector3(0, 0, 0), Vector3(0, 0, 1.0))
            res = rot_detector.process(sample)

        self.assertEqual(res.phase, HoldGesturePhase.READY)

        # Rotate clockwise at 60 dps for 4.0s (~240 degrees total)
        trigger_results = []
        for i in range(200):
            t_current += 20_000_000
            sample = create_filtered_sample(t_current, Vector3(0, 0, 60.0), Vector3(0, 0, 1.0))
            res = rot_detector.process(sample)
            if res.triggered:
                trigger_results.append(res)

        self.assertGreaterEqual(len(trigger_results), 1, "Should trigger full rotation gesture")
        self.assertEqual(trigger_results[0].direction, RotationGestureDirection.RIGHT)

        # -------------------------------------------------------------------
        # Phase 5: 90-Degree Edge Pose Brightness Adjustment
        # -------------------------------------------------------------------
        bright_ctrl = EdgePoseBrightnessController(initial_brightness_percent=50.0)
        # Place on edge: Acc Y = 1.0g, Acc Z = 0.0g (Frame 1 initializes stabilization timestamp)
        t_current += 20_000_000
        sample_edge1 = create_filtered_sample(t_current, Vector3(0, 0, 0), Vector3(0, 1.0, 0.0))
        res_edge1 = bright_ctrl.process(sample_edge1, is_button_pressed=True)
        self.assertTrue(res_edge1.active)

        # Frame 2 with button held bypasses timer and becomes ready
        t_current += 20_000_000
        sample_edge2 = create_filtered_sample(t_current, Vector3(0, 0, 0), Vector3(0, 1.0, 0.0))
        res_edge2 = bright_ctrl.process(sample_edge2, is_button_pressed=True)
        self.assertTrue(res_edge2.active)
        self.assertTrue(res_edge2.ready)

        # Rotate clockwise at 50 dps for 1.0s (50 degrees -> 50 / 2.5 = +20% brightness)
        for i in range(50):
            t_current += 20_000_000
            sample = create_filtered_sample(t_current, Vector3(0, 0, 50.0), Vector3(0, 1.0, 0.0))
            bright_ctrl.process(sample, is_button_pressed=True)

        self.assertAlmostEqual(bright_ctrl.current_brightness_percent, 70.0, delta=1.0)

        # -------------------------------------------------------------------
        # Phase 6: Free-Air Air Mouse Navigation and Lateral Scroll
        # -------------------------------------------------------------------
        air_mouse = AirMouseController(is_active=True)
        # Lift into free air (Acc Y = 0.5g, Acc Z = 0.0g) and move wrist
        t_current += 20_000_000
        sample_air = create_filtered_sample(t_current, Vector3(25.0, 0, -25.0), Vector3(0, 0.5, 0.0))
        out_mouse = air_mouse.process(sample_air)
        self.assertTrue(out_mouse.is_active)
        self.assertFalse(out_mouse.is_scroll_mode)
        self.assertTrue(out_mouse.delta_x != 0 or out_mouse.delta_y != 0)

        # Tilt to 90 deg lateral edge (Acc Y = 0.8g, Acc Z = 0.1g) -> engages scroll mode
        t_current += 20_000_000
        sample_scroll = create_filtered_sample(t_current, Vector3(0, 0, 40.0), Vector3(0, 0.8, 0.1))
        out_scroll = air_mouse.process(sample_scroll)
        self.assertTrue(out_scroll.is_scroll_mode)

        # Rotate for 0.5s to generate scroll steps
        for i in range(25):
            t_current += 20_000_000
            sample = create_filtered_sample(t_current, Vector3(0, 0, 40.0), Vector3(0, 0.8, 0.1))
            out_scroll = air_mouse.process(sample)

        # -------------------------------------------------------------------
        # Phase 7: Session Termination and Reset
        # -------------------------------------------------------------------
        vol_ctrl.reset()
        bright_ctrl.reset()
        rot_detector.reset()
        air_mouse.reset()
        decoder.reset()

        self.assertEqual(decoder.statistics.decoded_frames, 0)
        self.assertEqual(bright_ctrl.current_brightness_percent, 70.0)


if __name__ == "__main__":
    unittest.main()
