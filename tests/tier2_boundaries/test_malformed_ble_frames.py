"""
Tier 2 Boundary Test: Malformed BLE Frames and Stream Fragmentation.

Verifies frame reassembly across split notifications, handling of zero-length payloads,
rejection of invalid packet IDs, and dropped byte accounting under stream corruption.
Strict 0-emoji compliance enforced.
"""

import unittest
from tests.helpers.imu_math import TrikiProtocolDecoder
from tests.helpers.synthetic_data import encode_raw_frame


class TestMalformedBleFrames(unittest.TestCase):

    def setUp(self):
        self.decoder = TrikiProtocolDecoder(startup_frames_to_discard=0)

    def test_zero_length_notification(self):
        """Verify decoder safely returns empty list on zero-length notification."""
        samples = self.decoder.decode(b"", 1_000_000_000)
        self.assertEqual(samples, [])

    def test_fragmented_frame_reassembly(self):
        """Verify 14-byte frame split across 6 bytes and 8 bytes is reassembled."""
        full_frame = encode_raw_frame(status=0, gx_dps=10, gy_dps=20, gz_dps=30, ax_g=0, ay_g=0, az_g=1)
        part1 = full_frame[:6]
        part2 = full_frame[6:]

        samples1 = self.decoder.decode(part1, 1_000_000_000)
        self.assertEqual(len(samples1), 0)

        samples2 = self.decoder.decode(part2, 1_020_000_000)
        self.assertEqual(len(samples2), 1)
        self.assertAlmostEqual(samples2[0].gyroscope_dps.x, 10.0, delta=0.1)
        self.assertAlmostEqual(samples2[0].gyroscope_dps.y, 20.0, delta=0.1)
        self.assertAlmostEqual(samples2[0].gyroscope_dps.z, 30.0, delta=0.1)

    def test_split_header_across_notifications(self):
        """Verify 0x22 header byte at the end of packet 1 and 0x00 status at start of packet 2."""
        full_frame = encode_raw_frame(status=3, gx_dps=5, gy_dps=0, gz_dps=0, ax_g=0, ay_g=0, az_g=1)
        # Prepend 3 bytes of garbage, then 0x22 (header) as last byte of notification 1
        notif1 = bytes([0xAA, 0xBB, 0xCC, full_frame[0]])
        # Notification 2 has rest of the frame (13 bytes)
        notif2 = full_frame[1:]

        s1 = self.decoder.decode(notif1, 1_000_000_000)
        self.assertEqual(len(s1), 0)

        s2 = self.decoder.decode(notif2, 1_020_000_000)
        self.assertEqual(len(s2), 1)
        self.assertEqual(s2[0].status, 3)
        self.assertEqual(self.decoder.statistics.dropped_bytes, 3)

    def test_invalid_packet_id_rejection(self):
        """Verify frames with status > 0x0F are rejected from standard decoding."""
        invalid_frame = bytes([0x22, 0x10]) + bytes(12)  # status 0x10 > 0x0F
        samples = self.decoder.decode(invalid_frame, 1_000_000_000)
        self.assertEqual(len(samples), 0)


if __name__ == "__main__":
    unittest.main()
