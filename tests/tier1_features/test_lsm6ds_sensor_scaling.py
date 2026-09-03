"""
Tier 1 Feature Test: LSM6DS Sensor Scaling and Engineering Units Conversion.

Verifies exact LSB-to-physical unit conversions (2048 LSB/g, 70 mdps/LSB -> 14.2857 LSB/dps)
and 3D vector magnitude operations according to hardware specifications.
Strict 0-emoji compliance enforced.
"""

import math
import struct
import unittest
from tests.helpers.imu_math import (
    TrikiProtocol,
    TrikiProtocolDecoder,
    Vector3,
)


class TestLsm6dsSensorScaling(unittest.TestCase):

    def setUp(self):
        self.decoder = TrikiProtocolDecoder(startup_frames_to_discard=0)

    def test_accelerometer_scaling_exactness(self):
        """Verify 2048 LSB/g (0.488 mg/LSB) scaling across positive and negative ranges."""
        # 1g on Z axis: raw_az = 2048
        frame_1g = bytes([0x22, 0x00]) + struct.pack("<6h", 0, 0, 0, 0, 0, 2048)
        samples = self.decoder.decode(frame_1g, 1_000_000_000)
        self.assertEqual(len(samples), 1)
        self.assertAlmostEqual(samples[0].accelerometer_g.z, 1.0, places=4)
        self.assertEqual(samples[0].raw_accelerometer.z, 2048)

        # -1g on Z axis: raw_az = -2048
        frame_neg1g = bytes([0x22, 0x00]) + struct.pack("<6h", 0, 0, 0, 0, 0, -2048)
        samples = self.decoder.decode(frame_neg1g, 1_020_000_000)
        self.assertEqual(len(samples), 1)
        self.assertAlmostEqual(samples[0].accelerometer_g.z, -1.0, places=4)

        # 0.5g on X, 0.5g on Y: raw = 1024
        frame_half_g = bytes([0x22, 0x00]) + struct.pack("<6h", 0, 0, 0, 1024, 1024, 0)
        samples = self.decoder.decode(frame_half_g, 1_040_000_000)
        self.assertEqual(len(samples), 1)
        self.assertAlmostEqual(samples[0].accelerometer_g.x, 0.5, places=4)
        self.assertAlmostEqual(samples[0].accelerometer_g.y, 0.5, places=4)

    def test_gyroscope_scaling_exactness(self):
        """Verify 70 mdps/LSB (1 / 0.070 = 14.285714 LSB/dps) scaling."""
        # 100 dps on Z axis: raw_gz = round(100 / 0.070) = 1429
        raw_100_dps = int(round(100.0 / 0.070))
        frame_100dps = bytes([0x22, 0x00]) + struct.pack("<6h", 0, 0, raw_100_dps, 0, 0, 2048)
        samples = self.decoder.decode(frame_100dps, 1_000_000_000)
        self.assertEqual(len(samples), 1)
        self.assertAlmostEqual(samples[0].gyroscope_dps.z, 100.0, delta=0.05)

        # -250 dps on X axis: raw_gx = round(-250 / 0.070) = -3571
        raw_neg250_dps = int(round(-250.0 / 0.070))
        frame_neg250dps = bytes([0x22, 0x00]) + struct.pack("<6h", raw_neg250_dps, 0, 0, 0, 0, 2048)
        samples = self.decoder.decode(frame_neg250dps, 1_020_000_000)
        self.assertEqual(len(samples), 1)
        self.assertAlmostEqual(samples[0].gyroscope_dps.x, -250.0, delta=0.05)

    def test_vector3_magnitude_and_normalization(self):
        """Verify 3D Euclidean magnitude and normalization routines."""
        v = Vector3(3.0, 4.0, 0.0)
        self.assertAlmostEqual(v.magnitude, 5.0, places=5)

        norm = v.normalized()
        self.assertAlmostEqual(norm.x, 0.6, places=5)
        self.assertAlmostEqual(norm.y, 0.8, places=5)
        self.assertAlmostEqual(norm.z, 0.0, places=5)
        self.assertAlmostEqual(norm.magnitude, 1.0, places=5)

        # Zero vector normalization protection
        v_zero = Vector3(0.0, 0.0, 0.0)
        norm_zero = v_zero.normalized()
        self.assertEqual(norm_zero.x, 0.0)
        self.assertEqual(norm_zero.y, 0.0)
        self.assertEqual(norm_zero.z, 0.0)


if __name__ == "__main__":
    unittest.main()
