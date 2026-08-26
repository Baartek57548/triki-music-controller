using TrikiMusicController_Windows.Core;
using TrikiMusicController_Windows.Models;

namespace TrikiMusicController.Windows.Tests;

public sealed class HoldVerticalGestureDetectorTests
{
    [Fact]
    public void ProductionConfiguration_PreservesPhysicalDirectionConvention()
    {
        var lift = new Fixture(configuration: new HoldGestureConfiguration());
        lift.HoldAtRest();
        lift.Accelerate(-0.6f, 15);
        lift.Accelerate(-1.4f, 15);

        var lowering = new Fixture(configuration: new HoldGestureConfiguration());
        lowering.HoldAtRest();
        lowering.Accelerate(-1.4f, 15);
        lowering.Accelerate(-0.6f, 15);

        Assert.Equal([RatingGestureAction.Like], lift.Actions);
        Assert.Equal([RatingGestureAction.Dislike], lowering.Actions);
    }

    [Fact]
    public void LiftNearTwentyFiveDegreeTilt_StillEmitsLike()
    {
        var gravityAtTiltLimit = new Vector3f(0.423f, 0, -0.906f);
        var fixture = new Fixture(
            configuration: new HoldGestureConfiguration(),
            restAcceleration: gravityAtTiltLimit);
        fixture.HoldAtRest();

        fixture.Accelerate(Scale(gravityAtTiltLimit, 0.6f), 15);
        fixture.Accelerate(Scale(gravityAtTiltLimit, 1.4f), 15);

        Assert.Equal([RatingGestureAction.Like], fixture.Actions);
    }

    [Fact]
    public void LiftWhileHeld_EmitsLikeExactlyOnce()
    {
        var fixture = new Fixture();
        fixture.HoldAtRest();

        fixture.Accelerate(-0.6f, 13);
        fixture.Accelerate(-1.4f, 13);
        fixture.Accelerate(-1f, 10);
        fixture.Accelerate(-1.6f, 20);

        Assert.Equal([RatingGestureAction.Like], fixture.Actions);
        Assert.True(fixture.Latest.EstimatedDisplacementMeters <= -0.20f);
    }

    [Fact]
    public void LoweringWhileHeld_EmitsDislikeExactlyOnce()
    {
        var fixture = new Fixture();
        fixture.HoldAtRest();

        fixture.Accelerate(-1.4f, 13);
        fixture.Accelerate(-0.6f, 13);
        fixture.Accelerate(-1f, 10);

        Assert.Equal([RatingGestureAction.Dislike], fixture.Actions);
        Assert.True(fixture.Latest.EstimatedDisplacementMeters >= 0.20f);
    }

    [Fact]
    public void MovementBeforeRequiredHold_DoesNotRateTrack()
    {
        var fixture = new Fixture();
        fixture.Accelerate(-1.4f, 8);
        fixture.Accelerate(-0.6f, 8);
        fixture.Release();

        Assert.Empty(fixture.Actions);
        Assert.Equal(HoldGesturePhase.Idle, fixture.Latest.Phase);
    }

    [Fact]
    public void DistanceWithoutBrakingPhase_DoesNotRateTrack()
    {
        var fixture = new Fixture();
        fixture.HoldAtRest();

        fixture.Accelerate(-0.6f, 22);
        fixture.Accelerate(-1f, 6);

        Assert.Empty(fixture.Actions);
    }

    [Fact]
    public void ShortDirectionalJolt_IsRejectedUntilMotionBecomesQuiet()
    {
        var fixture = new Fixture();
        fixture.HoldAtRest();

        fixture.Accelerate(-0.2f, 3);
        fixture.Accelerate(-1f, 10);

        Assert.Empty(fixture.Actions);
        Assert.Equal(HoldGesturePhase.Ready, fixture.Latest.Phase);
    }

    [Fact]
    public void BrakingOvershoot_CannotTurnConfirmedLikeIntoDislike()
    {
        var fixture = new Fixture();
        fixture.HoldAtRest();

        fixture.Accelerate(-0.6f, 6);
        fixture.Accelerate(-1.6f, 25);
        fixture.Accelerate(-1f, 10);

        Assert.Empty(fixture.Actions);
    }

    [Fact]
    public void Rotation_IsRejectedAndDetectorRearmsAfterQuietPeriod()
    {
        var fixture = new Fixture();
        fixture.HoldAtRest();

        fixture.Accelerate(-0.6f, 13, gyroscopeZ: 180f);
        fixture.Accelerate(-1.4f, 13, gyroscopeZ: 180f);
        fixture.Accelerate(-1f, 10);
        Assert.Empty(fixture.Actions);

        fixture.Accelerate(-0.6f, 13);
        fixture.Accelerate(-1.4f, 13);

        Assert.Equal([RatingGestureAction.Like], fixture.Actions);
    }

    [Fact]
    public void HandTremorWhileArmed_DoesNotStartRatingGesture()
    {
        var fixture = new Fixture();
        fixture.HoldAtRest();

        for (var index = 0; index < 20; index++)
            fixture.Accelerate(
                index % 2 == 0 ? -0.96f : -1.04f,
                1,
                gyroscopeZ: index % 2 == 0 ? 24f : -24f);

        Assert.Empty(fixture.Actions);
        Assert.Equal(HoldGesturePhase.Ready, fixture.Latest.Phase);
    }

    [Fact]
    public void MovementWithoutPressedButton_IsIgnored()
    {
        var fixture = new Fixture(buttonPressed: false);
        fixture.Accelerate(-1.5f, 40);

        Assert.Empty(fixture.Actions);
        Assert.Equal(HoldGesturePhase.Idle, fixture.Latest.Phase);
    }

    private sealed class Fixture
    {
        private readonly HoldVerticalGestureDetector _detector;
        private readonly Vector3f _restAcceleration;
        private bool _buttonPressed;
        private long _timestampNanos;

        public Fixture(
            bool buttonPressed = true,
            HoldGestureConfiguration? configuration = null,
            Vector3f? restAcceleration = null)
        {
            _detector = new HoldVerticalGestureDetector(configuration ?? new HoldGestureConfiguration(
                HoldMillis: 400,
                TriggerDisplacementMeters: 0.20f,
                MotionStartAccelerationG: 0.10f,
                AccelerationDeadZoneG: 0.03f,
                LinearAccelerationSmoothingAlpha: 1f));
            _restAcceleration = restAcceleration ?? new Vector3f(0, 0, -1);
            _buttonPressed = buttonPressed;
            Latest = _detector.Process(SensorTestData.Filtered(0, _restAcceleration), _buttonPressed);
        }

        public List<RatingGestureAction> Actions { get; } = [];
        public HoldVerticalGestureResult Latest { get; private set; }

        public void HoldAtRest()
        {
            Accelerate(_restAcceleration, 25);
            Assert.Equal(1f, Latest.HoldProgress);
        }

        public void Accelerate(float z, int frames, float gyroscopeZ = 0)
        {
            Accelerate(new Vector3f(0, 0, z), frames, gyroscopeZ);
        }

        public void Accelerate(Vector3f acceleration, int frames, float gyroscopeZ = 0)
        {
            for (var frame = 0; frame < frames; frame++)
            {
                _timestampNanos += SensorTestData.SamplePeriodNanos;
                Latest = _detector.Process(
                    SensorTestData.Filtered(
                        _timestampNanos,
                        acceleration,
                        gyroscope: new Vector3f(0, 0, gyroscopeZ),
                        status: 1),
                    _buttonPressed);
                if (Latest.Action is { } action) Actions.Add(action);
            }
        }

        public void Release()
        {
            _buttonPressed = false;
            _timestampNanos += SensorTestData.SamplePeriodNanos;
            Latest = _detector.Process(
                SensorTestData.Filtered(_timestampNanos, _restAcceleration),
                _buttonPressed);
        }
    }

    private static Vector3f Scale(Vector3f value, float scale) =>
        new(value.X * scale, value.Y * scale, value.Z * scale);
}
