"""
Tier 1 Feature Test: BLE Protocol Framing and Decoding.

Verifies 14-byte NUS frame parsing, 0x22 header matching, packet ID masking (0x00-0x0F),
20 startup frame discard, monotonic timestamp progression, and dropped byte accounting.
Strict 0-emoji compliance enforced.
"""

import unittest
from tests.helpers.imu_math import (
    TrikiProtocol,
    TrikiProtocolDecoder,
    Vector3,
)
from tests.helpers.synthetic_data import encode_raw_frame


class TestBleProtocolDecoding(unittest.TestCase):

    def setUp(self):
        self.decoder = TrikiProtocolDecoder(startup_frames_to_discard=20)

    def test_startup_frames_are_discarded(self):
        """Verify that exactly the first 20 valid frames are discarded as warmup."""
        frame = encode_raw_frame(status=0, gx_dps=0.0, gy_dps=0.0, gz_dps=0.0, ax_g=0.0, ay_g=0.0, az_g=1.0)

        # Feed 20 frames one by one
        for i in range(20):
            samples = self.decoder.decode(frame, received_at_nanos=1_000_000_000 + i * 20_000_000)
            self.assertEqual(len(samples), 0, f"Frame {i+1} should have been discarded")

        stats = self.decoder.statistics
        self.assertEqual(stats.discarded_startup_frames, 20)
        self.assertEqual(stats.decoded_frames, 0)

        # Frame 21 should be decoded
        samples = self.decoder.decode(frame, received_at_nanos=1_000_000_000 + 20 * 20_000_000)
        self.assertEqual(len(samples), 1)
        self.assertEqual(samples[0].frame_index, 0)
        self.assertEqual(self.decoder.statistics.decoded_frames, 1)

    def test_multi_frame_notification_timestamp_interpolation(self):
        """Verify that multi-frame packets have correct backwards timestamp interpolation."""
        frame1 = encode_raw_frame(status=1, gx_dps=10.0, gy_dps=0.0, gz_dps=0.0, ax_g=0.0, ay_g=0.0, az_g=1.0)
        frame2 = encode_raw_frame(status=2, gx_dps=20.0, gy_dps=0.0, gz_dps=0.0, ax_g=0.0, ay_g=0.0, az_g=1.0)
        frame3 = encode_raw_frame(status=3, gx_dps=30.0, gy_dps=0.0, gz_dps=0.0, ax_g=0.0, ay_g=0.0, az_g=1.0)

        # Discard warmup frames first
        dummy = encode_raw_frame(0, 0, 0, 0, 0, 0, 1)
        for i in range(20):
            self.decoder.decode(dummy, 1_000_000_000 + i * 20_000_000)

        notification = frame1 + frame2 + frame3
        recv_time = 2_000_000_000
        samples = self.decoder.decode(notification, received_at_nanos=recv_time)

        self.assertEqual(len(samples), 3)
        nominal_dt = TrikiProtocolDecoder.APPROXIMATE_SAMPLE_PERIOD_NANOS
        self.assertEqual(samples[0].timestamp_nanos, recv_time - 2 * nominal_dt)
        self.assertEqual(samples[1].timestamp_nanos, recv_time - 1 * nominal_dt)
        self.assertEqual(samples[2].timestamp_nanos, recv_time)

        # Verify values decoded properly
        self.assertAlmostEqual(samples[0].gyroscope_dps.x, 10.0, delta=0.1)
        self.assertAlmostEqual(samples[1].gyroscope_dps.x, 20.0, delta=0.1)
        self.assertAlmostEqual(samples[2].gyroscope_dps.x, 30.0, delta=0.1)

    def test_packet_id_and_status_byte_acceptance(self):
        """Verify that status byte accepts values from 0x00 to 0x0F (4-bit counter / flag)."""
        decoder = TrikiProtocolDecoder(startup_frames_to_discard=0)
        for packet_id in range(16):
            frame = encode_raw_frame(status=packet_id, gx_dps=0, gy_dps=0, gz_dps=0, ax_g=0, ay_g=0, az_g=1)
            samples = decoder.decode(frame, received_at_nanos=1_000_000_000 + packet_id * 20_000_000)
            self.assertEqual(len(samples), 1)
            self.assertEqual(samples[0].status, packet_id)

    def test_dropped_bytes_accounting_on_garbage_preamble(self):
        """Verify that leading garbage bytes before valid 0x22 header are counted as dropped."""
        decoder = TrikiProtocolDecoder(startup_frames_to_discard=0)
        garbage = bytes([0xAA, 0xBB, 0xCC, 0xDD, 0xEE])
        valid_frame = encode_raw_frame(status=0, gx_dps=0, gy_dps=0, gz_dps=0, ax_g=0, ay_g=0, az_g=1)

        samples = decoder.decode(garbage + valid_frame, received_at_nanos=1_000_000_000)
        self.assertEqual(len(samples), 1)
        self.assertEqual(decoder.statistics.dropped_bytes, 5)

    def test_reset_clears_all_statistics(self):
        """Verify that reset() restores initial state."""
        frame = encode_raw_frame(0, 0, 0, 0, 0, 0, 1)
        self.decoder.decode(frame * 25, received_at_nanos=1_000_000_000)
        self.assertGreater(self.decoder.statistics.decoded_frames, 0)

        self.decoder.reset()
        stats = self.decoder.statistics
        self.assertEqual(stats.decoded_frames, 0)
        self.assertEqual(stats.discarded_startup_frames, 0)
        self.assertEqual(stats.dropped_bytes, 0)


if __name__ == "__main__":
    unittest.main()
