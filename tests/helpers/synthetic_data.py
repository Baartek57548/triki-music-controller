"""
Synthetic Sensor Data and BLE Packet Generator for Triki Music Controller E2E Tests.

Enforces strict 0-emoji compliance.
"""

from __future__ import annotations
import math
import struct
from typing import List, Tuple
from tests.helpers.imu_math import (
    FilteredSensorData,
    RawVector3,
    TrikiProtocol,
    TrikiSensorData,
    Vector3,
)


def encode_raw_frame(
    status: int,
    gx_dps: float,
    gy_dps: float,
    gz_dps: float,
    ax_g: float,
    ay_g: float,
    az_g: float,
    gyro_scale: float = TrikiProtocol.GYROSCOPE_LSB_PER_DPS,
    acc_scale: float = TrikiProtocol.ACCELEROMETER_LSB_PER_G,
) -> bytes:
    """Encode engineering unit sensor values into a 14-byte BLE NUS frame."""
    def clamp_short(val: float) -> int:
        if not math.isfinite(val):
            return 0
        return max(-32768, min(32767, int(round(val))))

    raw_gx = clamp_short(gx_dps * gyro_scale)
    raw_gy = clamp_short(gy_dps * gyro_scale)
    raw_gz = clamp_short(gz_dps * gyro_scale)
    raw_ax = clamp_short(ax_g * acc_scale)
    raw_ay = clamp_short(ay_g * acc_scale)
    raw_az = clamp_short(az_g * acc_scale)

    payload = struct.pack("<6h", raw_gx, raw_gy, raw_gz, raw_ax, raw_ay, raw_az)
    return bytes([TrikiProtocol.FRAME_HEADER, status & 0xFF]) + payload


def create_filtered_sample(
    timestamp_nanos: int,
    gyro: Vector3,
    accel: Vector3,
    status: int = 0,
    frame_index: int = 0,
) -> FilteredSensorData:
    """Helper to create FilteredSensorData directly for algorithm testing."""
    def safe_int16(val: float, scale: float) -> int:
        if not math.isfinite(val):
            return 0
        return max(-32768, min(32767, int(round(val * scale))))

    raw_gyro = RawVector3(
        safe_int16(gyro.x, TrikiProtocol.GYROSCOPE_LSB_PER_DPS),
        safe_int16(gyro.y, TrikiProtocol.GYROSCOPE_LSB_PER_DPS),
        safe_int16(gyro.z, TrikiProtocol.GYROSCOPE_LSB_PER_DPS),
    )
    raw_accel = RawVector3(
        safe_int16(accel.x, TrikiProtocol.ACCELEROMETER_LSB_PER_G),
        safe_int16(accel.y, TrikiProtocol.ACCELEROMETER_LSB_PER_G),
        safe_int16(accel.z, TrikiProtocol.ACCELEROMETER_LSB_PER_G),
    )
    source = TrikiSensorData(
        frame_index=frame_index,
        timestamp_nanos=timestamp_nanos,
        gyroscope_dps=gyro,
        accelerometer_g=accel,
        raw_gyroscope=raw_gyro,
        raw_accelerometer=raw_accel,
        status=status,
    )
    return FilteredSensorData(source=source, gyroscope_dps=gyro, accelerometer_g=accel)


def generate_volume_rotation_samples(
    start_time_nanos: int,
    sample_count: int,
    rotation_dps: float,
    tilt_degrees: float = 0.0,
    sample_period_nanos: int = 20_000_000,
) -> List[FilteredSensorData]:
    """Generates synthetic samples for upright capsule volume rotation."""
    rad = math.radians(tilt_degrees)
    acc_x = math.sin(rad)
    acc_y = 0.0
    acc_z = -math.cos(rad)

    samples = []
    for i in range(sample_count):
        t = start_time_nanos + i * sample_period_nanos
        samples.append(
            create_filtered_sample(
                timestamp_nanos=t,
                gyro=Vector3(0.0, 0.0, rotation_dps),
                accel=Vector3(acc_x, acc_y, acc_z),
                frame_index=i,
            )
        )
    return samples


def generate_face_down_rotation_samples(
    start_time_nanos: int,
    sample_count: int,
    rotation_dps: float,
    tilt_degrees: float = 0.0,
    sample_period_nanos: int = 20_000_000,
) -> List[FilteredSensorData]:
    """Generates synthetic samples for inverted face-down capsule full rotation."""
    rad = math.radians(tilt_degrees)
    acc_x = math.sin(rad)
    acc_y = 0.0
    acc_z = math.cos(rad)

    samples = []
    for i in range(sample_count):
        t = start_time_nanos + i * sample_period_nanos
        samples.append(
            create_filtered_sample(
                timestamp_nanos=t,
                gyro=Vector3(0.0, 0.0, rotation_dps),
                accel=Vector3(acc_x, acc_y, acc_z),
                frame_index=i,
            )
        )
    return samples


def generate_edge_brightness_samples(
    start_time_nanos: int,
    sample_count: int,
    rotation_dps: float,
    acc_z: float = 0.0,
    plane_g: float = 1.0,
    sample_period_nanos: int = 20_000_000,
) -> List[FilteredSensorData]:
    """Generates synthetic samples for 90-degree edge pose brightness adjustment."""
    samples = []
    for i in range(sample_count):
        t = start_time_nanos + i * sample_period_nanos
        samples.append(
            create_filtered_sample(
                timestamp_nanos=t,
                gyro=Vector3(0.0, 0.0, rotation_dps),
                accel=Vector3(0.0, plane_g, acc_z),
                frame_index=i,
            )
        )
    return samples
