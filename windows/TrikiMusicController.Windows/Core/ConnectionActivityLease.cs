using TrikiMusicController_Windows.Models;

namespace TrikiMusicController_Windows.Core;

public sealed class ConnectionActivityLease
{
    public const long DefaultIdleTimeoutNanos = 120_000_000_000;
    public const float DefaultAccelerationDeltaThresholdG = 0.08f;
    public const float DefaultGyroscopeThresholdDps = 5f;

    private readonly long _idleTimeoutNanos;
    private readonly float _accelerationDeltaThresholdG;
    private readonly float _gyroscopeThresholdDps;
    private long? _lastActivityTimestampNanos;
    private bool _parkingRequested;

    public ConnectionActivityLease(
        long idleTimeoutNanos = DefaultIdleTimeoutNanos,
        float accelerationDeltaThresholdG = DefaultAccelerationDeltaThresholdG,
        float gyroscopeThresholdDps = DefaultGyroscopeThresholdDps)
    {
        if (idleTimeoutNanos <= 0) throw new ArgumentOutOfRangeException(nameof(idleTimeoutNanos));
        if (!float.IsFinite(accelerationDeltaThresholdG) || accelerationDeltaThresholdG <= 0)
            throw new ArgumentOutOfRangeException(nameof(accelerationDeltaThresholdG));
        if (!float.IsFinite(gyroscopeThresholdDps) || gyroscopeThresholdDps <= 0)
            throw new ArgumentOutOfRangeException(nameof(gyroscopeThresholdDps));
        _idleTimeoutNanos = idleTimeoutNanos;
        _accelerationDeltaThresholdG = accelerationDeltaThresholdG;
        _gyroscopeThresholdDps = gyroscopeThresholdDps;
    }

    public bool Observe(FilteredSensorData sample, bool explicitActivity)
    {
        var sensorActivity = Math.Abs(sample.AccelerationMagnitude - 1f) >= _accelerationDeltaThresholdG ||
            sample.GyroscopeMagnitude >= _gyroscopeThresholdDps;
        return Observe(sample.Source.TimestampNanos, explicitActivity || sensorActivity);
    }

    public bool Observe(FilteredSensorData sample, bool buttonActivity, bool brightnessActivity, HoldGesturePhase gesturePhase) =>
        Observe(sample, buttonActivity || brightnessActivity ||
            gesturePhase is HoldGesturePhase.Tracking or HoldGesturePhase.Completing or HoldGesturePhase.Triggered);

    public bool Observe(long timestampNanos, bool active)
    {
        if (timestampNanos < 0)
        {
            Reset();
            return false;
        }
        if (_lastActivityTimestampNanos is not long previous || timestampNanos < previous)
        {
            _lastActivityTimestampNanos = timestampNanos;
            _parkingRequested = false;
            return false;
        }
        if (active)
        {
            _lastActivityTimestampNanos = timestampNanos;
            _parkingRequested = false;
            return false;
        }
        if (!_parkingRequested && timestampNanos - previous >= _idleTimeoutNanos)
        {
            _parkingRequested = true;
            return true;
        }
        return false;
    }

    public void Reset()
    {
        _lastActivityTimestampNanos = null;
        _parkingRequested = false;
    }
}
