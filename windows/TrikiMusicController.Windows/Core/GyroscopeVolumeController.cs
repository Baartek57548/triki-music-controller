using TrikiMusicController_Windows.Models;

namespace TrikiMusicController_Windows.Core;

public sealed record VolumeControllerConfiguration(
    float MaximumTiltDegrees = 25f,
    long TiltStabilizationMillis = 2_000,
    float MaximumAccelerationDeviationG = 0.20f,
    float ActivationGyroscopeDps = 22f,
    float ReleaseGyroscopeDps = 12f,
    float DegreesPerVolumeStep = 22f,
    float GyroscopeSmoothingAlpha = 0.16f,
    long MinimumStepIntervalMillis = 140);

public sealed class GyroscopeVolumeController
{
    private const long MaximumSampleIntervalNanos = 100_000_000;
    private const long MaximumStreamGapNanos = 250_000_000;
    private readonly VolumeControllerConfiguration _configuration;
    private long? _previousTimestampNanos;
    private long? _tiltRangeSinceNanos;
    private float _accumulatedRotationDegrees;
    private int _activeDirection;
    private float? _smoothedGyroscopeZ;
    private long? _lastVolumeStepNanos;
    private bool _tiltStable;

    public GyroscopeVolumeController(VolumeControllerConfiguration? configuration = null)
    {
        _configuration = configuration ?? new VolumeControllerConfiguration();
        if (!float.IsFinite(_configuration.MaximumTiltDegrees) || _configuration.MaximumTiltDegrees is < 0 or > 90)
            throw new ArgumentOutOfRangeException(nameof(configuration));
        if (_configuration.TiltStabilizationMillis is < 0 or > 10_000 ||
            !float.IsFinite(_configuration.MaximumAccelerationDeviationG) ||
            _configuration.MaximumAccelerationDeviationG is < 0.05f or > 0.50f ||
            !float.IsFinite(_configuration.ActivationGyroscopeDps) || _configuration.ActivationGyroscopeDps <= 0 ||
            !float.IsFinite(_configuration.ReleaseGyroscopeDps) || _configuration.ReleaseGyroscopeDps < 0 ||
            _configuration.ReleaseGyroscopeDps > _configuration.ActivationGyroscopeDps ||
            !float.IsFinite(_configuration.DegreesPerVolumeStep) || _configuration.DegreesPerVolumeStep <= 0 ||
            !float.IsFinite(_configuration.GyroscopeSmoothingAlpha) || _configuration.GyroscopeSmoothingAlpha is < 0.01f or > 1f ||
            _configuration.MinimumStepIntervalMillis is < 0 or > 1_000)
            throw new ArgumentOutOfRangeException(nameof(configuration));
    }

    public void Reset()
    {
        _previousTimestampNanos = null;
        ResetStabilization();
    }

    public VolumeControlResult Process(FilteredSensorData sample)
    {
        var timestamp = sample.Source.TimestampNanos;
        if (_previousTimestampNanos is long previous && (timestamp <= previous || timestamp - previous > MaximumStreamGapNanos))
        {
            ResetStabilization();
            _previousTimestampNanos = null;
        }

        var accelerationMagnitude = sample.AccelerationMagnitude;
        var gyroscopeZ = sample.GyroscopeDps.Z;
        var accelerometerValid = float.IsFinite(sample.AccelerometerG.X) && float.IsFinite(sample.AccelerometerG.Y) &&
            float.IsFinite(sample.AccelerometerG.Z) && float.IsFinite(accelerationMagnitude) && accelerationMagnitude >= 0.001f;
        var sensorValid = accelerometerValid && float.IsFinite(gyroscopeZ);
        var tiltDegrees = CalculateTiltDegrees(sample.AccelerometerG.Z, accelerationMagnitude);
        var withinTiltRange = sensorValid && tiltDegrees <= _configuration.MaximumTiltDegrees + 0.001f;
        var accelerationStable = sensorValid &&
            Math.Abs(accelerationMagnitude - 1f) <= _configuration.MaximumAccelerationDeviationG + 0.0001f;
        var deltaSeconds = CalculateDeltaSeconds(timestamp);

        if (!withinTiltRange)
        {
            ResetStabilization();
            return Result(null, sensorValid, false, accelerationStable, 0, tiltDegrees, gyroscopeZ);
        }

        if (!accelerationStable)
        {
            ResetStabilization();
            return Result(null, true, true, false, 0, tiltDegrees, gyroscopeZ);
        }

        _tiltRangeSinceNanos ??= timestamp;
        var requiredNanos = _configuration.TiltStabilizationMillis * 1_000_000;
        var elapsedNanos = Math.Max(0, timestamp - _tiltRangeSinceNanos.Value);
        var progress = requiredNanos == 0 ? 1f : Math.Clamp((float)((double)elapsedNanos / requiredNanos), 0, 1);
        var wasStable = _tiltStable;
        _tiltStable = elapsedNanos >= requiredNanos;
        var filteredZ = SmoothGyroscopeZ(gyroscopeZ);
        if (!_tiltStable || !wasStable)
        {
            ResetRotation(true);
            return Result(null, true, true, true, progress, tiltDegrees, filteredZ);
        }

        var absoluteZ = Math.Abs(filteredZ);
        if (absoluteZ <= _configuration.ReleaseGyroscopeDps)
        {
            ResetRotation(true);
            return Result(null, true, true, true, 1, tiltDegrees, filteredZ);
        }

        var direction = filteredZ > 0 ? 1 : -1;
        if (_activeDirection != 0 && _activeDirection != direction) _accumulatedRotationDegrees = 0;
        if (_activeDirection == 0 && absoluteZ < _configuration.ActivationGyroscopeDps)
            return Result(null, true, true, true, 1, tiltDegrees, filteredZ);

        _activeDirection = direction;
        _accumulatedRotationDegrees = Math.Clamp(
            _accumulatedRotationDegrees + filteredZ * deltaSeconds,
            -_configuration.DegreesPerVolumeStep,
            _configuration.DegreesPerVolumeStep);
        var minimumIntervalNanos = _configuration.MinimumStepIntervalMillis * 1_000_000;
        var mayEmit = _lastVolumeStepNanos is not long last || timestamp - last >= minimumIntervalNanos;
        MediaAction? action = null;
        if (mayEmit && _accumulatedRotationDegrees >= _configuration.DegreesPerVolumeStep)
        {
            _accumulatedRotationDegrees -= _configuration.DegreesPerVolumeStep;
            _lastVolumeStepNanos = timestamp;
            action = MediaAction.VolumeUp;
        }
        else if (mayEmit && _accumulatedRotationDegrees <= -_configuration.DegreesPerVolumeStep)
        {
            _accumulatedRotationDegrees += _configuration.DegreesPerVolumeStep;
            _lastVolumeStepNanos = timestamp;
            action = MediaAction.VolumeDown;
        }
        return Result(action, true, true, true, 1, tiltDegrees, filteredZ);
    }

    private VolumeControlResult Result(
        MediaAction? action,
        bool sensorValid,
        bool withinRange,
        bool accelerationStable,
        float progress,
        float tilt,
        float gyroZ) =>
        new(action, sensorValid, withinRange, accelerationStable, _tiltStable, progress,
            sensorValid && withinRange && accelerationStable && _tiltStable,
            float.IsFinite(tilt) ? tilt : 180f, float.IsFinite(gyroZ) ? gyroZ : 0);

    private static float CalculateTiltDegrees(float accelerometerZ, float magnitude)
    {
        if (!float.IsFinite(accelerometerZ) || !float.IsFinite(magnitude) || magnitude < 0.001f) return 180;
        var faceUpComponent = Math.Clamp(-accelerometerZ / magnitude, -1, 1);
        return MathF.Acos(faceUpComponent) * 180f / MathF.PI;
    }

    private float CalculateDeltaSeconds(long timestamp)
    {
        var previous = _previousTimestampNanos;
        _previousTimestampNanos = timestamp;
        if (previous is null || timestamp <= previous.Value) return 0;
        return Math.Min(timestamp - previous.Value, MaximumSampleIntervalNanos) / 1_000_000_000f;
    }

    private float SmoothGyroscopeZ(float value)
    {
        var smoothed = _smoothedGyroscopeZ is float previous
            ? previous + _configuration.GyroscopeSmoothingAlpha * (value - previous)
            : value;
        _smoothedGyroscopeZ = smoothed;
        return smoothed;
    }

    private void ResetRotation(bool preserveSmoothing = false)
    {
        _accumulatedRotationDegrees = 0;
        _activeDirection = 0;
        _lastVolumeStepNanos = null;
        if (!preserveSmoothing) _smoothedGyroscopeZ = null;
    }

    private void ResetStabilization()
    {
        _tiltRangeSinceNanos = null;
        _tiltStable = false;
        ResetRotation();
    }
}
