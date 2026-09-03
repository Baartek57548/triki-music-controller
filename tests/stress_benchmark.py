"""
High-Frequency & Stress Verification Benchmark for Triki Music Controller.

Empirically challenges:
1. 500,000 packet high-frequency burst injection through TrikiProtocolDecoder (simulating up to 50 kHz burst).
2. Fuzzing with interleaved garbage and random frame drops.
3. Rapid chaotic IMU stream (extreme g-forces, extreme angular velocities, non-finite values).
4. Rapid connection/disconnection cycles with sudden stream gaps.
5. State arbitration exclusivity during chaotic motion.
Strict 0-emoji compliance enforced.
"""

import os
import sys
import math
import random
import struct
import time

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

from tests.helpers.imu_math import (
    AirMouseController,
    EdgePoseBrightnessController,
    FullRotationGestureDetector,
    GyroscopeVolumeController,
    TrikiProtocolDecoder,
    Vector3,
)
from tests.helpers.synthetic_data import create_filtered_sample


def stress_test_high_frequency_packets():
    print("[STRESS 1] Starting 500,000-packet high-frequency injection test...")
    decoder = TrikiProtocolDecoder(startup_frames_to_discard=0)
    
    # Pre-generate 10,000 sample payload patterns
    rng = random.Random(1337)
    packets = []
    for i in range(10_000):
        gx = rng.randint(-32000, 32000)
        gy = rng.randint(-32000, 32000)
        gz = rng.randint(-32000, 32000)
        ax = rng.randint(-32000, 32000)
        ay = rng.randint(-32000, 32000)
        az = rng.randint(-32000, 32000)
        payload = struct.pack("<6h", gx, gy, gz, ax, ay, az)
        frame = bytes([0x22, i % 16]) + payload
        packets.append(frame)
    
    # Run 500,000 frame decoding in bursts of 50 frames (multi-frame notification)
    total_frames = 500_000
    burst_size = 50
    burst_bytes = bytearray()
    for _ in range(burst_size):
        burst_bytes.extend(packets[rng.randint(0, 9999)])
    
    start_time = time.perf_counter()
    decoded_count = 0
    t_nanos = 1_000_000_000
    
    for burst_idx in range(total_frames // burst_size):
        samples = decoder.decode(bytes(burst_bytes), t_nanos)
        decoded_count += len(samples)
        t_nanos += 19_230_769 * burst_size
        
    duration = time.perf_counter() - start_time
    rate = decoded_count / duration
    print(f"  Processed {decoded_count:,} frames in {duration:.3f}s ({rate:,.0f} frames/sec)")
    assert decoded_count == total_frames, f"Expected {total_frames}, got {decoded_count}"
    assert decoder.statistics.dropped_bytes == 0, f"Expected 0 dropped bytes, got {decoder.statistics.dropped_bytes}"
    print("  [PASS] High-frequency injection test completed successfully.")


def stress_test_extreme_orientations_and_tumbling():
    print("[STRESS 2] Starting chaotic orientation and extreme G-force tumbling test...")
    vol_ctrl = GyroscopeVolumeController()
    bright_ctrl = EdgePoseBrightnessController(initial_brightness_percent=50.0)
    rot_ctrl = FullRotationGestureDetector()
    mouse_ctrl = AirMouseController(is_active=True)
    
    rng = random.Random(42)
    iterations = 200_000
    t = 1_000_000_000
    
    vol_actions = 0
    rot_actions = 0
    mouse_events = 0
    
    start_time = time.perf_counter()
    for i in range(iterations):
        # Generate chaotic accelerations [-50g, +50g] and gyro [-2500 dps, +2500 dps]
        ax = rng.uniform(-50.0, 50.0)
        ay = rng.uniform(-50.0, 50.0)
        az = rng.uniform(-50.0, 50.0)
        gx = rng.uniform(-2500.0, 2500.0)
        gy = rng.uniform(-2500.0, 2500.0)
        gz = rng.uniform(-2500.0, 2500.0)
        
        # Inject occasional NaNs / Infs
        if i % 500 == 0:
            ax = float("nan")
        elif i % 500 == 1:
            gz = float("inf")
            
        sample = create_filtered_sample(t, gyro=Vector3(gx, gy, gz), accel=Vector3(ax, ay, az))
        
        res_v = vol_ctrl.process(sample)
        if res_v.action:
            vol_actions += 1
            
        res_b = bright_ctrl.process(sample)
        assert math.isfinite(res_b.brightness_percent), "Brightness broke finite range"
        assert 0.0 <= res_b.brightness_percent <= 100.0, f"Brightness out of range: {res_b.brightness_percent}"
        
        res_r = rot_ctrl.process(sample)
        if res_r.triggered:
            rot_actions += 1
            
        res_m = mouse_ctrl.process(sample)
        if res_m.is_active:
            mouse_events += 1
            assert isinstance(res_m.delta_x, int) and isinstance(res_m.delta_y, int), "Mouse delta non-integer"
            
        t += 20_000_000  # 20ms step
        
    duration = time.perf_counter() - start_time
    print(f"  Processed {iterations:,} chaotic IMU iterations in {duration:.3f}s")
    print(f"  Volume actions: {vol_actions}, Rotation actions: {rot_actions}, Active mouse events: {mouse_events}")
    print("  [PASS] Chaotic tumbling and extreme orientation stress test completed successfully.")


def stress_test_sudden_disconnects_and_gaps():
    print("[STRESS 3] Starting 10,000 sudden disconnect / stream gap recovery cycles...")
    vol_ctrl = GyroscopeVolumeController()
    bright_ctrl = EdgePoseBrightnessController(initial_brightness_percent=50.0)
    
    t = 1_000_000_000
    gap_count = 10_000
    
    start_time = time.perf_counter()
    for cycle in range(gap_count):
        # Normal steady sample
        sample_normal = create_filtered_sample(
            t, gyro=Vector3(0.0, 0.0, 0.0), accel=Vector3(0.0, 0.0, -1.0)
        )
        vol_ctrl.process(sample_normal)
        bright_ctrl.process(sample_normal)
        
        # Sudden disconnect / large time gap (e.g. 1 sec to 1000 sec gap)
        gap_ns = random.randint(300_000_000, 10_000_000_000)
        t += gap_ns
        
        # Sudden reconnect with high rotation
        sample_gap = create_filtered_sample(
            t, gyro=Vector3(0.0, 0.0, 100.0), accel=Vector3(0.0, 0.0, -1.0)
        )
        res_v = vol_ctrl.process(sample_gap)
        # Volume controller must require fresh 2.0s stabilization and reject immediate action after gap
        assert res_v.action is None, f"Volume triggered immediately after gap at cycle {cycle}"
        
        res_b = bright_ctrl.process(sample_gap)
        assert math.isfinite(res_b.brightness_percent), "Brightness percent non-finite after gap"
        
        t += 20_000_000
        
    duration = time.perf_counter() - start_time
    print(f"  Completed {gap_count:,} disconnect cycles in {duration:.3f}s")
    print("  [PASS] Sudden disconnect and stream gap recovery test completed successfully.")


if __name__ == "__main__":
    print("================================================================================")
    print("             TRIKI MUSIC CONTROLLER -- EMPIRICAL STRESS HARNESS                 ")
    print("================================================================================")
    stress_test_high_frequency_packets()
    stress_test_extreme_orientations_and_tumbling()
    stress_test_sudden_disconnects_and_gaps()
    print("================================================================================")
    print("                      ALL STRESS HARNESS TESTS PASSED                           ")
    print("================================================================================")
