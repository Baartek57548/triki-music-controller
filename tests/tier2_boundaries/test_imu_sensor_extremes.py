"""
Tier 2 Boundary Test: IMU Sensor Extreme Values and High-G Shocks.

Verifies system stability under INT16 register limits (-32768/+32767),
extreme physical acceleration shocks (>16g), fast spins (>2000 dps),
and precision deadband boundaries.
Strict 0-emoji compliance enforced.
"""

import struct
import unittest
from tests.helpers.imu_math import (
    AirMouseController,
    EdgePoseBrightnessController,
    FullRotationGestureDetector,
    GyroscopeVolumeController,
    TrikiProtocolDecoder,
    Vector3,
)
from tests.helpers.synthetic_data import create_filtered_sample, encode_raw_frame


class TestImuSensorExtremes(unittest.TestCase):

    def setUp(self):
        self.decoder = TrikiProtocolDecoder(startup_frames_to_discard=0)

    def test_int16_register_extremes_decoding(self):
        """Verify decoder safely unpacks INT16 min (-32768) and max (+32767) values."""
        payload_min = struct.pack("<6h", -32768, -32768, -32768, -32768, -32768, -32768)
        frame_min = bytes([0x22, 0x00]) + payload_min
        samples_min = self.decoder.decode(frame_min, 1_000_000_000)
        self.assertEqual(len(samples_min), 1)
        self.assertAlmostEqual(samples_min[0].gyroscope_dps.x, -32768 * 0.070, delta=0.01)
        self.assertAlmostEqual(samples_min[0].accelerometer_g.x, -32768 / 2048.0, delta=0.01)

        payload_max = struct.pack("<6h", 32767, 32767, 32767, 32767, 32767, 32767)
        frame_max = bytes([0x22, 0x00]) + payload_max
        samples_max = self.decoder.decode(frame_max, 1_020_000_000)
        self.assertEqual(len(samples_max), 1)
        self.assertAlmostEqual(samples_max[0].gyroscope_dps.x, 32767 * 0.070, delta=0.01)
        self.assertAlmostEqual(samples_max[0].accelerometer_g.x, 32767 / 2048.0, delta=0.01)

    def test_high_g_acceleration_shock_rejection(self):
        """Verify high-G drops/taps (>16g or >2.5g) are rejected by orientation controllers."""
        volume_ctrl = GyroscopeVolumeController()
        # Stabilize at 1g face-up
        for i in range(110):
            sample = create_filtered_sample(
                timestamp_nanos=i * 20_000_000,
                gyro=Vector3(0, 0, 0),
                accel=Vector3(0, 0, -1.0),
            )
            volume_ctrl.process(sample)

        # 16g shock impact
        shock_sample = create_filtered_sample(
            timestamp_nanos=2_300_000_000,
            gyro=Vector3(0, 0, 50.0),
            accel=Vector3(0, 0, -16.0),
        )
        res = volume_ctrl.process(shock_sample)
        self.assertFalse(res.acceleration_stable)
        self.assertFalse(res.active)
        self.assertIsNone(res.action)

    def test_full_rotation_rejects_extreme_acceleration(self):
        """Verify FullRotationGestureDetector rejects acceleration outside [0.20g, 2.50g]."""
        detector = FullRotationGestureDetector()
        low_g = create_filtered_sample(1_000_000_000, Vector3(0, 0, 0), Vector3(0, 0, 0.10))
        res_low = detector.process(low_g)
        self.assertEqual(res_low.stabilization_progress, 0.0)

        high_g = create_filtered_sample(1_020_000_000, Vector3(0, 0, 0), Vector3(0, 0, 3.50))
        res_high = detector.process(high_g)
        self.assertEqual(res_high.stabilization_progress, 0.0)

    def test_brightness_deadband_exact_boundary(self):
        """Verify 3.0 dps deadband in EdgePoseBrightnessController."""
        ctrl = EdgePoseBrightnessController(initial_brightness_percent=50.0)
        t0 = 1_000_000_000
        # Enter edge pose (Acc Y = 1.0g, Acc Z = 0.0g)
        ctrl.process(create_filtered_sample(t0, Vector3(0, 0, 0), Vector3(0, 1.0, 0)), is_button_pressed=True)

        # 2.9 dps (< 3.0 dps) -> no change
        res_sub = ctrl.process(
            create_filtered_sample(t0 + 20_000_000, Vector3(0, 0, 2.9), Vector3(0, 1.0, 0)),
            is_button_pressed=True,
        )
        self.assertEqual(res_sub.delta_percent, 0.0)
        self.assertEqual(res_sub.brightness_percent, 50.0)

        # Rotate at 50 dps for 5 frames at 20ms (0.1s -> 5 degrees -> 2% increase)
        for i in range(1, 6):
            res_active = ctrl.process(
                create_filtered_sample(t0 + 20_000_000 + i * 20_000_000, Vector3(0, 0, 50.0), Vector3(0, 1.0, 0)),
                is_button_pressed=True,
            )
        self.assertGreater(ctrl.current_brightness_percent, 50.0)


if __name__ == "__main__":
    unittest.main()
