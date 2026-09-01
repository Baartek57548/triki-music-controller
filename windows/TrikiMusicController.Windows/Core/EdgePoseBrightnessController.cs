using TrikiMusicController_Windows.Models;

namespace TrikiMusicController_Windows.Core;

public sealed record BrightnessControlResult(
    bool Active,
    bool Ready,
    float BrightnessPercent,
    float DeltaPercent,
    float StabilizationProgress,
    string StatusText);

public sealed class EdgePoseBrightnessController
{
    public const long DefaultStabilizationNanos = 400_000_000; // 400 ms
    public const float DegreesPerPercentBrightness = 3.6f; // 360 deg = 100% brightness
    public const float GyroscopeDeadbandDps = 4.0f;
    public const float MaximumZAxisDeviationG = 0.40f;
    public const float MinimumPlaneAccelerationG = 0.75f;
    public const float MaximumPlaneAccelerationG = 1.25f;

    private readonly long _stabilizationNanos;
    private long? _stabilizationStartNanos;
    private long? _lastTimestampNanos;
    private float _accumulatedDegrees;
    private float _currentBrightnessPercent = 50f;

    public EdgePoseBrightnessController(
        float initialBrightnessPercent = 50f,
        long stabilizationNanos = DefaultStabilizationNanos)
    {
        _currentBrightnessPercent = Math.Clamp(initialBrightnessPercent, 0f, 100f);
        _stabilizationNanos = stabilizationNanos;
    }

    public float CurrentBrightnessPercent
    {
        get => _currentBrightnessPercent;
        set => _currentBrightnessPercent = Math.Clamp(value, 0f, 100f);
    }

    public void Reset()
    {
        _stabilizationStartNanos = null;
        _lastTimestampNanos = null;
        _accumulatedDegrees = 0f;
    }

    public BrightnessControlResult Process(FilteredSensorData sample)
    {
        var accZ = Math.Abs(sample.AccelerometerG.Z);
        var planeAcc = MathF.Sqrt(
            sample.AccelerometerG.X * sample.AccelerometerG.X +
            sample.AccelerometerG.Y * sample.AccelerometerG.Y);

        var isEdgePose = accZ <= MaximumZAxisDeviationG &&
            planeAcc >= MinimumPlaneAccelerationG &&
            planeAcc <= MaximumPlaneAccelerationG &&
            Math.Abs(sample.AccelerationMagnitude - 1f) <= 0.25f;

        var timestamp = sample.Source.TimestampNanos;

        if (!isEdgePose)
        {
            _stabilizationStartNanos = null;
            _lastTimestampNanos = timestamp;
            _accumulatedDegrees = 0f;
            return new BrightnessControlResult(
                Active: false,
                Ready: false,
                BrightnessPercent: _currentBrightnessPercent,
                DeltaPercent: 0f,
                StabilizationProgress: 0f,
                StatusText: "Postaw kapsel na krawędzi (90°), aby regulować jasność.");
        }

        if (_stabilizationStartNanos is null)
        {
            _stabilizationStartNanos = timestamp;
            _lastTimestampNanos = timestamp;
            return new BrightnessControlResult(
                Active: true,
                Ready: false,
                BrightnessPercent: _currentBrightnessPercent,
                DeltaPercent: 0f,
                StabilizationProgress: 0f,
                StatusText: "Stabilizacja pozycji 90°…");
        }

        var elapsedStabilization = timestamp - _stabilizationStartNanos.Value;
        var stabilizationProgress = Math.Clamp((float)elapsedStabilization / _stabilizationNanos, 0f, 1f);

        if (elapsedStabilization < _stabilizationNanos)
        {
            _lastTimestampNanos = timestamp;
            return new BrightnessControlResult(
                Active: true,
                Ready: false,
                BrightnessPercent: _currentBrightnessPercent,
                DeltaPercent: 0f,
                StabilizationProgress: stabilizationProgress,
                StatusText: $"Stabilizacja: {(int)(stabilizationProgress * 100)}%");
        }

        var deltaPercent = 0f;
        if (_lastTimestampNanos is long prevTimestamp && timestamp > prevTimestamp)
        {
            var dtSeconds = (timestamp - prevTimestamp) / 1_000_000_000f;
            var gyroZ = sample.GyroscopeDps.Z;

            if (Math.Abs(gyroZ) >= GyroscopeDeadbandDps)
            {
                var deltaDegrees = gyroZ * dtSeconds;
                _accumulatedDegrees += deltaDegrees;

                if (Math.Abs(_accumulatedDegrees) >= DegreesPerPercentBrightness)
                {
                    var steps = MathF.Truncate(_accumulatedDegrees / DegreesPerPercentBrightness);
                    deltaPercent = steps;
                    _accumulatedDegrees -= steps * DegreesPerPercentBrightness;
                    _currentBrightnessPercent = Math.Clamp(_currentBrightnessPercent + deltaPercent, 0f, 100f);
                }
            }
        }

        _lastTimestampNanos = timestamp;

        return new BrightnessControlResult(
            Active: true,
            Ready: true,
            BrightnessPercent: _currentBrightnessPercent,
            DeltaPercent: deltaPercent,
            StabilizationProgress: 1f,
            StatusText: $"Jasność: {(int)_currentBrightnessPercent}% (Obracaj kapsel na krawędzi)");
    }
}
