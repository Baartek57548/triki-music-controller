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
    public const long DefaultStabilizationNanos = 150_000_000; // 150 ms szybkiej stabilizacji
    public const float DegreesPerPercentBrightness = 2.5f; // 250 deg = 100% jasności
    public const float GyroscopeDeadbandDps = 3.0f;
    public const float EdgeEnterMaxZ = 0.45f;
    public const float EdgeExitMaxZ = 0.60f;
    public const float EdgeMinPlaneG = 0.65f;
    public const float EdgeMaxPlaneG = 1.35f;

    private readonly long _stabilizationNanos;
    private long? _stabilizationStartNanos;
    private long? _lastTimestampNanos;
    private float _accumulatedDegrees;
    private float _currentBrightnessPercent = 60f;
    private bool _isCurrentlyInEdge;

    public EdgePoseBrightnessController(
        float initialBrightnessPercent = 60f,
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
        _isCurrentlyInEdge = false;
    }

    public BrightnessControlResult Process(FilteredSensorData sample, bool isButtonPressed = true)
    {
        var accZ = Math.Abs(sample.AccelerometerG.Z);
        var planeAcc = MathF.Sqrt(
            sample.AccelerometerG.X * sample.AccelerometerG.X +
            sample.AccelerometerG.Y * sample.AccelerometerG.Y);

        // Histereza pozycji krawędziowej 90°
        var isEdgePose = _isCurrentlyInEdge
            ? (accZ <= EdgeExitMaxZ && planeAcc >= EdgeMinPlaneG * 0.8f)
            : (accZ <= EdgeEnterMaxZ && planeAcc >= EdgeMinPlaneG && planeAcc <= EdgeMaxPlaneG);

        _isCurrentlyInEdge = isEdgePose;
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
                StatusText: "Postaw kapsel na krawędzi (90°) i przytrzymaj przycisk, aby regulować jasność.");
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

        if (elapsedStabilization < _stabilizationNanos && !isButtonPressed)
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

        if (!isButtonPressed)
        {
            _accumulatedDegrees = 0f;
            _lastTimestampNanos = timestamp;
            return new BrightnessControlResult(
                Active: true,
                Ready: false,
                BrightnessPercent: _currentBrightnessPercent,
                DeltaPercent: 0f,
                StabilizationProgress: 1f,
                StatusText: "Przytrzymaj przycisk, aby regulować jasność w pozycji 90°.");
        }

        var deltaPercent = 0f;
        if (_lastTimestampNanos is long prevTimestamp && timestamp > prevTimestamp)
        {
            var deltaNanos = timestamp - prevTimestamp;
            var dtSeconds = (deltaNanos > 250_000_000L) ? 0.02f : (deltaNanos / 1_000_000_000f);
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
            StatusText: $"Jasność: {(int)_currentBrightnessPercent}% (Obracaj trzymając przycisk)");
    }
}
