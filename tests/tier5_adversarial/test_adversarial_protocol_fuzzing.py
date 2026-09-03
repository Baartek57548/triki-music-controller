"""
Tier 5 Adversarial Test: BLE Protocol Fuzzing, Fragmentation & Register Extremes.

Stress testing:
- Extreme INT16 limits (-32768, +32767) in all sensor axes simultaneously
- Massive buffer fuzzing with random byte streams and corrupted headers
- Extreme fragmentation (streaming 1 byte at a time through decoder)
- Rapid sequence number variations (0x00 to 0x0F)
Strict 0-emoji compliance enforced.
"""

from __future__ import annotations
import os
import random
import struct
import unittest

from tests.helpers.imu_math import TrikiProtocol, TrikiProtocolDecoder


class TestAdversarialProtocolFuzzing(unittest.TestCase):

    def setUp(self):
        self.decoder = TrikiProtocolDecoder(startup_frames_to_discard=0)

    def test_all_axes_int16_minimum_extremes(self):
        """Decoding all channels at -32768 produces mathematically exact scaled values."""
        payload = struct.pack("<6h", -32768, -32768, -32768, -32768, -32768, -32768)
        frame = bytes([0x22, 0x05]) + payload

        samples = self.decoder.decode(frame, 1_000_000_000)
        self.assertEqual(len(samples), 1)
        s = samples[0]

        # Check gyro: -32768 * 0.070 dps = -2293.76 dps
        expected_gyro = -32768.0 * 0.070
        self.assertAlmostEqual(s.gyroscope_dps.x, expected_gyro, delta=0.01)
        self.assertAlmostEqual(s.gyroscope_dps.y, expected_gyro, delta=0.01)
        self.assertAlmostEqual(s.gyroscope_dps.z, expected_gyro, delta=0.01)

        # Check accel: -32768 / 2048.0 = -16.0 g
        expected_accel = -16.0
        self.assertAlmostEqual(s.accelerometer_g.x, expected_accel, delta=0.001)
        self.assertAlmostEqual(s.accelerometer_g.y, expected_accel, delta=0.001)
        self.assertAlmostEqual(s.accelerometer_g.z, expected_accel, delta=0.001)

    def test_all_axes_int16_maximum_extremes(self):
        """Decoding all channels at +32767 produces mathematically exact scaled values."""
        payload = struct.pack("<6h", 32767, 32767, 32767, 32767, 32767, 32767)
        frame = bytes([0x22, 0x05]) + payload

        samples = self.decoder.decode(frame, 1_000_000_000)
        self.assertEqual(len(samples), 1)
        s = samples[0]

        # Check gyro: 32767 * 0.070 dps = 2293.69 dps
        expected_gyro = 32767.0 * 0.070
        self.assertAlmostEqual(s.gyroscope_dps.x, expected_gyro, delta=0.01)
        self.assertAlmostEqual(s.gyroscope_dps.y, expected_gyro, delta=0.01)
        self.assertAlmostEqual(s.gyroscope_dps.z, expected_gyro, delta=0.01)

        # Check accel: 32767 / 2048.0 = 15.99951 g
        expected_accel = 32767.0 / 2048.0
        self.assertAlmostEqual(s.accelerometer_g.x, expected_accel, delta=0.001)
        self.assertAlmostEqual(s.accelerometer_g.y, expected_accel, delta=0.001)
        self.assertAlmostEqual(s.accelerometer_g.z, expected_accel, delta=0.001)

    def test_single_byte_streaming_fragmentation_fuzz(self):
        """Feed 50 valid frames byte-by-byte (1 byte per call) to test reassembly."""
        raw_stream = bytearray()
        for i in range(50):
            payload = struct.pack("<6h", i * 10, -i * 10, i * 5, 0, 0, -2048)
            frame = bytes([0x22, i % 16]) + payload
            raw_stream.extend(frame)

        decoded_samples = []
        t = 1_000_000_000
        for b in raw_stream:
            chunk = bytes([b])
            res = self.decoder.decode(chunk, t)
            decoded_samples.extend(res)
            t += 1_000_000

        self.assertEqual(len(decoded_samples), 50, f"Expected 50 reassembled frames, got {len(decoded_samples)}")
        self.assertEqual(self.decoder.statistics.dropped_bytes, 0)

    def test_random_fuzzing_garbage_stream_does_not_crash(self):
        """Feed 50 KB of pseudorandom bytes with embedded valid frames into decoder."""
        rng = random.Random(42)
        garbage_buffer = bytearray(rng.randbytes(50_000))

        # Embed a valid frame at offset 10,000
        valid_payload = struct.pack("<6h", 100, 200, 300, 0, 0, -2048)
        valid_frame = bytes([0x22, 0x01]) + valid_payload
        garbage_buffer[10_000:10_014] = valid_frame

        # Feed in random chunk sizes
        offset = 0
        decoded_samples = []
        t = 1_000_000_000
        while offset < len(garbage_buffer):
            chunk_size = rng.randint(1, 128)
            chunk = bytes(garbage_buffer[offset:offset + chunk_size])
            res = self.decoder.decode(chunk, t)
            decoded_samples.extend(res)
            offset += chunk_size
            t += 5_000_000

        # Should have found at least the deliberately embedded valid frame
        self.assertGreaterEqual(len(decoded_samples), 1)
        self.assertGreater(self.decoder.statistics.dropped_bytes, 0)


if __name__ == "__main__":
    unittest.main()
