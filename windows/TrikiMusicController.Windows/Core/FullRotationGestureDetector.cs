using TrikiMusicController_Windows.Models;

namespace TrikiMusicController_Windows.Core;

/// <summary>
/// Recognizes one deliberate 270° rotation around the inverted capsule's local Z axis.
/// </summary>
public sealed record FullRotationGestureConfiguration(
    long StabilizationMillis = 500,
    // The two low-pass stages lose roughly 20–25° at the edges of a deliberate turn. A 245°
    // integrated threshold therefore makes the physical gesture finish around the requested 270°.
    float RequiredRotationDegrees = FullRotationGestureDetector.FilteredRotationTriggerDegrees,
    float MaximumRotationDegrees = 420,
    long MaximumRotationMillis = 5_000,
    float MaximumFaceDownTiltDegrees = 25,
    float MaximumAccelerationDeviationG = 0.20f,
    float ActivationGyroscopeDps = 22,
    float ReleaseGyroscopeDps = 12,
    float GyroscopeSmoothingAlpha = 0.16f,
    long MaximumSampleGapMillis = 250,
    float ArmingMaximumAngularRateDps = 45,
    long RearmQuietMillis = 180);

public sealed class FullRotationGestureDetector
{
    public const float PhysicalRotationTargetDegrees = 270;
    public const float FilteredRotationTriggerDegrees = 245;

    private const long NanosPerMillisecond = 1_000_000;
    private const long MaxSampleIntervalNanos = 100_000_000;
    private const float NanosPerSecond = 1_000_000_000f;
    private const float MinimumUsableAccelerationG = 0.20f;
    private const float MaximumUsableAccelerationG = 2.50f;
    private readonly FullRotationGestureConfiguration _configuration;
    private long? _previousTimestampNanos;
    private long? _stabilizationSinceNanos;
    private float? _smoothedGyroscopeZ;
    private float _accumulatedRotationDegrees;
    private long? _motionStartedNanos;
    private int _activeDirection;
    private bool _awaitingQuietRearm;
    private long? _quietRearmSinceNanos;
    private bool _triggered;
    private bool _faceDown;

    public FullRotationGestureDetector(FullRotationGestureConfiguration? configuration = null)
    {
        _configuration = configuration ?? new FullRotationGestureConfiguration();
        ValidateConfiguration(_configuration);
    }

    public void Reset()
    {
        _previousTimestampNanos = null;
        _stabilizationSinceNanos = null;
        _smoothedGyroscopeZ = null;
        _accumulatedRotationDegrees = 0;
        _motionStartedNanos = null;
        _activeDirection = 0;
        _awaitingQuietRearm = false;
        _quietRearmSinceNanos = null;
        _triggered = false;
        _faceDown = false;
    }

    public FullRotationGestureResult Process(FilteredSensorData sample)
    {
        var timestamp = sample.Source.TimestampNanos;
        var acceleration = sample.AccelerometerG;
        if (!IsUsableAcceleration(acceleration))
        {
            RestartStabilization(timestamp, null);
            return Result(HoldGesturePhase.Holding, 0);
        }
        _faceDown = IsFaceDown(acceleration);

        var previousTimestamp = _previousTimestampNanos;
        if (previousTimestamp is long previous &&
            (timestamp <= previous || timestamp - previous > _configuration.MaximumSampleGapMillis * NanosPerMillisecond))
        {
            RestartStabilization(timestamp, acceleration);
            return Result(HoldGesturePhase.Holding, 0);
        }
        _previousTimestampNanos = timestamp;

        var stabilizationStart = _stabilizationSinceNanos;
        if (stabilizationStart is null)
        {
            _stabilizationSinceNanos = timestamp;
            return Result(HoldGesturePhase.Holding, 0);
        }

        if (!_faceDown || Math.Abs(sample.AccelerationMagnitude - 1f) > _configuration.MaximumAccelerationDeviationG)
        {
            RestartStabilization(timestamp, acceleration);
            return Result(HoldGesturePhase.Holding, 0);
        }

        var stabilizationElapsed = Math.Max(0, timestamp - stabilizationStart.Value);
        var requiredStabilization = _configuration.StabilizationMillis * NanosPerMillisecond;
        var stabilizationProgress = requiredStabilization == 0
            ? 1f
            : Math.Clamp((float)((double)stabilizationElapsed / requiredStabilization), 0, 1);
        if (stabilizationElapsed < requiredStabilization)
        {
            ResetRotation();
            return Result(HoldGesturePhase.Holding, stabilizationProgress);
        }

        var gyroscopeZ = SmoothGyroscopeZ(sample.GyroscopeDps.Z);
        if (_awaitingQuietRearm)
        {
            if (Math.Abs(gyroscopeZ) <= _configuration.ReleaseGyroscopeDps &&
                sample.GyroscopeMagnitude <= _configuration.ArmingMaximumAngularRateDps)
            {
                _quietRearmSinceNanos ??= timestamp;
                if (timestamp - _quietRearmSinceNanos.Value >= _configuration.RearmQuietMillis * NanosPerMillisecond)
                    RestartStabilization(timestamp, acceleration);
            }
            else
            {
                _quietRearmSinceNanos = null;
            }
            return Result(HoldGesturePhase.Rearming, 1, gyroscopeZ);
        }

        if (!float.IsFinite(gyroscopeZ))
        {
            InvalidateMotion(timestamp, acceleration);
            return Result(HoldGesturePhase.Rearming, 1);
        }
        if (Math.Abs(gyroscopeZ) <= _configuration.ReleaseGyroscopeDps)
        {
            ResetRotation(preserveSmoothing: true);
            return Result(HoldGesturePhase.Ready, 1, gyroscopeZ);
        }

        var direction = gyroscopeZ > 0 ? 1 : -1;
        if (_activeDirection != 0 && _activeDirection != direction)
            ResetRotation(preserveSmoothing: true);
        if (_activeDirection == 0 && Math.Abs(gyroscopeZ) < _configuration.ActivationGyroscopeDps)
            return Result(HoldGesturePhase.Ready, 1, gyroscopeZ);

        _activeDirection = direction;
        _motionStartedNanos ??= timestamp;
        var motionElapsed = timestamp - _motionStartedNanos.Value;
        if (motionElapsed > _configuration.MaximumRotationMillis * NanosPerMillisecond)
        {
            InvalidateMotion(timestamp, acceleration);
            return Result(HoldGesturePhase.Rearming, 1, gyroscopeZ);
        }

        var deltaSeconds = previousTimestamp is null || timestamp <= previousTimestamp.Value
            ? 0
            : Math.Min(timestamp - previousTimestamp.Value, MaxSampleIntervalNanos) / NanosPerSecond;
        var nextRotation = _accumulatedRotationDegrees + gyroscopeZ * deltaSeconds;
        if (Math.Abs(nextRotation) > _configuration.MaximumRotationDegrees)
        {
            InvalidateMotion(timestamp, acceleration);
            return Result(HoldGesturePhase.Rearming, 1, gyroscopeZ);
        }
        _accumulatedRotationDegrees = nextRotation;

        var progress = Math.Clamp(Math.Abs(_accumulatedRotationDegrees) / _configuration.RequiredRotationDegrees, 0, 1);
        if (Math.Abs(_accumulatedRotationDegrees) >= _configuration.RequiredRotationDegrees)
        {
            _triggered = true;
            _awaitingQuietRearm = true;
            _quietRearmSinceNanos = null;
            return Result(
                HoldGesturePhase.Triggered,
                1,
                gyroscopeZ,
                _accumulatedRotationDegrees > 0 ? RotationGestureDirection.Right : RotationGestureDirection.Left);
        }

        return Result(HoldGesturePhase.Tracking, progress, gyroscopeZ);
    }

    private FullRotationGestureResult Result(
        HoldGesturePhase phase,
        float stabilizationProgress,
        float gyroscopeZ = 0,
        RotationGestureDirection? direction = null) => new(
        _triggered && phase == HoldGesturePhase.Triggered,
        direction ?? (_activeDirection == 0
            ? null
            : _activeDirection > 0 ? RotationGestureDirection.Right : RotationGestureDirection.Left),
        phase,
        stabilizationProgress,
        _faceDown,
        _accumulatedRotationDegrees,
        float.IsFinite(gyroscopeZ) ? gyroscopeZ : 0);

    private void ResetRotation(bool preserveSmoothing = false)
    {
        _accumulatedRotationDegrees = 0;
        _motionStartedNanos = null;
        _activeDirection = 0;
        if (!preserveSmoothing) _smoothedGyroscopeZ = null;
    }

    private void RestartStabilization(long timestamp, Vector3f? acceleration)
    {
        _previousTimestampNanos = timestamp;
        _stabilizationSinceNanos = timestamp;
        _faceDown = acceleration is Vector3f value && IsFaceDown(value);
        _awaitingQuietRearm = false;
        _quietRearmSinceNanos = null;
        _triggered = false;
        ResetRotation();
    }

    private void InvalidateMotion(long timestamp, Vector3f acceleration)
    {
        _previousTimestampNanos = timestamp;
        _stabilizationSinceNanos = timestamp;
        _faceDown = IsFaceDown(acceleration);
        _awaitingQuietRearm = true;
        _quietRearmSinceNanos = null;
        _triggered = false;
        ResetRotation(preserveSmoothing: true);
    }

    private float SmoothGyroscopeZ(float value)
    {
        var smoothed = _smoothedGyroscopeZ is float previous
            ? previous + _configuration.GyroscopeSmoothingAlpha * (value - previous)
            : value;
        _smoothedGyroscopeZ = smoothed;
        return smoothed;
    }

    private bool IsFaceDown(Vector3f value)
    {
        var magnitude = value.Magnitude;
        if (!float.IsFinite(magnitude) || magnitude < MinimumUsableAccelerationG) return false;
        var zComponent = Math.Clamp(value.Z / magnitude, -1, 1);
        return zComponent >= MathF.Cos(_configuration.MaximumFaceDownTiltDegrees * MathF.PI / 180f);
    }

    private static bool IsUsableAcceleration(Vector3f value) =>
        float.IsFinite(value.X) && float.IsFinite(value.Y) && float.IsFinite(value.Z) &&
        value.Magnitude is >= MinimumUsableAccelerationG and <= MaximumUsableAccelerationG;

    private static void ValidateConfiguration(FullRotationGestureConfiguration value)
    {
        if (value.StabilizationMillis is < 200 or > 3_000 ||
            !float.IsFinite(value.RequiredRotationDegrees) || value.RequiredRotationDegrees is < 180 or > 360 ||
            !float.IsFinite(value.MaximumRotationDegrees) || value.MaximumRotationDegrees <= value.RequiredRotationDegrees ||
            value.MaximumRotationMillis is < 1_000 or > 10_000 ||
            !float.IsFinite(value.MaximumFaceDownTiltDegrees) || value.MaximumFaceDownTiltDegrees is < 5 or > 45 ||
            !float.IsFinite(value.MaximumAccelerationDeviationG) || value.MaximumAccelerationDeviationG is < 0.05f or > 0.50f ||
            !float.IsFinite(value.ActivationGyroscopeDps) || value.ActivationGyroscopeDps <= 0 ||
            !float.IsFinite(value.ReleaseGyroscopeDps) || value.ReleaseGyroscopeDps < 0 || value.ReleaseGyroscopeDps > value.ActivationGyroscopeDps ||
            !float.IsFinite(value.GyroscopeSmoothingAlpha) || value.GyroscopeSmoothingAlpha is < 0.01f or > 1 ||
            value.MaximumSampleGapMillis is < 100 or > 1_000 ||
            !float.IsFinite(value.ArmingMaximumAngularRateDps) || value.ArmingMaximumAngularRateDps is < 10 or > 120 ||
            value.RearmQuietMillis is < 80 or > 500)
            throw new ArgumentOutOfRangeException(nameof(value));
    }
}
