using TrikiMusicController_Windows.Models;

namespace TrikiMusicController_Windows.Core;

public sealed record HoldGestureConfiguration(
    long HoldMillis = 500,
    float TriggerDisplacementMeters = 0.20f,
    float MotionStartAccelerationG = 0.12f,
    float AccelerationDeadZoneG = 0.06f,
    long MaximumMotionMillis = 1_800,
    float LinearAccelerationSmoothingAlpha = 0.35f,
    float ArmingAccelerationToleranceG = 0.18f,
    float ArmingMaximumAngularRateDps = 45f,
    long DirectionConfirmationMillis = 120,
    float MinimumDirectionImpulseGSeconds = 0.025f,
    float MinimumDirectionPeakAccelerationG = 0.16f,
    int MaximumCandidateDirectionChanges = 1,
    float BrakingAccelerationG = 0.08f,
    float MinimumDisplacementBeforeBrakingMeters = 0.06f,
    float MinimumBrakingImpulseGSeconds = 0.025f,
    long MinimumMotionMillis = 220,
    float DirectionMismatchToleranceMeters = 0.06f,
    float MaximumTriggerVelocityMetersPerSecond = 0.70f,
    float MaximumTriggerDisplacementMeters = 0.34f,
    float MaximumMotionAngularRateDps = 120f,
    long MaximumRotationMillis = 80,
    long RearmQuietMillis = 140);

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
    private RatingGestureAction? _candidateAction;
    private RatingGestureAction? _confirmedAction;
    private long _directionConfirmationNanos;
    private float _directionImpulseGSeconds;
    private float _peakDirectionAccelerationG;
    private int _candidateDirectionChanges;
    private float _brakingImpulseGSeconds;
    private long? _excessiveRotationSinceNanos;
    private bool _awaitingQuietRearm;
    private long? _quietRearmSinceNanos;
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
            !float.IsFinite(_configuration.LinearAccelerationSmoothingAlpha) || _configuration.LinearAccelerationSmoothingAlpha is < 0.05f or > 1f ||
            !float.IsFinite(_configuration.ArmingAccelerationToleranceG) || _configuration.ArmingAccelerationToleranceG is < 0.05f or > 0.40f ||
            !float.IsFinite(_configuration.ArmingMaximumAngularRateDps) || _configuration.ArmingMaximumAngularRateDps is < 10f or > 120f ||
            _configuration.DirectionConfirmationMillis is < 40 or > 300 ||
            !float.IsFinite(_configuration.MinimumDirectionImpulseGSeconds) ||
            _configuration.MinimumDirectionImpulseGSeconds is < 0.005f or > 0.20f ||
            !float.IsFinite(_configuration.MinimumDirectionPeakAccelerationG) ||
            _configuration.MinimumDirectionPeakAccelerationG < _configuration.MotionStartAccelerationG ||
            _configuration.MinimumDirectionPeakAccelerationG > 1f ||
            _configuration.MaximumCandidateDirectionChanges is < 0 or > 3 ||
            !float.IsFinite(_configuration.BrakingAccelerationG) ||
            _configuration.BrakingAccelerationG < _configuration.AccelerationDeadZoneG ||
            _configuration.BrakingAccelerationG > _configuration.MotionStartAccelerationG ||
            !float.IsFinite(_configuration.MinimumDisplacementBeforeBrakingMeters) ||
            _configuration.MinimumDisplacementBeforeBrakingMeters is < 0.02f ||
            _configuration.MinimumDisplacementBeforeBrakingMeters > _configuration.TriggerDisplacementMeters ||
            !float.IsFinite(_configuration.MinimumBrakingImpulseGSeconds) ||
            _configuration.MinimumBrakingImpulseGSeconds is < 0.005f or > 0.20f ||
            _configuration.MinimumMotionMillis < _configuration.DirectionConfirmationMillis ||
            _configuration.MinimumMotionMillis > _configuration.MaximumMotionMillis ||
            !float.IsFinite(_configuration.DirectionMismatchToleranceMeters) ||
            _configuration.DirectionMismatchToleranceMeters is < 0.02f ||
            _configuration.DirectionMismatchToleranceMeters > _configuration.TriggerDisplacementMeters ||
            !float.IsFinite(_configuration.MaximumTriggerVelocityMetersPerSecond) ||
            _configuration.MaximumTriggerVelocityMetersPerSecond is < 0.20f or > 2f ||
            !float.IsFinite(_configuration.MaximumTriggerDisplacementMeters) ||
            _configuration.MaximumTriggerDisplacementMeters < _configuration.TriggerDisplacementMeters ||
            _configuration.MaximumTriggerDisplacementMeters > 0.60f ||
            !float.IsFinite(_configuration.MaximumMotionAngularRateDps) ||
            _configuration.MaximumMotionAngularRateDps is < 60f or > 360f ||
            _configuration.MaximumMotionAngularRateDps <= _configuration.ArmingMaximumAngularRateDps ||
            _configuration.MaximumRotationMillis is < 40 or > 300 ||
            _configuration.RearmQuietMillis is < 80 or > 500)
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
        if (_triggered) return Result(HoldGesturePhase.Triggered, 1);

        var acceleration = sample.AccelerometerG;
        if (!IsUsableAcceleration(acceleration))
        {
            RestartArming(timestamp, null);
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
            RestartArming(timestamp, acceleration);
            return Result(HoldGesturePhase.Holding, 0);
        }
        var deltaNanos = timestamp - previous;
        var deltaSeconds = deltaNanos / 1_000_000_000f;
        _previousTimestampNanos = timestamp;

        var holdNanos = _configuration.HoldMillis * 1_000_000;
        var heldNanos = Math.Max(0, timestamp - pressStart);
        var holdProgress = Math.Clamp((float)((double)heldNanos / holdNanos), 0, 1);
        if (heldNanos < holdNanos)
        {
            if (!IsStableForArming(sample))
            {
                RestartArming(timestamp, acceleration);
                return Result(HoldGesturePhase.Holding, 0);
            }
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

        if (_awaitingQuietRearm)
        {
            var quiet = Math.Abs(_filteredLinearAccelerationG) < _configuration.AccelerationDeadZoneG &&
                sample.GyroscopeMagnitude <= _configuration.ArmingMaximumAngularRateDps;
            if (quiet)
            {
                _quietRearmSinceNanos ??= timestamp;
                _gravityBaseline = LowPass(_gravityBaseline, acceleration, 0.025f);
                if (timestamp - _quietRearmSinceNanos.Value >= _configuration.RearmQuietMillis * 1_000_000)
                    ResetMotion();
            }
            else
            {
                _quietRearmSinceNanos = null;
            }
            return Result(HoldGesturePhase.Rearming, 1);
        }

        if (sample.GyroscopeMagnitude > _configuration.MaximumMotionAngularRateDps)
        {
            _excessiveRotationSinceNanos ??= timestamp;
            if (timestamp - _excessiveRotationSinceNanos.Value >= _configuration.MaximumRotationMillis * 1_000_000)
            {
                InvalidateMotion();
                return Result(HoldGesturePhase.Rearming, 1);
            }
            return Result(_motionStartedNanos is null ? HoldGesturePhase.Ready : HoldGesturePhase.Tracking, 1);
        }
        _excessiveRotationSinceNanos = null;

        if (_motionStartedNanos is null)
        {
            if (Math.Abs(_filteredLinearAccelerationG) < _configuration.MotionStartAccelerationG)
            {
                _gravityBaseline = LowPass(_gravityBaseline, acceleration, 0.025f);
                ResetMotion();
                return Result(HoldGesturePhase.Ready, 1);
            }
            StartMotion(timestamp, ActionForAcceleration(_filteredLinearAccelerationG));
        }

        var motionStarted = _motionStartedNanos ??
            throw new InvalidOperationException("Stan ruchu nie został zainicjalizowany.");
        var motionElapsedNanos = timestamp - motionStarted;
        if (motionElapsedNanos > _configuration.MaximumMotionMillis * 1_000_000)
        {
            InvalidateMotion();
            return Result(HoldGesturePhase.Rearming, 1);
        }

        var effectiveAccelerationG = Math.Abs(_filteredLinearAccelerationG) < _configuration.AccelerationDeadZoneG
            ? 0
            : _filteredLinearAccelerationG;
        if (_confirmedAction is null)
        {
            if (effectiveAccelerationG == 0)
            {
                InvalidateMotion();
                return Result(HoldGesturePhase.Rearming, 1);
            }
            var currentAction = ActionForAcceleration(effectiveAccelerationG);
            if (currentAction != _candidateAction)
            {
                _candidateDirectionChanges++;
                if (_candidateDirectionChanges > _configuration.MaximumCandidateDirectionChanges)
                {
                    InvalidateMotion();
                    return Result(HoldGesturePhase.Rearming, 1);
                }
                StartMotion(timestamp, currentAction, preserveDirectionChanges: true);
                motionElapsedNanos = 0;
            }
            else
            {
                _directionConfirmationNanos += deltaNanos;
                _directionImpulseGSeconds += Math.Abs(effectiveAccelerationG) * deltaSeconds;
                _peakDirectionAccelerationG = Math.Max(_peakDirectionAccelerationG, Math.Abs(effectiveAccelerationG));
                if (_directionConfirmationNanos >= _configuration.DirectionConfirmationMillis * 1_000_000 &&
                    _directionImpulseGSeconds >= _configuration.MinimumDirectionImpulseGSeconds &&
                    _peakDirectionAccelerationG >= _configuration.MinimumDirectionPeakAccelerationG)
                    _confirmedAction = currentAction;
            }
        }
        else if (IsAccelerationOppositeTo(effectiveAccelerationG, _confirmedAction.Value) &&
                 Math.Abs(effectiveAccelerationG) >= _configuration.BrakingAccelerationG &&
                 DirectionalDisplacement(_confirmedAction.Value) >= _configuration.MinimumDisplacementBeforeBrakingMeters)
        {
            _brakingImpulseGSeconds += Math.Abs(effectiveAccelerationG) * deltaSeconds;
        }

        var accelerationMetersPerSecondSquared = effectiveAccelerationG * StandardGravity;
        _displacementMeters += _verticalVelocityMetersPerSecond * deltaSeconds +
            0.5f * accelerationMetersPerSecondSquared * deltaSeconds * deltaSeconds;
        _verticalVelocityMetersPerSecond = Math.Clamp(
            _verticalVelocityMetersPerSecond + accelerationMetersPerSecondSquared * deltaSeconds, -2, 2);
        if (effectiveAccelerationG == 0) _verticalVelocityMetersPerSecond *= 0.92f;
        _displacementMeters = Math.Clamp(_displacementMeters, -0.60f, 0.60f);

        var lockedAction = _confirmedAction;
        if (lockedAction is RatingGestureAction confirmed &&
            DirectionalDisplacement(confirmed) < -_configuration.DirectionMismatchToleranceMeters)
        {
            InvalidateMotion();
            return Result(HoldGesturePhase.Rearming, 1);
        }

        if (lockedAction is RatingGestureAction boundedAction &&
            DirectionalDisplacement(boundedAction) > _configuration.MaximumTriggerDisplacementMeters)
        {
            InvalidateMotion();
            return Result(HoldGesturePhase.Rearming, 1);
        }

        RatingGestureAction? action = lockedAction is RatingGestureAction locked &&
                                      _brakingImpulseGSeconds >= _configuration.MinimumBrakingImpulseGSeconds &&
                                      Math.Abs(_verticalVelocityMetersPerSecond) <= _configuration.MaximumTriggerVelocityMetersPerSecond &&
                                      motionElapsedNanos >= _configuration.MinimumMotionMillis * 1_000_000 &&
                                      DirectionalDisplacement(locked) >= _configuration.TriggerDisplacementMeters
            ? locked
            : null;
        if (action is not null) _triggered = true;
        var phase = _triggered
            ? HoldGesturePhase.Triggered
            : _brakingImpulseGSeconds > 0
                ? HoldGesturePhase.Completing
                : HoldGesturePhase.Tracking;
        return Result(phase, 1, action);
    }

    private HoldVerticalGestureResult Result(HoldGesturePhase phase, float progress, RatingGestureAction? action = null) =>
        new(action, _confirmedAction, phase, progress, _displacementMeters);

    private void ResetMotion()
    {
        ClearMotionState();
        _awaitingQuietRearm = false;
        _quietRearmSinceNanos = null;
    }

    private void ClearMotionState()
    {
        _motionStartedNanos = null;
        _filteredLinearAccelerationG = 0;
        _verticalVelocityMetersPerSecond = 0;
        _displacementMeters = 0;
        _candidateAction = null;
        _confirmedAction = null;
        _directionConfirmationNanos = 0;
        _directionImpulseGSeconds = 0;
        _peakDirectionAccelerationG = 0;
        _candidateDirectionChanges = 0;
        _brakingImpulseGSeconds = 0;
        _excessiveRotationSinceNanos = null;
    }

    private void InvalidateMotion()
    {
        ClearMotionState();
        _awaitingQuietRearm = true;
        _quietRearmSinceNanos = null;
    }

    private void StartMotion(long timestamp, RatingGestureAction action, bool preserveDirectionChanges = false)
    {
        _motionStartedNanos = timestamp;
        _verticalVelocityMetersPerSecond = 0;
        _displacementMeters = 0;
        _candidateAction = action;
        _confirmedAction = null;
        _directionConfirmationNanos = 0;
        _directionImpulseGSeconds = 0;
        _peakDirectionAccelerationG = 0;
        _brakingImpulseGSeconds = 0;
        if (!preserveDirectionChanges) _candidateDirectionChanges = 0;
    }

    private void RestartArming(long timestamp, Vector3f? acceleration)
    {
        _pressedSinceNanos = timestamp;
        _previousTimestampNanos = timestamp;
        _gravityBaseline = acceleration;
        ResetMotion();
        _triggered = false;
    }

    private static bool IsUsableAcceleration(Vector3f value) =>
        float.IsFinite(value.X) && float.IsFinite(value.Y) && float.IsFinite(value.Z) && value.Magnitude is >= 0.20f and <= 2.50f;

    private bool IsStableForArming(FilteredSensorData sample) =>
        Math.Abs(sample.AccelerationMagnitude - 1) <= _configuration.ArmingAccelerationToleranceG &&
        sample.GyroscopeMagnitude <= _configuration.ArmingMaximumAngularRateDps;

    private static RatingGestureAction ActionForAcceleration(float accelerationG) =>
        accelerationG < 0 ? RatingGestureAction.Like : RatingGestureAction.Dislike;

    private static bool IsAccelerationOppositeTo(float accelerationG, RatingGestureAction action) => action switch
    {
        RatingGestureAction.Like => accelerationG > 0,
        RatingGestureAction.Dislike => accelerationG < 0,
        _ => false,
    };

    private float DirectionalDisplacement(RatingGestureAction action) => action switch
    {
        RatingGestureAction.Like => -_displacementMeters,
        RatingGestureAction.Dislike => _displacementMeters,
        _ => 0,
    };

    private static Vector3f LowPass(Vector3f? previous, Vector3f current, float alpha) => previous is not Vector3f value
        ? current
        : new Vector3f(
            value.X + alpha * (current.X - value.X),
            value.Y + alpha * (current.Y - value.Y),
            value.Z + alpha * (current.Z - value.Z));

    private static float Dot(Vector3f first, Vector3f second) =>
        first.X * second.X + first.Y * second.Y + first.Z * second.Z;
}
