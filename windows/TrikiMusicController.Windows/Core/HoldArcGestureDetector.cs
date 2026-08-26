using TrikiMusicController_Windows.Models;

namespace TrikiMusicController_Windows.Core;

public sealed record HoldGestureConfiguration(
    long HoldMillis = 500,
    float TriggerDisplacementMeters = 0.10f,
    float MotionStartAccelerationG = 0.08f,
    float AccelerationDeadZoneG = 0.05f,
    float VerticalAccelerationDeadZoneG = 0.035f,
    long MaximumMotionMillis = 1_800,
    float LinearAccelerationSmoothingAlpha = 0.35f,
    float ArmingAccelerationToleranceG = 0.18f,
    float ArmingMaximumAngularRateDps = 45f,
    float MaximumFaceDownTiltDegrees = 25f,
    long DirectionConfirmationMillis = 120,
    float MinimumDirectionImpulseGSeconds = 0.025f,
    float MinimumDirectionPeakAccelerationG = 0.12f,
    int MaximumCandidateDirectionChanges = 1,
    float BrakingAccelerationG = 0.08f,
    float MinimumDisplacementBeforeBrakingMeters = 0.04f,
    float MinimumBrakingImpulseGSeconds = 0.025f,
    float MinimumArcDepthMeters = 0.020f,
    float MaximumArcDepthMeters = 0.12f,
    float MinimumArcImpulseEachDirectionGSeconds = 0.010f,
    float MaximumFinalVerticalOffsetMeters = 0.07f,
    float MaximumForwardDisplacementMeters = 0.10f,
    long MinimumMotionMillis = 280,
    float DirectionMismatchToleranceMeters = 0.04f,
    float MaximumTriggerVelocityMetersPerSecond = 0.70f,
    float MaximumVerticalVelocityMetersPerSecond = 0.70f,
    float MaximumTriggerDisplacementMeters = 0.16f,
    float MaximumMotionAngularRateDps = 120f,
    long MaximumRotationMillis = 80,
    long RearmQuietMillis = 140);

public sealed class HoldArcGestureDetector
{
    private const long MaximumSampleGapNanos = 150_000_000;
    private const float StandardGravity = 9.80665f;
    private const float MinimumBaselineGravityG = 0.65f;
    private const float MaximumBaselineGravityG = 1.35f;
    private const float MinimumUsableAccelerationG = 0.20f;
    private const float MaximumUsableAccelerationG = 2.50f;
    private const float MinimumVectorMagnitude = 0.001f;
    private const float MaximumAbsoluteVelocityMetersPerSecond = 2f;
    private const float MaximumAbsoluteDisplacementMeters = 0.60f;
    private const float VelocityDampingWhenQuiet = 0.92f;
    private readonly HoldGestureConfiguration _configuration;
    private long? _stabilizationSinceNanos;
    private long? _previousTimestampNanos;
    private Vector3f? _gravityBaseline;
    private long? _motionStartedNanos;
    private float _filteredHorizontalAccelerationG;
    private float _filteredVerticalAccelerationG;
    private float _filteredForwardAccelerationG;
    private float _horizontalVelocityMetersPerSecond;
    private float _verticalVelocityMetersPerSecond;
    private float _forwardVelocityMetersPerSecond;
    private float _horizontalDisplacementMeters;
    private float _verticalDisplacementMeters;
    private float _forwardDisplacementMeters;
    private float _minimumVerticalDisplacementMeters;
    private float _maximumVerticalDisplacementMeters;
    private float _positiveVerticalImpulseGSeconds;
    private float _negativeVerticalImpulseGSeconds;
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
    private bool _faceDown;
    private bool _triggered;

    public HoldArcGestureDetector(HoldGestureConfiguration? configuration = null)
    {
        _configuration = configuration ?? new HoldGestureConfiguration();
        ValidateConfiguration(_configuration);
    }

    public void Reset()
    {
        _stabilizationSinceNanos = null;
        _previousTimestampNanos = null;
        _gravityBaseline = null;
        _faceDown = false;
        ResetMotion();
        _triggered = false;
    }

    public HoldArcGestureResult Process(FilteredSensorData sample)
    {
        var timestamp = sample.Source.TimestampNanos;
        var acceleration = sample.AccelerometerG;
        if (!IsUsableAcceleration(acceleration))
        {
            RestartArming(timestamp, null);
            return Result(HoldGesturePhase.Holding, 0);
        }
        _faceDown = IsFaceDown(acceleration);

        if (_stabilizationSinceNanos is null)
        {
            _stabilizationSinceNanos = timestamp;
            _previousTimestampNanos = timestamp;
            _gravityBaseline = acceleration;
            return Result(HoldGesturePhase.Holding, 0);
        }

        if (_previousTimestampNanos is not long previous ||
            timestamp <= previous ||
            timestamp - previous > MaximumSampleGapNanos)
        {
            RestartArming(timestamp, acceleration);
            return Result(HoldGesturePhase.Holding, 0);
        }
        var deltaNanos = timestamp - previous;
        var deltaSeconds = deltaNanos / 1_000_000_000f;
        _previousTimestampNanos = timestamp;

        var stabilizationStart = _stabilizationSinceNanos.Value;
        var stabilizationNanos = _configuration.HoldMillis * 1_000_000;
        var stabilizedNanos = Math.Max(0, timestamp - stabilizationStart);
        var holdProgress = Math.Clamp((float)((double)stabilizedNanos / stabilizationNanos), 0, 1);
        if (stabilizedNanos < stabilizationNanos)
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
        if (baselineMagnitude is < MinimumBaselineGravityG or > MaximumBaselineGravityG || !IsFaceDown(baseline))
        {
            RestartArming(timestamp, acceleration);
            return Result(HoldGesturePhase.Holding, 0);
        }
        _faceDown = true;
        var gravityUnit = Normalized(baseline);
        var rightUnit = RightUnit(gravityUnit);
        var forwardUnit = Normalized(Cross(gravityUnit, rightUnit));
        var linearAcceleration = new Vector3f(
            acceleration.X - gravityUnit.X * baselineMagnitude,
            acceleration.Y - gravityUnit.Y * baselineMagnitude,
            acceleration.Z - gravityUnit.Z * baselineMagnitude);
        _filteredHorizontalAccelerationG = Smooth(_filteredHorizontalAccelerationG, Dot(linearAcceleration, rightUnit));
        _filteredVerticalAccelerationG = Smooth(_filteredVerticalAccelerationG, Dot(linearAcceleration, gravityUnit));
        _filteredForwardAccelerationG = Smooth(_filteredForwardAccelerationG, Dot(linearAcceleration, forwardUnit));

        if (_awaitingQuietRearm)
        {
            var quiet = MaximumLinearAcceleration() < _configuration.AccelerationDeadZoneG &&
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
            if (Math.Abs(_filteredHorizontalAccelerationG) < _configuration.MotionStartAccelerationG)
            {
                ResetMotion();
                return Result(HoldGesturePhase.Ready, 1);
            }
            StartMotion(timestamp, ActionForAcceleration(_filteredHorizontalAccelerationG));
        }

        var motionStarted = _motionStartedNanos ??
            throw new InvalidOperationException("Stan ruchu nie został zainicjalizowany.");
        var motionElapsedNanos = timestamp - motionStarted;
        if (motionElapsedNanos > _configuration.MaximumMotionMillis * 1_000_000)
        {
            InvalidateMotion();
            return Result(HoldGesturePhase.Rearming, 1);
        }

        var effectiveHorizontalAccelerationG = DeadZone(
            _filteredHorizontalAccelerationG,
            _configuration.AccelerationDeadZoneG);
        if (_confirmedAction is null)
        {
            if (effectiveHorizontalAccelerationG == 0)
            {
                InvalidateMotion();
                return Result(HoldGesturePhase.Rearming, 1);
            }
            var currentAction = ActionForAcceleration(effectiveHorizontalAccelerationG);
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
                _directionImpulseGSeconds += Math.Abs(effectiveHorizontalAccelerationG) * deltaSeconds;
                _peakDirectionAccelerationG = Math.Max(_peakDirectionAccelerationG, Math.Abs(effectiveHorizontalAccelerationG));
                if (_directionConfirmationNanos >= _configuration.DirectionConfirmationMillis * 1_000_000 &&
                    _directionImpulseGSeconds >= _configuration.MinimumDirectionImpulseGSeconds &&
                    _peakDirectionAccelerationG >= _configuration.MinimumDirectionPeakAccelerationG)
                    _confirmedAction = currentAction;
            }
        }
        else if (IsAccelerationOppositeTo(effectiveHorizontalAccelerationG, _confirmedAction.Value) &&
                 Math.Abs(effectiveHorizontalAccelerationG) >= _configuration.BrakingAccelerationG &&
                 DirectionalDisplacement(_confirmedAction.Value) >= _configuration.MinimumDisplacementBeforeBrakingMeters)
        {
            _brakingImpulseGSeconds += Math.Abs(effectiveHorizontalAccelerationG) * deltaSeconds;
        }

        var effectiveVerticalAccelerationG = DeadZone(
            _filteredVerticalAccelerationG,
            _configuration.VerticalAccelerationDeadZoneG);
        if (effectiveVerticalAccelerationG > 0)
            _positiveVerticalImpulseGSeconds += effectiveVerticalAccelerationG * deltaSeconds;
        else if (effectiveVerticalAccelerationG < 0)
            _negativeVerticalImpulseGSeconds += -effectiveVerticalAccelerationG * deltaSeconds;

        IntegrateMotion(
            effectiveHorizontalAccelerationG,
            effectiveVerticalAccelerationG,
            DeadZone(_filteredForwardAccelerationG, _configuration.AccelerationDeadZoneG),
            deltaSeconds);

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
        if (ArcDepthMeters() > _configuration.MaximumArcDepthMeters ||
            Math.Abs(_forwardDisplacementMeters) > _configuration.MaximumForwardDisplacementMeters)
        {
            InvalidateMotion();
            return Result(HoldGesturePhase.Rearming, 1);
        }

        RatingGestureAction? action = lockedAction is RatingGestureAction locked &&
                                      _brakingImpulseGSeconds >= _configuration.MinimumBrakingImpulseGSeconds &&
                                      Math.Abs(_horizontalVelocityMetersPerSecond) <= _configuration.MaximumTriggerVelocityMetersPerSecond &&
                                      Math.Abs(_verticalVelocityMetersPerSecond) <= _configuration.MaximumVerticalVelocityMetersPerSecond &&
                                      Math.Abs(_verticalDisplacementMeters) <= _configuration.MaximumFinalVerticalOffsetMeters &&
                                      ArcDepthMeters() >= _configuration.MinimumArcDepthMeters &&
                                      _positiveVerticalImpulseGSeconds >= _configuration.MinimumArcImpulseEachDirectionGSeconds &&
                                      _negativeVerticalImpulseGSeconds >= _configuration.MinimumArcImpulseEachDirectionGSeconds &&
                                      motionElapsedNanos >= _configuration.MinimumMotionMillis * 1_000_000 &&
                                      DirectionalDisplacement(locked) >= _configuration.TriggerDisplacementMeters
            ? locked
            : null;
        if (action is not null)
        {
            _triggered = true;
            _awaitingQuietRearm = true;
            _quietRearmSinceNanos = null;
        }
        var phase = _triggered
            ? HoldGesturePhase.Triggered
            : _brakingImpulseGSeconds > 0
                ? HoldGesturePhase.Completing
                : HoldGesturePhase.Tracking;
        return Result(phase, 1, action);
    }

    private void IntegrateMotion(
        float horizontalAccelerationG,
        float verticalAccelerationG,
        float forwardAccelerationG,
        float deltaSeconds)
    {
        var horizontalAcceleration = horizontalAccelerationG * StandardGravity;
        var verticalAcceleration = verticalAccelerationG * StandardGravity;
        var forwardAcceleration = forwardAccelerationG * StandardGravity;
        _horizontalDisplacementMeters += _horizontalVelocityMetersPerSecond * deltaSeconds +
            0.5f * horizontalAcceleration * deltaSeconds * deltaSeconds;
        _verticalDisplacementMeters += _verticalVelocityMetersPerSecond * deltaSeconds +
            0.5f * verticalAcceleration * deltaSeconds * deltaSeconds;
        _forwardDisplacementMeters += _forwardVelocityMetersPerSecond * deltaSeconds +
            0.5f * forwardAcceleration * deltaSeconds * deltaSeconds;
        _horizontalVelocityMetersPerSecond = BoundedVelocity(
            _horizontalVelocityMetersPerSecond + horizontalAcceleration * deltaSeconds,
            horizontalAccelerationG);
        _verticalVelocityMetersPerSecond = BoundedVelocity(
            _verticalVelocityMetersPerSecond + verticalAcceleration * deltaSeconds,
            verticalAccelerationG);
        _forwardVelocityMetersPerSecond = BoundedVelocity(
            _forwardVelocityMetersPerSecond + forwardAcceleration * deltaSeconds,
            forwardAccelerationG);
        _horizontalDisplacementMeters = BoundedDisplacement(_horizontalDisplacementMeters);
        _verticalDisplacementMeters = BoundedDisplacement(_verticalDisplacementMeters);
        _forwardDisplacementMeters = BoundedDisplacement(_forwardDisplacementMeters);
        _minimumVerticalDisplacementMeters = Math.Min(_minimumVerticalDisplacementMeters, _verticalDisplacementMeters);
        _maximumVerticalDisplacementMeters = Math.Max(_maximumVerticalDisplacementMeters, _verticalDisplacementMeters);
    }

    private HoldArcGestureResult Result(HoldGesturePhase phase, float progress, RatingGestureAction? action = null) =>
        new(
            action,
            _confirmedAction,
            phase,
            progress,
            _faceDown,
            _horizontalDisplacementMeters,
            ArcDepthMeters());

    private void ResetMotion()
    {
        ClearMotionState();
        _awaitingQuietRearm = false;
        _quietRearmSinceNanos = null;
        _triggered = false;
    }

    private void ClearMotionState()
    {
        _motionStartedNanos = null;
        _filteredHorizontalAccelerationG = 0;
        _filteredVerticalAccelerationG = 0;
        _filteredForwardAccelerationG = 0;
        _horizontalVelocityMetersPerSecond = 0;
        _verticalVelocityMetersPerSecond = 0;
        _forwardVelocityMetersPerSecond = 0;
        _horizontalDisplacementMeters = 0;
        _verticalDisplacementMeters = 0;
        _forwardDisplacementMeters = 0;
        _minimumVerticalDisplacementMeters = 0;
        _maximumVerticalDisplacementMeters = 0;
        _positiveVerticalImpulseGSeconds = 0;
        _negativeVerticalImpulseGSeconds = 0;
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
        var horizontalAcceleration = _filteredHorizontalAccelerationG;
        var verticalAcceleration = _filteredVerticalAccelerationG;
        var forwardAcceleration = _filteredForwardAccelerationG;
        var directionChanges = preserveDirectionChanges ? _candidateDirectionChanges : 0;
        ClearMotionState();
        _filteredHorizontalAccelerationG = horizontalAcceleration;
        _filteredVerticalAccelerationG = verticalAcceleration;
        _filteredForwardAccelerationG = forwardAcceleration;
        _motionStartedNanos = timestamp;
        _candidateAction = action;
        _candidateDirectionChanges = directionChanges;
    }

    private void RestartArming(long timestamp, Vector3f? acceleration)
    {
        _stabilizationSinceNanos = timestamp;
        _previousTimestampNanos = timestamp;
        _gravityBaseline = acceleration;
        _faceDown = acceleration is Vector3f value && IsFaceDown(value);
        ResetMotion();
    }

    private bool IsStableForArming(FilteredSensorData sample) =>
        _faceDown &&
        Math.Abs(sample.AccelerationMagnitude - 1) <= _configuration.ArmingAccelerationToleranceG &&
        sample.GyroscopeMagnitude <= _configuration.ArmingMaximumAngularRateDps;

    private bool IsFaceDown(Vector3f value)
    {
        var magnitude = value.Magnitude;
        if (!float.IsFinite(magnitude) || magnitude < MinimumUsableAccelerationG) return false;
        var faceDownComponent = Math.Clamp(value.Z / magnitude, -1, 1);
        var minimumComponent = MathF.Cos(_configuration.MaximumFaceDownTiltDegrees * MathF.PI / 180f);
        return faceDownComponent >= minimumComponent;
    }

    private static RatingGestureAction ActionForAcceleration(float accelerationG) =>
        accelerationG > 0 ? RatingGestureAction.Like : RatingGestureAction.Dislike;

    private static bool IsAccelerationOppositeTo(float accelerationG, RatingGestureAction action) => action switch
    {
        RatingGestureAction.Like => accelerationG < 0,
        RatingGestureAction.Dislike => accelerationG > 0,
        _ => false,
    };

    private float DirectionalDisplacement(RatingGestureAction action) => action switch
    {
        RatingGestureAction.Like => _horizontalDisplacementMeters,
        RatingGestureAction.Dislike => -_horizontalDisplacementMeters,
        _ => 0,
    };

    private float MaximumLinearAcceleration() => Math.Max(
        Math.Abs(_filteredHorizontalAccelerationG),
        Math.Max(Math.Abs(_filteredVerticalAccelerationG), Math.Abs(_filteredForwardAccelerationG)));

    private float ArcDepthMeters() => _maximumVerticalDisplacementMeters - _minimumVerticalDisplacementMeters;

    private float Smooth(float previous, float current) =>
        previous + _configuration.LinearAccelerationSmoothingAlpha * (current - previous);

    private static float BoundedVelocity(float value, float accelerationG)
    {
        var damped = accelerationG == 0 ? value * VelocityDampingWhenQuiet : value;
        return Math.Clamp(damped, -MaximumAbsoluteVelocityMetersPerSecond, MaximumAbsoluteVelocityMetersPerSecond);
    }

    private static float BoundedDisplacement(float value) =>
        Math.Clamp(value, -MaximumAbsoluteDisplacementMeters, MaximumAbsoluteDisplacementMeters);

    private static float DeadZone(float value, float threshold) => Math.Abs(value) < threshold ? 0 : value;

    private static bool IsUsableAcceleration(Vector3f value) =>
        float.IsFinite(value.X) && float.IsFinite(value.Y) && float.IsFinite(value.Z) &&
        value.Magnitude is >= MinimumUsableAccelerationG and <= MaximumUsableAccelerationG;

    private static Vector3f Normalized(Vector3f value)
    {
        var magnitude = value.Magnitude;
        if (magnitude <= MinimumVectorMagnitude)
            throw new InvalidOperationException("Nie można znormalizować zerowego wektora czujnika.");
        return new Vector3f(value.X / magnitude, value.Y / magnitude, value.Z / magnitude);
    }

    private static Vector3f RightUnit(Vector3f gravityUnit)
    {
        var projection = gravityUnit.X;
        return Normalized(new Vector3f(
            1 - projection * gravityUnit.X,
            -projection * gravityUnit.Y,
            -projection * gravityUnit.Z));
    }

    private static Vector3f LowPass(Vector3f? previous, Vector3f current, float alpha) => previous is not Vector3f value
        ? current
        : new Vector3f(
            value.X + alpha * (current.X - value.X),
            value.Y + alpha * (current.Y - value.Y),
            value.Z + alpha * (current.Z - value.Z));

    private static Vector3f Cross(Vector3f first, Vector3f second) => new(
        first.Y * second.Z - first.Z * second.Y,
        first.Z * second.X - first.X * second.Z,
        first.X * second.Y - first.Y * second.X);

    private static float Dot(Vector3f first, Vector3f second) =>
        first.X * second.X + first.Y * second.Y + first.Z * second.Z;

    private static void ValidateConfiguration(HoldGestureConfiguration value)
    {
        if (value.HoldMillis is < 200 or > 3_000 ||
            !float.IsFinite(value.TriggerDisplacementMeters) || value.TriggerDisplacementMeters is < 0.10f or > 0.50f ||
            !float.IsFinite(value.MotionStartAccelerationG) || value.MotionStartAccelerationG is < 0.05f or > 1f ||
            !float.IsFinite(value.AccelerationDeadZoneG) || value.AccelerationDeadZoneG is < 0.01f ||
            value.AccelerationDeadZoneG > value.MotionStartAccelerationG ||
            !float.IsFinite(value.VerticalAccelerationDeadZoneG) || value.VerticalAccelerationDeadZoneG is < 0.01f ||
            value.VerticalAccelerationDeadZoneG > value.MotionStartAccelerationG ||
            value.MaximumMotionMillis is < 500 or > 4_000 ||
            !float.IsFinite(value.LinearAccelerationSmoothingAlpha) || value.LinearAccelerationSmoothingAlpha is < 0.05f or > 1f ||
            !float.IsFinite(value.ArmingAccelerationToleranceG) || value.ArmingAccelerationToleranceG is < 0.05f or > 0.40f ||
            !float.IsFinite(value.ArmingMaximumAngularRateDps) || value.ArmingMaximumAngularRateDps is < 10f or > 120f ||
            !float.IsFinite(value.MaximumFaceDownTiltDegrees) || value.MaximumFaceDownTiltDegrees is < 5f or > 45f ||
            value.DirectionConfirmationMillis is < 40 or > 300 ||
            !float.IsFinite(value.MinimumDirectionImpulseGSeconds) || value.MinimumDirectionImpulseGSeconds is < 0.005f or > 0.20f ||
            !float.IsFinite(value.MinimumDirectionPeakAccelerationG) ||
            value.MinimumDirectionPeakAccelerationG < value.MotionStartAccelerationG || value.MinimumDirectionPeakAccelerationG > 1f ||
            value.MaximumCandidateDirectionChanges is < 0 or > 3 ||
            !float.IsFinite(value.BrakingAccelerationG) || value.BrakingAccelerationG < value.AccelerationDeadZoneG ||
            value.BrakingAccelerationG > value.MotionStartAccelerationG ||
            !float.IsFinite(value.MinimumDisplacementBeforeBrakingMeters) || value.MinimumDisplacementBeforeBrakingMeters is < 0.02f ||
            value.MinimumDisplacementBeforeBrakingMeters > value.TriggerDisplacementMeters ||
            !float.IsFinite(value.MinimumBrakingImpulseGSeconds) || value.MinimumBrakingImpulseGSeconds is < 0.005f or > 0.20f ||
            !float.IsFinite(value.MinimumArcDepthMeters) || value.MinimumArcDepthMeters is < 0.01f ||
            !float.IsFinite(value.MaximumArcDepthMeters) || value.MaximumArcDepthMeters is < 0.03f or > 0.25f ||
            value.MinimumArcDepthMeters > value.MaximumArcDepthMeters ||
            !float.IsFinite(value.MinimumArcImpulseEachDirectionGSeconds) ||
            value.MinimumArcImpulseEachDirectionGSeconds is < 0.003f or > 0.10f ||
            !float.IsFinite(value.MaximumFinalVerticalOffsetMeters) ||
            value.MaximumFinalVerticalOffsetMeters < value.MinimumArcDepthMeters || value.MaximumFinalVerticalOffsetMeters > 0.20f ||
            !float.IsFinite(value.MaximumForwardDisplacementMeters) || value.MaximumForwardDisplacementMeters is < 0.03f or > 0.25f ||
            value.MinimumMotionMillis < value.DirectionConfirmationMillis || value.MinimumMotionMillis > value.MaximumMotionMillis ||
            !float.IsFinite(value.DirectionMismatchToleranceMeters) || value.DirectionMismatchToleranceMeters is < 0.02f ||
            value.DirectionMismatchToleranceMeters > value.TriggerDisplacementMeters ||
            !float.IsFinite(value.MaximumTriggerVelocityMetersPerSecond) ||
            value.MaximumTriggerVelocityMetersPerSecond is < 0.20f or > 2f ||
            !float.IsFinite(value.MaximumVerticalVelocityMetersPerSecond) ||
            value.MaximumVerticalVelocityMetersPerSecond is < 0.20f or > 2f ||
            !float.IsFinite(value.MaximumTriggerDisplacementMeters) ||
            value.MaximumTriggerDisplacementMeters < value.TriggerDisplacementMeters ||
            value.MaximumTriggerDisplacementMeters > MaximumAbsoluteDisplacementMeters ||
            !float.IsFinite(value.MaximumMotionAngularRateDps) || value.MaximumMotionAngularRateDps is < 60f or > 360f ||
            value.MaximumMotionAngularRateDps <= value.ArmingMaximumAngularRateDps ||
            value.MaximumRotationMillis is < 40 or > 300 ||
            value.RearmQuietMillis is < 80 or > 500)
            throw new ArgumentOutOfRangeException(nameof(value));
    }
}
