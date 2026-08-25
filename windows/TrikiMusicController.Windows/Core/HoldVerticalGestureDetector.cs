using TrikiMusicController_Windows.Models;

namespace TrikiMusicController_Windows.Core;

public sealed record HoldGestureConfiguration(
    long HoldMillis = 500,
    float TriggerDisplacementMeters = 0.20f,
    float MotionStartAccelerationG = 0.12f,
    float AccelerationDeadZoneG = 0.06f,
    long MaximumMotionMillis = 1_800,
    float LinearAccelerationSmoothingAlpha = 0.35f);

public sealed class HoldVerticalGestureDetector
{
    private const long MaximumSampleGapNanos = 150_000_000;
    private const float StandardGravity = 9.80665f;
    private readonly HoldGestureConfiguration _configuration;
    private long? _pressedSinceNanos;
    private long? _previousTimestampNanos;
    private Vector3f? _gravityBaseline;
    private long? _motionStartedNanos;
    private float _filteredLinearAccelerationG;
    private float _verticalVelocityMetersPerSecond;
    private float _displacementMeters;
    private bool _triggered;

    public HoldVerticalGestureDetector(HoldGestureConfiguration? configuration = null)
    {
        _configuration = configuration ?? new HoldGestureConfiguration();
        if (_configuration.HoldMillis is < 200 or > 3_000 ||
            !float.IsFinite(_configuration.TriggerDisplacementMeters) || _configuration.TriggerDisplacementMeters is < 0.10f or > 0.50f ||
            !float.IsFinite(_configuration.MotionStartAccelerationG) || _configuration.MotionStartAccelerationG is < 0.05f or > 1f ||
            !float.IsFinite(_configuration.AccelerationDeadZoneG) || _configuration.AccelerationDeadZoneG < 0.01f ||
            _configuration.AccelerationDeadZoneG > _configuration.MotionStartAccelerationG ||
            _configuration.MaximumMotionMillis is < 500 or > 4_000 ||
            !float.IsFinite(_configuration.LinearAccelerationSmoothingAlpha) || _configuration.LinearAccelerationSmoothingAlpha is < 0.05f or > 1f)
            throw new ArgumentOutOfRangeException(nameof(configuration));
    }

    public void Reset()
    {
        _pressedSinceNanos = null;
        _previousTimestampNanos = null;
        _gravityBaseline = null;
        ResetMotion();
        _triggered = false;
    }

    public HoldVerticalGestureResult Process(FilteredSensorData sample, bool buttonPressed)
    {
        if (!buttonPressed)
        {
            Reset();
            return Result(HoldGesturePhase.Idle, 0);
        }

        var timestamp = sample.Source.TimestampNanos;
        var acceleration = sample.AccelerometerG;
        if (!IsUsableAcceleration(acceleration))
        {
            ResetMotion();
            _previousTimestampNanos = timestamp;
            return Result(HoldGesturePhase.Holding, 0);
        }

        if (_pressedSinceNanos is null)
        {
            _pressedSinceNanos = timestamp;
            _previousTimestampNanos = timestamp;
            _gravityBaseline = acceleration;
            return Result(HoldGesturePhase.Holding, 0);
        }

        var pressStart = _pressedSinceNanos.Value;
        if (_previousTimestampNanos is not long previous || timestamp <= previous || timestamp - previous > MaximumSampleGapNanos)
        {
            _pressedSinceNanos = timestamp;
            _previousTimestampNanos = timestamp;
            _gravityBaseline = acceleration;
            ResetMotion();
            _triggered = false;
            return Result(HoldGesturePhase.Holding, 0);
        }
        var deltaSeconds = (timestamp - previous) / 1_000_000_000f;
        _previousTimestampNanos = timestamp;
        if (_triggered) return Result(HoldGesturePhase.Triggered, 1);

        var holdNanos = _configuration.HoldMillis * 1_000_000;
        var heldNanos = Math.Max(0, timestamp - pressStart);
        var holdProgress = Math.Clamp((float)((double)heldNanos / holdNanos), 0, 1);
        if (heldNanos < holdNanos)
        {
            _gravityBaseline = LowPass(_gravityBaseline, acceleration, 0.12f);
            ResetMotion();
            return Result(HoldGesturePhase.Holding, holdProgress);
        }

        var baseline = _gravityBaseline ?? acceleration;
        var baselineMagnitude = baseline.Magnitude;
        if (baselineMagnitude is < 0.65f or > 1.35f)
        {
            _gravityBaseline = acceleration;
            ResetMotion();
            return Result(HoldGesturePhase.Holding, 0);
        }
        var gravityUnit = new Vector3f(baseline.X / baselineMagnitude, baseline.Y / baselineMagnitude, baseline.Z / baselineMagnitude);
        var rawLinearAccelerationG = Dot(acceleration, gravityUnit) - baselineMagnitude;
        _filteredLinearAccelerationG += _configuration.LinearAccelerationSmoothingAlpha *
            (rawLinearAccelerationG - _filteredLinearAccelerationG);

        if (_motionStartedNanos is null)
        {
            if (Math.Abs(_filteredLinearAccelerationG) < _configuration.MotionStartAccelerationG)
            {
                _gravityBaseline = LowPass(_gravityBaseline, acceleration, 0.025f);
                ResetMotion();
                return Result(HoldGesturePhase.Ready, 1);
            }
            _motionStartedNanos = timestamp;
            _verticalVelocityMetersPerSecond = 0;
            _displacementMeters = 0;
        }

        if (timestamp - _motionStartedNanos.Value > _configuration.MaximumMotionMillis * 1_000_000)
        {
            _gravityBaseline = acceleration;
            ResetMotion();
            return Result(HoldGesturePhase.Ready, 1);
        }

        var effectiveAccelerationG = Math.Abs(_filteredLinearAccelerationG) < _configuration.AccelerationDeadZoneG
            ? 0
            : _filteredLinearAccelerationG;
        var accelerationMetersPerSecondSquared = effectiveAccelerationG * StandardGravity;
        _displacementMeters += _verticalVelocityMetersPerSecond * deltaSeconds +
            0.5f * accelerationMetersPerSecondSquared * deltaSeconds * deltaSeconds;
        _verticalVelocityMetersPerSecond = Math.Clamp(
            _verticalVelocityMetersPerSecond + accelerationMetersPerSecondSquared * deltaSeconds, -2, 2);
        if (effectiveAccelerationG == 0) _verticalVelocityMetersPerSecond *= 0.92f;
        _displacementMeters = Math.Clamp(_displacementMeters, -0.60f, 0.60f);

        RatingGestureAction? action = null;
        // Physical Triki captures establish this sign convention: lift is negative, lowering positive.
        if (_displacementMeters <= -_configuration.TriggerDisplacementMeters) action = RatingGestureAction.Like;
        else if (_displacementMeters >= _configuration.TriggerDisplacementMeters) action = RatingGestureAction.Dislike;
        if (action is not null) _triggered = true;
        return Result(_triggered ? HoldGesturePhase.Triggered : HoldGesturePhase.Tracking, 1, action);
    }

    private HoldVerticalGestureResult Result(HoldGesturePhase phase, float progress, RatingGestureAction? action = null) =>
        new(action, phase, progress, _displacementMeters);

    private void ResetMotion()
    {
        _motionStartedNanos = null;
        _filteredLinearAccelerationG = 0;
        _verticalVelocityMetersPerSecond = 0;
        _displacementMeters = 0;
    }

    private static bool IsUsableAcceleration(Vector3f value) =>
        float.IsFinite(value.X) && float.IsFinite(value.Y) && float.IsFinite(value.Z) && value.Magnitude is >= 0.20f and <= 2.50f;

    private static Vector3f LowPass(Vector3f? previous, Vector3f current, float alpha) => previous is not Vector3f value
        ? current
        : new Vector3f(
            value.X + alpha * (current.X - value.X),
            value.Y + alpha * (current.Y - value.Y),
            value.Z + alpha * (current.Z - value.Z));

    private static float Dot(Vector3f first, Vector3f second) =>
        first.X * second.X + first.Y * second.Y + first.Z * second.Z;
}
