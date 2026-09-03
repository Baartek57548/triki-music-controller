"""
Authoritative IMU Mathematics and Protocol Models for Triki Music Controller.

This module implements mathematically exact reference models corresponding to the
C# (.NET 10) and Kotlin (Jetpack Compose) core implementations according to ADR-002.
Strict 0-emoji compliance enforced.
"""

from __future__ import annotations
import math
import struct
from dataclasses import dataclass
from enum import Enum
from typing import List, Optional, Tuple


class MediaAction(Enum):
    PLAY_PAUSE = "PlayPause"
    NEXT_TRACK = "NextTrack"
    PREVIOUS_TRACK = "PreviousTrack"
    VOLUME_UP = "VolumeUp"
    VOLUME_DOWN = "VolumeDown"
    MUTE = "Mute"


class ButtonClickType(Enum):
    SINGLE = "Single"
    DOUBLE = "Double"
    TRIPLE = "Triple"


class ButtonProtocolMode(Enum):
    UNKNOWN = "Unknown"
    BUTTON_FLAG = "ButtonFlag"
    SEQUENCE_COUNTER = "SequenceCounter"


class MultiDeviceArbitrationMode(Enum):
    ALWAYS_CONNECT = "AlwaysConnect"
    ONLY_WHEN_PLAYING = "OnlyWhenPlaying"
    MEDIA_PRIORITY = "MediaPriority"


class HoldGesturePhase(Enum):
    HOLDING = "Holding"
    READY = "Ready"
    TRACKING = "Tracking"
    TRIGGERED = "Triggered"
    REARMING = "Rearming"


class RotationGestureDirection(Enum):
    LEFT = "Left"
    RIGHT = "Right"


@dataclass(frozen=True)
class Vector3:
    x: float
    y: float
    z: float

    @property
    def magnitude(self) -> float:
        return math.sqrt(self.x * self.x + self.y * self.y + self.z * self.z)

    @property
    def is_finite(self) -> bool:
        return math.isfinite(self.x) and math.isfinite(self.y) and math.isfinite(self.z)

    def dot(self, other: Vector3) -> float:
        return self.x * other.x + self.y * other.y + self.z * other.z

    def normalized(self) -> Vector3:
        mag = self.magnitude
        if not math.isfinite(mag) or mag < 1e-9:
            return Vector3(0.0, 0.0, 0.0)
        return Vector3(self.x / mag, self.y / mag, self.z / mag)


@dataclass(frozen=True)
class RawVector3:
    x: int
    y: int
    z: int


@dataclass(frozen=True)
class TrikiSensorData:
    frame_index: int
    timestamp_nanos: int
    gyroscope_dps: Vector3
    accelerometer_g: Vector3
    raw_gyroscope: RawVector3
    raw_accelerometer: RawVector3
    status: int


@dataclass(frozen=True)
class FilteredSensorData:
    source: TrikiSensorData
    gyroscope_dps: Vector3
    accelerometer_g: Vector3

    @property
    def acceleration_magnitude(self) -> float:
        return self.accelerometer_g.magnitude

    @property
    def gyroscope_magnitude(self) -> float:
        return self.gyroscope_dps.magnitude


# ---------------------------------------------------------------------------
# BLE Protocol Decoder Reference
# ---------------------------------------------------------------------------

class TrikiProtocol:
    NUS_SERVICE_UUID = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
    NUS_RX_UUID = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
    NUS_TX_UUID = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"
    LED_UUID = "6e400004-b5a3-f393-e0a9-e50e24dcca9e"

    START_STREAM_COMMAND = bytes([0x20, 0x10, 0x00, 0xD0, 0x07, 0x34, 0x00, 0x03])

    FRAME_LENGTH = 14
    FRAME_HEADER = 0x22
    # LSM6DS Gyro: 70 mdps/LSB -> 1 / 0.070 = 14.285714 LSB/dps
    GYROSCOPE_LSB_PER_DPS = 1.0 / 0.070
    # LSM6DS Accel: 2048 LSB/g (0.488 mg/LSB)
    ACCELEROMETER_LSB_PER_G = 2048.0


@dataclass(frozen=True)
class DecoderStatistics:
    decoded_frames: int
    discarded_startup_frames: int
    dropped_bytes: int


class TrikiProtocolDecoder:
    APPROXIMATE_SAMPLE_PERIOD_NANOS = 19_230_769
    MIN_MONOTONIC_STEP_NANOS = 1_000

    def __init__(
        self,
        startup_frames_to_discard: int = 20,
        gyroscope_scale: float = TrikiProtocol.GYROSCOPE_LSB_PER_DPS,
        accelerometer_scale: float = TrikiProtocol.ACCELEROMETER_LSB_PER_G,
    ):
        if startup_frames_to_discard < 0:
            raise ValueError("startup_frames_to_discard must be non-negative")
        if not math.isfinite(gyroscope_scale) or gyroscope_scale <= 0:
            raise ValueError("gyroscope_scale must be positive and finite")
        if not math.isfinite(accelerometer_scale) or accelerometer_scale <= 0:
            raise ValueError("accelerometer_scale must be positive and finite")

        self._startup_frames_to_discard = startup_frames_to_discard
        self._gyroscope_scale = gyroscope_scale
        self._accelerometer_scale = accelerometer_scale
        self._buffer = bytearray()
        self._frame_index = 0
        self._discarded = 0
        self._dropped = 0
        self._last_timestamp_nanos: Optional[int] = None

    @property
    def statistics(self) -> DecoderStatistics:
        return DecoderStatistics(self._frame_index, self._discarded, self._dropped)

    def reset(self) -> None:
        self._buffer.clear()
        self._frame_index = 0
        self._discarded = 0
        self._dropped = 0
        self._last_timestamp_nanos = None

    def decode(self, notification: bytes, received_at_nanos: int) -> List[TrikiSensorData]:
        if not notification:
            return []

        self._buffer.extend(notification)
        frames: List[bytes] = []

        while True:
            header_index = self._find_header()
            if header_index < 0:
                self._retain_possible_split_header()
                break

            if header_index > 0:
                self._dropped += header_index
                del self._buffer[:header_index]

            if len(self._buffer) < TrikiProtocol.FRAME_LENGTH:
                break

            frame = bytes(self._buffer[:TrikiProtocol.FRAME_LENGTH])
            del self._buffer[:TrikiProtocol.FRAME_LENGTH]
            frames.append(frame)

        if not frames:
            return []

        result: List[TrikiSensorData] = []
        first_timestamp = received_at_nanos - (len(frames) - 1) * self.APPROXIMATE_SAMPLE_PERIOD_NANOS

        for index, frame in enumerate(frames):
            if self._discarded < self._startup_frames_to_discard:
                self._discarded += 1
                continue

            interpolated = first_timestamp + index * self.APPROXIMATE_SAMPLE_PERIOD_NANOS
            if self._last_timestamp_nanos is None:
                timestamp = interpolated
            else:
                timestamp = max(interpolated, self._last_timestamp_nanos + self.MIN_MONOTONIC_STEP_NANOS)

            self._last_timestamp_nanos = timestamp
            result.append(self._decode_frame(frame, timestamp, self._frame_index))
            self._frame_index += 1

        return result

    def _decode_frame(self, frame: bytes, timestamp_nanos: int, index: int) -> TrikiSensorData:
        if len(frame) != TrikiProtocol.FRAME_LENGTH or frame[0] != TrikiProtocol.FRAME_HEADER or frame[1] > 0x0F:
            raise ValueError("Invalid Triki protocol frame.")

        status = frame[1]
        gx, gy, gz, ax, ay, az = struct.unpack("<6h", frame[2:14])
        return TrikiSensorData(
            frame_index=index,
            timestamp_nanos=timestamp_nanos,
            gyroscope_dps=Vector3(gx / self._gyroscope_scale, gy / self._gyroscope_scale, gz / self._gyroscope_scale),
            accelerometer_g=Vector3(ax / self._accelerometer_scale, ay / self._accelerometer_scale, az / self._accelerometer_scale),
            raw_gyroscope=RawVector3(gx, gy, gz),
            raw_accelerometer=RawVector3(ax, ay, az),
            status=status,
        )

    def _find_header(self) -> int:
        for idx in range(len(self._buffer) - 1):
            if self._buffer[idx] == TrikiProtocol.FRAME_HEADER and self._buffer[idx + 1] <= 0x0F:
                return idx
        return -1

    def _retain_possible_split_header(self) -> None:
        if not self._buffer:
            return
        keep_last = self._buffer[-1] == TrikiProtocol.FRAME_HEADER
        removed = len(self._buffer) - (1 if keep_last else 0)
        self._dropped += removed
        last = self._buffer[-1] if keep_last else None
        self._buffer.clear()
        if keep_last and last is not None:
            self._buffer.append(last)


# ---------------------------------------------------------------------------
# Edge Pose Brightness Controller Reference (ADR-002 / v3.1.5 Parity)
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class BrightnessControlResult:
    active: bool
    ready: bool
    brightness_percent: float
    delta_percent: float
    stabilization_progress: float
    status_text: str


class EdgePoseBrightnessController:
    DEFAULT_STABILIZATION_NANOS = 150_000_000  # 150 ms
    DEGREES_PER_PERCENT_BRIGHTNESS = 2.5       # 250 deg = 100%
    GYROSCOPE_DEADBAND_DPS = 3.0
    EDGE_ENTER_MAX_Z = 0.45
    EDGE_EXIT_MAX_Z = 0.60
    EDGE_MIN_PLANE_G = 0.65
    EDGE_MAX_PLANE_G = 1.35

    def __init__(
        self,
        initial_brightness_percent: float = 60.0,
        stabilization_nanos: int = DEFAULT_STABILIZATION_NANOS,
    ):
        self._current_brightness_percent = max(0.0, min(100.0, initial_brightness_percent))
        self._stabilization_nanos = stabilization_nanos
        self._stabilization_start_nanos: Optional[int] = None
        self._last_timestamp_nanos: Optional[int] = None
        self._accumulated_degrees = 0.0
        self._is_currently_in_edge = False

    @property
    def current_brightness_percent(self) -> float:
        return self._current_brightness_percent

    @current_brightness_percent.setter
    def current_brightness_percent(self, value: float) -> None:
        self._current_brightness_percent = max(0.0, min(100.0, value))

    def reset(self) -> None:
        self._stabilization_start_nanos = None
        self._last_timestamp_nanos = None
        self._accumulated_degrees = 0.0
        self._is_currently_in_edge = False

    def process(self, sample: FilteredSensorData, is_button_pressed: bool = True) -> BrightnessControlResult:
        acc_z = abs(sample.accelerometer_g.z)
        plane_acc = math.sqrt(sample.accelerometer_g.x ** 2 + sample.accelerometer_g.y ** 2)

        # Hysteresis for 90 deg edge pose
        if self._is_currently_in_edge:
            is_edge_pose = (acc_z <= self.EDGE_EXIT_MAX_Z) and (plane_acc >= self.EDGE_MIN_PLANE_G * 0.8)
        else:
            is_edge_pose = (acc_z <= self.EDGE_ENTER_MAX_Z) and (self.EDGE_MIN_PLANE_G <= plane_acc <= self.EDGE_MAX_PLANE_G)

        self._is_currently_in_edge = is_edge_pose
        timestamp = sample.source.timestamp_nanos

        if not is_edge_pose:
            self._stabilization_start_nanos = None
            self._last_timestamp_nanos = timestamp
            self._accumulated_degrees = 0.0
            return BrightnessControlResult(
                active=False,
                ready=False,
                brightness_percent=self._current_brightness_percent,
                delta_percent=0.0,
                stabilization_progress=0.0,
                status_text="Postaw kapsel na krawedzi (90 deg) i przytrzymaj przycisk, aby regulowac jasnosc.",
            )

        if self._stabilization_start_nanos is None:
            self._stabilization_start_nanos = timestamp
            self._last_timestamp_nanos = timestamp
            return BrightnessControlResult(
                active=True,
                ready=False,
                brightness_percent=self._current_brightness_percent,
                delta_percent=0.0,
                stabilization_progress=0.0,
                status_text="Stabilizacja pozycji 90 deg...",
            )

        elapsed_stabilization = timestamp - self._stabilization_start_nanos
        stabilization_progress = max(0.0, min(1.0, elapsed_stabilization / self._stabilization_nanos))

        if elapsed_stabilization < self._stabilization_nanos and not is_button_pressed:
            self._last_timestamp_nanos = timestamp
            return BrightnessControlResult(
                active=True,
                ready=False,
                brightness_percent=self._current_brightness_percent,
                delta_percent=0.0,
                stabilization_progress=stabilization_progress,
                status_text=f"Stabilizacja: {int(stabilization_progress * 100)}%",
            )

        if not is_button_pressed:
            self._accumulated_degrees = 0.0
            self._last_timestamp_nanos = timestamp
            return BrightnessControlResult(
                active=True,
                ready=False,
                brightness_percent=self._current_brightness_percent,
                delta_percent=0.0,
                stabilization_progress=1.0,
                status_text="Przytrzymaj przycisk, aby regulowac jasnosc w pozycji 90 deg.",
            )

        delta_percent = 0.0
        if self._last_timestamp_nanos is not None and timestamp > self._last_timestamp_nanos:
            delta_nanos = timestamp - self._last_timestamp_nanos
            dt_seconds = 0.02 if delta_nanos > 250_000_000 else (delta_nanos / 1_000_000_000.0)
            gyro_z = sample.gyroscope_dps.z

            if abs(gyro_z) >= self.GYROSCOPE_DEADBAND_DPS:
                delta_degrees = gyro_z * dt_seconds
                self._accumulated_degrees += delta_degrees

                if abs(self._accumulated_degrees) >= self.DEGREES_PER_PERCENT_BRIGHTNESS:
                    steps = math.trunc(self._accumulated_degrees / self.DEGREES_PER_PERCENT_BRIGHTNESS)
                    delta_percent = float(steps)
                    self._accumulated_degrees -= steps * self.DEGREES_PER_PERCENT_BRIGHTNESS
                    self._current_brightness_percent = max(0.0, min(100.0, self._current_brightness_percent + delta_percent))

        self._last_timestamp_nanos = timestamp
        return BrightnessControlResult(
            active=True,
            ready=True,
            brightness_percent=self._current_brightness_percent,
            delta_percent=delta_percent,
            stabilization_progress=1.0,
            status_text=f"Jasnosc: {int(self._current_brightness_percent)}% (Obracaj trzymajac przycisk)",
        )


# ---------------------------------------------------------------------------
# Gyroscope Volume Controller Reference (ADR-002 Parity)
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class VolumeControllerConfiguration:
    maximum_tilt_degrees: float = 25.0
    tilt_stabilization_millis: int = 2_000
    maximum_acceleration_deviation_g: float = 0.20
    activation_gyroscope_dps: float = 22.0
    release_gyroscope_dps: float = 12.0
    degrees_per_volume_step: float = 22.0
    gyroscope_smoothing_alpha: float = 0.16
    minimum_step_interval_millis: int = 140


@dataclass(frozen=True)
class VolumeControlResult:
    action: Optional[MediaAction]
    sensor_valid: bool
    within_tilt_range: bool
    acceleration_stable: bool
    tilt_stable: bool
    stabilization_progress: float
    active: bool
    tilt_degrees: float
    gyroscope_z_dps: float


class GyroscopeVolumeController:
    MAX_SAMPLE_INTERVAL_NANOS = 100_000_000
    MAX_STREAM_GAP_NANOS = 250_000_000

    def __init__(self, config: Optional[VolumeControllerConfiguration] = None):
        self.config = config or VolumeControllerConfiguration()
        self._previous_timestamp_nanos: Optional[int] = None
        self._tilt_range_since_nanos: Optional[int] = None
        self._accumulated_rotation_degrees = 0.0
        self._active_direction = 0
        self._smoothed_gyroscope_z: Optional[float] = None
        self._last_volume_step_nanos: Optional[int] = None
        self._tilt_stable = False

    def reset(self) -> None:
        self._previous_timestamp_nanos = None
        self._reset_stabilization()

    def process(self, sample: FilteredSensorData) -> VolumeControlResult:
        timestamp = sample.source.timestamp_nanos
        if self._previous_timestamp_nanos is not None and (
            timestamp <= self._previous_timestamp_nanos or timestamp - self._previous_timestamp_nanos > self.MAX_STREAM_GAP_NANOS
        ):
            self._reset_stabilization()
            self._previous_timestamp_nanos = None

        acc_mag = sample.acceleration_magnitude
        gyro_z = sample.gyroscope_dps.z
        acc_valid = (
            math.isfinite(sample.accelerometer_g.x)
            and math.isfinite(sample.accelerometer_g.y)
            and math.isfinite(sample.accelerometer_g.z)
            and math.isfinite(acc_mag)
            and acc_mag >= 0.001
        )
        sensor_valid = acc_valid and math.isfinite(gyro_z)
        tilt_degrees = self._calculate_tilt_degrees(sample.accelerometer_g.z, acc_mag)
        within_tilt_range = sensor_valid and (tilt_degrees <= self.config.maximum_tilt_degrees + 0.001)
        acc_stable = sensor_valid and (abs(acc_mag - 1.0) <= self.config.maximum_acceleration_deviation_g + 0.0001)
        delta_seconds = self._calculate_delta_seconds(timestamp)

        if not within_tilt_range:
            self._reset_stabilization()
            return self._result(None, sensor_valid, False, acc_stable, 0.0, tilt_degrees, gyro_z)

        if not acc_stable:
            self._reset_stabilization()
            return self._result(None, True, True, False, 0.0, tilt_degrees, gyro_z)

        if self._tilt_range_since_nanos is None:
            self._tilt_range_since_nanos = timestamp

        required_nanos = self.config.tilt_stabilization_millis * 1_000_000
        elapsed_nanos = max(0, timestamp - self._tilt_range_since_nanos)
        progress = 1.0 if required_nanos == 0 else max(0.0, min(1.0, elapsed_nanos / required_nanos))
        was_stable = self._tilt_stable
        self._tilt_stable = elapsed_nanos >= required_nanos
        filtered_z = self._smooth_gyroscope_z(gyro_z)

        if not self._tilt_stable or not was_stable:
            self._reset_rotation(preserve_smoothing=True)
            return self._result(None, True, True, True, progress, tilt_degrees, filtered_z)

        abs_z = abs(filtered_z)
        if abs_z <= self.config.release_gyroscope_dps:
            self._reset_rotation(preserve_smoothing=True)
            return self._result(None, True, True, True, 1.0, tilt_degrees, filtered_z)

        direction = 1 if filtered_z > 0 else -1
        if self._active_direction != 0 and self._active_direction != direction:
            self._accumulated_rotation_degrees = 0.0
        if self._active_direction == 0 and abs_z < self.config.activation_gyroscope_dps:
            return self._result(None, True, True, True, 1.0, tilt_degrees, filtered_z)

        self._active_direction = direction
        self._accumulated_rotation_degrees = max(
            -self.config.degrees_per_volume_step,
            min(self.config.degrees_per_volume_step, self._accumulated_rotation_degrees + filtered_z * delta_seconds),
        )

        minimum_interval_nanos = self.config.minimum_step_interval_millis * 1_000_000
        may_emit = (self._last_volume_step_nanos is None) or (timestamp - self._last_volume_step_nanos >= minimum_interval_nanos)
        action: Optional[MediaAction] = None

        if may_emit and self._accumulated_rotation_degrees >= self.config.degrees_per_volume_step:
            self._accumulated_rotation_degrees -= self.config.degrees_per_volume_step
            self._last_volume_step_nanos = timestamp
            action = MediaAction.VOLUME_UP
        elif may_emit and self._accumulated_rotation_degrees <= -self.config.degrees_per_volume_step:
            self._accumulated_rotation_degrees += self.config.degrees_per_volume_step
            self._last_volume_step_nanos = timestamp
            action = MediaAction.VOLUME_DOWN

        return self._result(action, True, True, True, 1.0, tilt_degrees, filtered_z)

    def _result(
        self,
        action: Optional[MediaAction],
        sensor_valid: bool,
        within_range: bool,
        acc_stable: bool,
        progress: float,
        tilt: float,
        gyro_z: float,
    ) -> VolumeControlResult:
        is_active = sensor_valid and within_range and acc_stable and self._tilt_stable
        return VolumeControlResult(
            action=action,
            sensor_valid=sensor_valid,
            within_tilt_range=within_range,
            acceleration_stable=acc_stable,
            tilt_stable=self._tilt_stable,
            stabilization_progress=progress,
            active=is_active,
            tilt_degrees=tilt if math.isfinite(tilt) else 180.0,
            gyroscope_z_dps=gyro_z if math.isfinite(gyro_z) else 0.0,
        )

    def _calculate_tilt_degrees(self, acc_z: float, magnitude: float) -> float:
        if not math.isfinite(acc_z) or not math.isfinite(magnitude) or magnitude < 0.001:
            return 180.0
        face_up = max(-1.0, min(1.0, -acc_z / magnitude))
        return math.acos(face_up) * 180.0 / math.pi

    def _calculate_delta_seconds(self, timestamp: int) -> float:
        prev = self._previous_timestamp_nanos
        self._previous_timestamp_nanos = timestamp
        if prev is None or timestamp <= prev:
            return 0.0
        return min(timestamp - prev, self.MAX_SAMPLE_INTERVAL_NANOS) / 1_000_000_000.0

    def _smooth_gyroscope_z(self, value: float) -> float:
        if self._smoothed_gyroscope_z is None:
            smoothed = value
        else:
            smoothed = self._smoothed_gyroscope_z + self.config.gyroscope_smoothing_alpha * (value - self._smoothed_gyroscope_z)
        self._smoothed_gyroscope_z = smoothed
        return smoothed

    def _reset_rotation(self, preserve_smoothing: bool = False) -> None:
        self._accumulated_rotation_degrees = 0.0
        self._active_direction = 0
        self._last_volume_step_nanos = None
        if not preserve_smoothing:
            self._smoothed_gyroscope_z = None

    def _reset_stabilization(self) -> None:
        self._tilt_range_since_nanos = None
        self._tilt_stable = False
        self._reset_rotation()


# ---------------------------------------------------------------------------
# Full Rotation Gesture Detector Reference (Face-Down Track Skip)
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class FullRotationGestureResult:
    triggered: bool
    direction: Optional[RotationGestureDirection]
    phase: HoldGesturePhase
    stabilization_progress: float
    face_down: bool
    accumulated_rotation_degrees: float
    gyroscope_z_dps: float


class FullRotationGestureDetector:
    PHYSICAL_ROTATION_TARGET_DEGREES = 200.0
    FILTERED_ROTATION_TRIGGER_DEGREES = 181.0

    def __init__(
        self,
        stabilization_millis: int = 500,
        required_rotation_degrees: float = 181.0,
        maximum_rotation_degrees: float = 340.0,
        maximum_face_down_tilt_degrees: float = 25.0,
        maximum_acc_deviation_g: float = 0.20,
        activation_gyroscope_dps: float = 22.0,
        release_gyroscope_dps: float = 12.0,
        gyroscope_smoothing_alpha: float = 0.16,
    ):
        self.stabilization_millis = stabilization_millis
        self.required_rotation_degrees = required_rotation_degrees
        self.maximum_rotation_degrees = maximum_rotation_degrees
        self.maximum_face_down_tilt_degrees = maximum_face_down_tilt_degrees
        self.maximum_acc_deviation_g = maximum_acc_deviation_g
        self.activation_gyroscope_dps = activation_gyroscope_dps
        self.release_gyroscope_dps = release_gyroscope_dps
        self.gyroscope_smoothing_alpha = gyroscope_smoothing_alpha

        self._previous_timestamp_nanos: Optional[int] = None
        self._stabilization_since_nanos: Optional[int] = None
        self._smoothed_gyroscope_z: Optional[float] = None
        self._accumulated_rotation_degrees = 0.0
        self._motion_started_nanos: Optional[int] = None
        self._active_direction = 0
        self._awaiting_quiet_rearm = False
        self._quiet_rearm_since_nanos: Optional[int] = None
        self._triggered = False
        self._face_down = False

    def reset(self) -> None:
        self._previous_timestamp_nanos = None
        self._stabilization_since_nanos = None
        self._smoothed_gyroscope_z = None
        self._accumulated_rotation_degrees = 0.0
        self._motion_started_nanos = None
        self._active_direction = 0
        self._awaiting_quiet_rearm = False
        self._quiet_rearm_since_nanos = None
        self._triggered = False
        self._face_down = False

    def process(self, sample: FilteredSensorData) -> FullRotationGestureResult:
        timestamp = sample.source.timestamp_nanos
        acc = sample.accelerometer_g

        if not (acc.is_finite and 0.20 <= acc.magnitude <= 2.50):
            self._restart_stabilization(timestamp, None)
            return self._result(HoldGesturePhase.HOLDING, 0.0)

        self._face_down = self._is_face_down(acc)

        if self._previous_timestamp_nanos is not None:
            gap_nanos = timestamp - self._previous_timestamp_nanos
            if timestamp <= self._previous_timestamp_nanos or gap_nanos > 250_000_000:
                self._restart_stabilization(timestamp, acc)
                return self._result(HoldGesturePhase.HOLDING, 0.0)

        self._previous_timestamp_nanos = timestamp

        if self._stabilization_since_nanos is None:
            self._stabilization_since_nanos = timestamp
            return self._result(HoldGesturePhase.HOLDING, 0.0)

        if not self._face_down or abs(sample.acceleration_magnitude - 1.0) > self.maximum_acc_deviation_g:
            self._restart_stabilization(timestamp, acc)
            return self._result(HoldGesturePhase.HOLDING, 0.0)

        stabilization_elapsed = max(0, timestamp - self._stabilization_since_nanos)
        required_nanos = self.stabilization_millis * 1_000_000
        progress = 1.0 if required_nanos == 0 else max(0.0, min(1.0, stabilization_elapsed / required_nanos))

        if stabilization_elapsed < required_nanos:
            self._reset_rotation()
            return self._result(HoldGesturePhase.HOLDING, progress)

        gyro_z = self._smooth_gyroscope_z(sample.gyroscope_dps.z)

        if self._awaiting_quiet_rearm:
            if abs(gyro_z) <= self.release_gyroscope_dps and sample.gyroscope_magnitude <= 45.0:
                if self._quiet_rearm_since_nanos is None:
                    self._quiet_rearm_since_nanos = timestamp
                if timestamp - self._quiet_rearm_since_nanos >= 180_000_000:
                    self._restart_stabilization(timestamp, acc)
            else:
                self._quiet_rearm_since_nanos = None
            return self._result(HoldGesturePhase.REARMING, 1.0, gyro_z)

        if not math.isfinite(gyro_z):
            self._invalidate_motion(timestamp, acc)
            return self._result(HoldGesturePhase.REARMING, 1.0)

        if abs(gyro_z) <= self.release_gyroscope_dps:
            self._reset_rotation(preserve_smoothing=True)
            return self._result(HoldGesturePhase.READY, 1.0, gyro_z)

        direction = 1 if gyro_z > 0 else -1
        if self._active_direction != 0 and self._active_direction != direction:
            self._reset_rotation(preserve_smoothing=True)

        if self._active_direction == 0 and abs(gyro_z) < self.activation_gyroscope_dps:
            return self._result(HoldGesturePhase.READY, 1.0, gyro_z)

        self._active_direction = direction
        if self._motion_started_nanos is None:
            self._motion_started_nanos = timestamp

        if timestamp - self._motion_started_nanos > 5_000_000_000:
            self._invalidate_motion(timestamp, acc)
            return self._result(HoldGesturePhase.REARMING, 1.0, gyro_z)

        delta_seconds = 0.02
        next_rotation = self._accumulated_rotation_degrees + gyro_z * delta_seconds
        if abs(next_rotation) > self.maximum_rotation_degrees:
            self._invalidate_motion(timestamp, acc)
            return self._result(HoldGesturePhase.REARMING, 1.0, gyro_z)

        self._accumulated_rotation_degrees = next_rotation
        rot_progress = max(0.0, min(1.0, abs(self._accumulated_rotation_degrees) / self.required_rotation_degrees))

        if abs(self._accumulated_rotation_degrees) >= self.required_rotation_degrees:
            self._triggered = True
            self._awaiting_quiet_rearm = True
            self._quiet_rearm_since_nanos = None
            direction_enum = (
                RotationGestureDirection.RIGHT if self._accumulated_rotation_degrees > 0 else RotationGestureDirection.LEFT
            )
            return self._result(HoldGesturePhase.TRIGGERED, 1.0, gyro_z, direction_enum)

        return self._result(HoldGesturePhase.TRACKING, rot_progress, gyro_z)

    def _result(
        self,
        phase: HoldGesturePhase,
        stabilization_progress: float,
        gyroscope_z: float = 0.0,
        direction: Optional[RotationGestureDirection] = None,
    ) -> FullRotationGestureResult:
        dir_val = direction
        if dir_val is None and self._active_direction != 0:
            dir_val = RotationGestureDirection.RIGHT if self._active_direction > 0 else RotationGestureDirection.LEFT

        return FullRotationGestureResult(
            triggered=self._triggered and phase == HoldGesturePhase.TRIGGERED,
            direction=dir_val,
            phase=phase,
            stabilization_progress=stabilization_progress,
            face_down=self._face_down,
            accumulated_rotation_degrees=self._accumulated_rotation_degrees,
            gyroscope_z_dps=gyroscope_z if math.isfinite(gyroscope_z) else 0.0,
        )

    def _reset_rotation(self, preserve_smoothing: bool = False) -> None:
        self._accumulated_rotation_degrees = 0.0
        self._motion_started_nanos = None
        self._active_direction = 0
        if not preserve_smoothing:
            self._smoothed_gyroscope_z = None

    def _restart_stabilization(self, timestamp: int, acc: Optional[Vector3]) -> None:
        self._previous_timestamp_nanos = timestamp
        self._stabilization_since_nanos = timestamp
        self._face_down = acc is not None and self._is_face_down(acc)
        self._awaiting_quiet_rearm = False
        self._quiet_rearm_since_nanos = None
        self._triggered = False
        self._reset_rotation()

    def _invalidate_motion(self, timestamp: int, acc: Vector3) -> None:
        self._previous_timestamp_nanos = timestamp
        self._stabilization_since_nanos = timestamp
        self._face_down = self._is_face_down(acc)
        self._awaiting_quiet_rearm = True
        self._quiet_rearm_since_nanos = None
        self._triggered = False
        self._reset_rotation(preserve_smoothing=True)

    def _smooth_gyroscope_z(self, value: float) -> float:
        if self._smoothed_gyroscope_z is None:
            smoothed = value
        else:
            smoothed = self._smoothed_gyroscope_z + self.gyroscope_smoothing_alpha * (value - self._smoothed_gyroscope_z)
        self._smoothed_gyroscope_z = smoothed
        return smoothed

    def _is_face_down(self, value: Vector3) -> bool:
        mag = value.magnitude
        if not math.isfinite(mag) or mag < 0.20:
            return False
        z_comp = max(-1.0, min(1.0, value.z / mag))
        return z_comp >= math.cos(self.maximum_face_down_tilt_degrees * math.pi / 180.0)


# ---------------------------------------------------------------------------
# Air Mouse Controller Reference
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class AirMouseOutput:
    is_active: bool
    is_scroll_mode: bool
    delta_x: int
    delta_y: int
    scroll_delta: int


class AirMouseController:
    EDGE_ENTER_LATERAL_ACC_Y = 0.70
    EDGE_ENTER_MAX_ABS_Z = 0.35
    EDGE_EXIT_LATERAL_ACC_Y = 0.55
    EDGE_EXIT_MAX_ABS_Z = 0.50
    TABLE_REST_MAX_Z = -0.65
    GYRO_DEADBAND_DPS = 3.0
    SCROLL_THRESHOLD_DEGREES = 8.0
    CLICK_SUPPRESSION_DURATION_NANOS = 90_000_000

    def __init__(self, is_active: bool = True):
        self.is_active = is_active
        self.is_scroll_mode = False
        self._last_timestamp_nanos: Optional[int] = None
        self._accumulated_scroll_angle = 0.0
        self._smoothed_delta_x = 0.0
        self._smoothed_delta_y = 0.0
        self._subpixel_x = 0.0
        self._subpixel_y = 0.0
        self._suppress_motion_until_nanos = 0

    def reset(self) -> None:
        self._last_timestamp_nanos = None
        self._accumulated_scroll_angle = 0.0
        self._smoothed_delta_x = 0.0
        self._smoothed_delta_y = 0.0
        self._subpixel_x = 0.0
        self._subpixel_y = 0.0
        self._suppress_motion_until_nanos = 0
        self.is_scroll_mode = False

    def notify_click_transient(self, now: int) -> None:
        self._suppress_motion_until_nanos = max(self._suppress_motion_until_nanos, now + self.CLICK_SUPPRESSION_DURATION_NANOS)
        self._smoothed_delta_x = 0.0
        self._smoothed_delta_y = 0.0
        self._subpixel_x = 0.0
        self._subpixel_y = 0.0

    def process(self, sample: FilteredSensorData) -> AirMouseOutput:
        if not self.is_active:
            self.reset()
            return AirMouseOutput(is_active=False, is_scroll_mode=False, delta_x=0, delta_y=0, scroll_delta=0)

        if not (sample.gyroscope_dps.is_finite and sample.accelerometer_g.is_finite):
            return AirMouseOutput(is_active=True, is_scroll_mode=False, delta_x=0, delta_y=0, scroll_delta=0)

        now = sample.source.timestamp_nanos
        dt = 0.02
        if self._last_timestamp_nanos is not None:
            delta_nanos = now - self._last_timestamp_nanos
            if 0 < delta_nanos <= 250_000_000:
                dt = delta_nanos / 1_000_000_000.0
            else:
                self._smoothed_delta_x = 0.0
                self._smoothed_delta_y = 0.0
                self._subpixel_x = 0.0
                self._subpixel_y = 0.0
                self._accumulated_scroll_angle = 0.0

        self._last_timestamp_nanos = now
        acc_y = sample.accelerometer_g.y
        acc_z = sample.accelerometer_g.z

        # Resting on desk
        if acc_z <= self.TABLE_REST_MAX_Z:
            self._smoothed_delta_x = 0.0
            self._smoothed_delta_y = 0.0
            self._subpixel_x = 0.0
            self._subpixel_y = 0.0
            self._accumulated_scroll_angle = 0.0
            self.is_scroll_mode = False
            return AirMouseOutput(is_active=True, is_scroll_mode=False, delta_x=0, delta_y=0, scroll_delta=0)

        # Scroll mode with hysteresis
        if self.is_scroll_mode:
            should_scroll = (abs(acc_y) >= self.EDGE_EXIT_LATERAL_ACC_Y) and (abs(acc_z) <= self.EDGE_EXIT_MAX_ABS_Z)
        else:
            should_scroll = (abs(acc_y) >= self.EDGE_ENTER_LATERAL_ACC_Y) and (abs(acc_z) <= self.EDGE_ENTER_MAX_ABS_Z)

        if should_scroll:
            self.is_scroll_mode = True
            self._smoothed_delta_x = 0.0
            self._smoothed_delta_y = 0.0
            self._subpixel_x = 0.0
            self._subpixel_y = 0.0

            raw_gyro_z = sample.gyroscope_dps.z
            if abs(raw_gyro_z) >= self.GYRO_DEADBAND_DPS:
                self._accumulated_scroll_angle += raw_gyro_z * dt

            scroll_steps = 0
            if self._accumulated_scroll_angle >= self.SCROLL_THRESHOLD_DEGREES:
                steps = int(self._accumulated_scroll_angle / self.SCROLL_THRESHOLD_DEGREES)
                scroll_steps = -steps
                self._accumulated_scroll_angle -= steps * self.SCROLL_THRESHOLD_DEGREES
            elif self._accumulated_scroll_angle <= -self.SCROLL_THRESHOLD_DEGREES:
                steps = int(-self._accumulated_scroll_angle / self.SCROLL_THRESHOLD_DEGREES)
                scroll_steps = steps
                self._accumulated_scroll_angle += steps * self.SCROLL_THRESHOLD_DEGREES

            return AirMouseOutput(is_active=True, is_scroll_mode=True, delta_x=0, delta_y=0, scroll_delta=scroll_steps)

        # Free-air pointer mode
        self.is_scroll_mode = False
        self._accumulated_scroll_angle = 0.0

        if now < self._suppress_motion_until_nanos:
            return AirMouseOutput(is_active=True, is_scroll_mode=False, delta_x=0, delta_y=0, scroll_delta=0)

        raw_yaw = -sample.gyroscope_dps.z
        raw_pitch = sample.gyroscope_dps.x

        dead_yaw = self._apply_soft_deadband(raw_yaw, self.GYRO_DEADBAND_DPS)
        dead_pitch = self._apply_soft_deadband(raw_pitch, self.GYRO_DEADBAND_DPS)

        accel_x = self._apply_velocity_curve(dead_yaw)
        accel_y = self._apply_velocity_curve(dead_pitch) * 1.15

        speed_mag = math.sqrt(dead_yaw * dead_yaw + dead_pitch * dead_pitch)
        alpha = max(0.50, min(0.85, 0.50 + (speed_mag / 60.0) * 0.30))

        self._smoothed_delta_x = self._smoothed_delta_x * (1.0 - alpha) + accel_x * alpha
        self._smoothed_delta_y = self._smoothed_delta_y * (1.0 - alpha) + accel_y * alpha

        self._subpixel_x += self._smoothed_delta_x
        self._subpixel_y += self._smoothed_delta_y

        step_x = int(math.trunc(self._subpixel_x))
        step_y = int(math.trunc(self._subpixel_y))

        self._subpixel_x -= step_x
        self._subpixel_y -= step_y

        return AirMouseOutput(is_active=True, is_scroll_mode=False, delta_x=step_x, delta_y=step_y, scroll_delta=0)

    def _apply_soft_deadband(self, value: float, deadband: float) -> float:
        abs_val = abs(value)
        if abs_val <= deadband:
            return 0.0
        excess = abs_val - deadband
        return math.copysign(excess, value)

    def _apply_velocity_curve(self, angular_velocity_dps: float) -> float:
        abs_val = abs(angular_velocity_dps)
        if abs_val <= 0.0:
            return 0.0
        speed = abs_val * 0.20 + (abs_val * abs_val) * 0.0030
        return math.copysign(speed, angular_velocity_dps)


# ---------------------------------------------------------------------------
# Button Interpreter Reference
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class ButtonClickEvent:
    click_type: ButtonClickType
    timestamp_nanos: int


class TrikiButtonInterpreter:
    MIN_OBSERVATIONS = 12
    MIN_REPEATED_RUN = 4
    DEBOUNCE_NANOS = 18_000_000
    MIN_CLICK_PRESS_NANOS = 25_000_000
    MAX_CLICK_PRESS_NANOS = 2_000_000_000
    MULTI_CLICK_TIMEOUT_NANOS = 450_000_000
    MAX_STREAM_GAP_NANOS = 300_000_000

    def __init__(self):
        self.protocol_mode = ButtonProtocolMode.UNKNOWN
        self._last_timestamp_nanos: Optional[int] = None
        self._observed_status: Optional[int] = None
        self._observed_run_length = 0
        self._longest_observed_run = 0
        self._observation_count = 0
        self._stable_pressed = False
        self._candidate_pressed = False
        self._candidate_since_nanos = 0
        self._pressed_at_nanos: Optional[int] = None
        self._pending_click_count = 0
        self._click_deadline_nanos: Optional[int] = None
        self._current_hold_consumed = False

    @property
    def is_pressed(self) -> bool:
        return self.protocol_mode == ButtonProtocolMode.BUTTON_FLAG and self._stable_pressed

    def consume_current_hold(self) -> bool:
        if not self.is_pressed:
            return False
        self._current_hold_consumed = True
        self._pending_click_count = 0
        self._click_deadline_nanos = None
        return True

    def check_and_consume_hold_duration(self, now: int, required_duration_nanos: int) -> bool:
        if not self.is_pressed or self._current_hold_consumed or self._pressed_at_nanos is None:
            return False
        if now - self._pressed_at_nanos >= required_duration_nanos:
            self.consume_current_hold()
            return True
        return False

    def reset(self) -> None:
        self.protocol_mode = ButtonProtocolMode.UNKNOWN
        self._last_timestamp_nanos = None
        self._observed_status = None
        self._observed_run_length = 0
        self._longest_observed_run = 0
        self._observation_count = 0
        self._clear_interaction()

    def process(self, sample: TrikiSensorData) -> Optional[ButtonClickEvent]:
        now = sample.timestamp_nanos
        if self._last_timestamp_nanos is not None and (
            now <= self._last_timestamp_nanos or now - self._last_timestamp_nanos > self.MAX_STREAM_GAP_NANOS
        ):
            self.reset()
        self._last_timestamp_nanos = now

        if self.protocol_mode == ButtonProtocolMode.UNKNOWN:
            self._observe_protocol(sample.status, now)
            return None

        if self.protocol_mode == ButtonProtocolMode.SEQUENCE_COUNTER:
            return None

        if sample.status not in (0, 1):
            self.protocol_mode = ButtonProtocolMode.SEQUENCE_COUNTER
            self._clear_interaction()
            return None

        return self._process_button_state(sample.status == 1, now)

    def _observe_protocol(self, status: int, now: int) -> None:
        if status not in (0, 1):
            self.protocol_mode = ButtonProtocolMode.SEQUENCE_COUNTER
            self._clear_interaction()
            return

        self._observation_count += 1
        if self._observed_status == status:
            self._observed_run_length += 1
        else:
            self._observed_status = status
            self._observed_run_length = 1

        self._longest_observed_run = max(self._longest_observed_run, self._observed_run_length)
        if self._observation_count < self.MIN_OBSERVATIONS or self._longest_observed_run < self.MIN_REPEATED_RUN:
            return

        self.protocol_mode = ButtonProtocolMode.BUTTON_FLAG
        self._stable_pressed = status == 1
        self._candidate_pressed = self._stable_pressed
        self._candidate_since_nanos = now
        self._pressed_at_nanos = now if self._stable_pressed else None
        self._pending_click_count = 0
        self._click_deadline_nanos = None
        self._current_hold_consumed = False

    def _process_button_state(self, raw_pressed: bool, now: int) -> Optional[ButtonClickEvent]:
        completed = self._finalize_expired_sequence(raw_pressed, now)
        if raw_pressed != self._candidate_pressed:
            self._candidate_pressed = raw_pressed
            self._candidate_since_nanos = now

        if self._candidate_pressed == self._stable_pressed or (now - self._candidate_since_nanos < self.DEBOUNCE_NANOS):
            return completed

        self._stable_pressed = self._candidate_pressed
        if self._stable_pressed:
            self._pressed_at_nanos = now
            self._current_hold_consumed = False
        else:
            if completed is None:
                completed = self._register_release(now)

        return completed

    def _finalize_expired_sequence(self, raw_pressed: bool, now: int) -> Optional[ButtonClickEvent]:
        if self._click_deadline_nanos is not None and now >= self._click_deadline_nanos and not self._stable_pressed and not raw_pressed:
            return self._complete_pending_sequence(now)
        return None

    def _register_release(self, now: int) -> Optional[ButtonClickEvent]:
        pressed_at = self._pressed_at_nanos
        self._pressed_at_nanos = None
        if pressed_at is None:
            return None
        if self._current_hold_consumed:
            self._current_hold_consumed = False
            self._pending_click_count = 0
            self._click_deadline_nanos = None
            return None

        duration = now - pressed_at
        if duration < self.MIN_CLICK_PRESS_NANOS or duration > self.MAX_CLICK_PRESS_NANOS:
            self._pending_click_count = 0
            self._click_deadline_nanos = None
            return None

        self._pending_click_count += 1
        if self._pending_click_count >= 3:
            return self._complete_pending_sequence(now)

        self._click_deadline_nanos = now + self.MULTI_CLICK_TIMEOUT_NANOS
        return None

    def _complete_pending_sequence(self, now: int) -> Optional[ButtonClickEvent]:
        type_map = {
            1: ButtonClickType.SINGLE,
            2: ButtonClickType.DOUBLE,
            3: ButtonClickType.TRIPLE,
        }
        click_type = type_map.get(self._pending_click_count)
        self._pending_click_count = 0
        self._click_deadline_nanos = None
        self._current_hold_consumed = False
        return ButtonClickEvent(click_type, now) if click_type else None

    def _clear_interaction(self) -> None:
        self._stable_pressed = False
        self._candidate_pressed = False
        self._candidate_since_nanos = 0
        self._pressed_at_nanos = None
        self._pending_click_count = 0
        self._click_deadline_nanos = None
        self._current_hold_consumed = False


# ---------------------------------------------------------------------------
# Multi-Device Arbitration Policy Reference
# ---------------------------------------------------------------------------

class MultiDeviceArbitrationPolicy:
    RECENT_PLAYBACK_WINDOW_SECONDS = 180.0  # 3 minutes
    IDLE_PLAYBACK_WINDOW_SECONDS = 600.0    # 10 minutes

    ACTIVE_PLAYBACK_DELAY_MS = 0
    FOREGROUND_USER_DELAY_MS = 100
    RECENT_PLAYBACK_DELAY_MS = 200
    STALE_PLAYBACK_DELAY_MS = 500
    IDLE_YIELD_DELAY_MS = 1000

    @classmethod
    def should_attempt_connection(
        cls,
        mode: MultiDeviceArbitrationMode,
        is_media_playing: bool,
        is_user_active: bool,
    ) -> bool:
        if mode == MultiDeviceArbitrationMode.ONLY_WHEN_PLAYING:
            return is_media_playing or is_user_active
        return True

    @classmethod
    def calculate_connection_delay_ms(
        cls,
        mode: MultiDeviceArbitrationMode,
        is_media_playing: bool,
        last_playback_time_seconds_ago: Optional[float],
        is_user_active: bool,
    ) -> int:
        if mode in (MultiDeviceArbitrationMode.ALWAYS_CONNECT, MultiDeviceArbitrationMode.ONLY_WHEN_PLAYING):
            return 0

        # MediaPriority mode:
        if is_media_playing:
            return cls.ACTIVE_PLAYBACK_DELAY_MS
        if is_user_active:
            return cls.FOREGROUND_USER_DELAY_MS

        if last_playback_time_seconds_ago is not None:
            elapsed = max(0.0, last_playback_time_seconds_ago)
            if elapsed <= cls.RECENT_PLAYBACK_WINDOW_SECONDS:
                return cls.RECENT_PLAYBACK_DELAY_MS
            if elapsed <= cls.IDLE_PLAYBACK_WINDOW_SECONDS:
                return cls.STALE_PLAYBACK_DELAY_MS

        return cls.IDLE_YIELD_DELAY_MS

    @classmethod
    def should_yield_connection(
        cls,
        mode: MultiDeviceArbitrationMode,
        is_media_playing: bool,
        connected_duration_without_media_seconds: float,
    ) -> bool:
        if is_media_playing:
            return False
        if mode in (MultiDeviceArbitrationMode.MEDIA_PRIORITY, MultiDeviceArbitrationMode.ONLY_WHEN_PLAYING):
            return connected_duration_without_media_seconds >= 10.0
        return False


# ---------------------------------------------------------------------------
# Wake Advertisement Gate Reference
# ---------------------------------------------------------------------------

class WakeAdvertisementGate:
    DEFAULT_REQUIRED_SILENCE_NANOS = 5_000_000_000  # 5 seconds

    def __init__(self, required_silence_nanos: int = DEFAULT_REQUIRED_SILENCE_NANOS):
        if required_silence_nanos <= 0:
            raise ValueError("required_silence_nanos must be positive")
        self._required_silence_nanos = required_silence_nanos
        self._last_advertisement_nanos: Optional[int] = None
        self._armed = False

    @property
    def is_armed(self) -> bool:
        return self._armed

    def reset(self, now_nanos: int) -> None:
        self._last_advertisement_nanos = now_nanos if now_nanos >= 0 else None
        self._armed = False

    def observe_advertisement(self, now_nanos: int) -> bool:
        if now_nanos < 0 or self._last_advertisement_nanos is None or now_nanos < self._last_advertisement_nanos:
            self._last_advertisement_nanos = now_nanos if now_nanos >= 0 else None
            self._armed = False
            return False

        may_connect = self._armed or (now_nanos - self._last_advertisement_nanos >= self._required_silence_nanos)
        self._last_advertisement_nanos = now_nanos
        return may_connect

    def try_arm(self, now_nanos: int) -> bool:
        if self._last_advertisement_nanos is None:
            return False
        if now_nanos < self._last_advertisement_nanos:
            self._last_advertisement_nanos = now_nanos if now_nanos >= 0 else None
            self._armed = False
            return False
        if self._armed or (now_nanos - self._last_advertisement_nanos < self._required_silence_nanos):
            return False
        self._armed = True
        return True
