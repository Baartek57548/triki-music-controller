using TrikiMusicController_Windows.Core;
using TrikiMusicController_Windows.Models;

namespace TrikiMusicController.Windows.Tests;

public sealed class HoldArcGestureDetectorTests
{
    [Fact]
    public void ProductionConfiguration_MapsRightArcToLikeAndLeftArcToDislike()
    {
        var right = new Fixture(new HoldGestureConfiguration());
        right.HoldAtRest();
        right.ParabolicArc(rightward: true);

        var left = new Fixture(new HoldGestureConfiguration());
        left.HoldAtRest();
        left.ParabolicArc(rightward: false);

        Assert.Equal([RatingGestureAction.Like], right.Actions);
        Assert.Equal([RatingGestureAction.Dislike], left.Actions);
        Assert.True(right.Latest.EstimatedHorizontalDisplacementMeters >= 0.20f);
        Assert.True(left.Latest.EstimatedHorizontalDisplacementMeters <= -0.20f);
        Assert.True(right.Latest.EstimatedArcDepthMeters >= 0.020f);
    }

    [Fact]
    public void OneArc_EmitsOnlyOneActionUntilButtonIsReleased()
    {
        var fixture = new Fixture();
        fixture.HoldAtRest();
        fixture.ParabolicArc(rightward: true);
        fixture.Accelerate(fixture.RestAcceleration, 30);
        fixture.ParabolicArc(rightward: false);

        Assert.Equal([RatingGestureAction.Like], fixture.Actions);
        Assert.Equal(HoldGesturePhase.Triggered, fixture.Latest.Phase);

        fixture.Release();
        Assert.Equal(HoldGesturePhase.Idle, fixture.Latest.Phase);
    }

    [Fact]
    public void FaceUpCapsule_CannotArmRatingGesture()
    {
        var fixture = new Fixture(restAcceleration: new Vector3f(0, 0, -1));
        fixture.HoldAtRest(expectReady: false);
        fixture.ParabolicArc(rightward: true);

        Assert.Empty(fixture.Actions);
        Assert.False(fixture.Latest.FaceDown);
        Assert.Equal(HoldGesturePhase.Holding, fixture.Latest.Phase);
    }

    [Fact]
    public void StraightHorizontalSwipe_IsRejectedWithoutVerticalArc()
    {
        var fixture = new Fixture();
        fixture.HoldAtRest();
        fixture.HorizontalSwipe(rightward: true);

        Assert.Empty(fixture.Actions);
        Assert.True(fixture.Latest.EstimatedArcDepthMeters < 0.015f);
        Assert.Null(fixture.Latest.Action);
    }

    [Fact]
    public void VerticalMovementWithoutHorizontalTravel_CannotRateTrack()
    {
        var fixture = new Fixture();
        fixture.HoldAtRest();
        fixture.Accelerate(new Vector3f(0, 0, 1.20f), 8);
        fixture.Accelerate(new Vector3f(0, 0, 0.80f), 16);
        fixture.Accelerate(new Vector3f(0, 0, 1.20f), 8);

        Assert.Empty(fixture.Actions);
        Assert.Equal(HoldGesturePhase.Ready, fixture.Latest.Phase);
    }

    [Fact]
    public void OneSidedVerticalDeviation_IsNotAcceptedAsArc()
    {
        var fixture = new Fixture();
        fixture.HoldAtRest();
        fixture.Accelerate(new Vector3f(0.30f, 0, 1.15f), 14);
        fixture.Accelerate(new Vector3f(-0.30f, 0, 1.15f), 14);

        Assert.Empty(fixture.Actions);
    }

    [Fact]
    public void ShortArcBelowTwentyCentimeters_IsIgnored()
    {
        var fixture = new Fixture();
        fixture.HoldAtRest();
        fixture.ParabolicArc(rightward: true, horizontalAccelerationG: 0.20f, quarterFrames: 5);

        Assert.Empty(fixture.Actions);
        Assert.Null(fixture.Latest.Action);
    }

    [Fact]
    public void OversizedThrow_IsRejectedInsteadOfGuessingRating()
    {
        var fixture = new Fixture();
        fixture.HoldAtRest();
        fixture.ParabolicArc(rightward: true, horizontalAccelerationG: 0.45f, quarterFrames: 9);

        Assert.Empty(fixture.Actions);
        Assert.Equal(HoldGesturePhase.Rearming, fixture.Latest.Phase);
    }

    [Fact]
    public void ForwardThrow_IsNotConfusedWithLeftOrRight()
    {
        var fixture = new Fixture();
        fixture.HoldAtRest();
        fixture.Accelerate(new Vector3f(0, 0.30f, 1.15f), 7);
        fixture.Accelerate(new Vector3f(0, 0.30f, 0.85f), 7);
        fixture.Accelerate(new Vector3f(0, -0.30f, 0.85f), 7);
        fixture.Accelerate(new Vector3f(0, -0.30f, 1.15f), 7);

        Assert.Empty(fixture.Actions);
        Assert.Equal(HoldGesturePhase.Ready, fixture.Latest.Phase);
    }

    [Fact]
    public void MovementBeforeFullHold_CannotRateTrack()
    {
        var fixture = new Fixture();
        fixture.ParabolicArc(rightward: true);
        fixture.Release();

        Assert.Empty(fixture.Actions);
        Assert.Equal(HoldGesturePhase.Idle, fixture.Latest.Phase);
    }

    [Fact]
    public void StrongRotation_InvalidatesArcAndRearmsAfterQuietPeriod()
    {
        var fixture = new Fixture();
        fixture.HoldAtRest();
        fixture.ParabolicArc(rightward: true, gyroscopeZ: 180);

        Assert.Empty(fixture.Actions);
        Assert.Equal(HoldGesturePhase.Rearming, fixture.Latest.Phase);

        fixture.Accelerate(fixture.RestAcceleration, 12);
        fixture.ParabolicArc(rightward: false);

        Assert.Equal([RatingGestureAction.Dislike], fixture.Actions);
    }

    [Fact]
    public void HandTremorWhileArmed_DoesNotStartGesture()
    {
        var fixture = new Fixture();
        fixture.HoldAtRest();
        for (var index = 0; index < 30; index++)
        {
            fixture.Accelerate(
                new Vector3f(index % 2 == 0 ? 0.035f : -0.035f, 0, index % 2 == 0 ? 1.025f : 0.975f),
                1,
                index % 2 == 0 ? 20 : -20);
        }

        Assert.Empty(fixture.Actions);
        Assert.Equal(HoldGesturePhase.Ready, fixture.Latest.Phase);
    }

    [Fact]
    public void MovementWithoutHeldButton_IsIgnored()
    {
        var fixture = new Fixture(buttonPressed: false);
        fixture.ParabolicArc(rightward: true);

        Assert.Empty(fixture.Actions);
        Assert.Equal(HoldGesturePhase.Idle, fixture.Latest.Phase);
    }

    private sealed class Fixture
    {
        private const long SamplePeriodNanos = 20_000_000;
        private readonly HoldArcGestureDetector _detector;
        private bool _buttonPressed;
        private long _timestampNanos;

        public Fixture(
            HoldGestureConfiguration? configuration = null,
            bool buttonPressed = true,
            Vector3f? restAcceleration = null)
        {
            _buttonPressed = buttonPressed;
            RestAcceleration = restAcceleration ?? new Vector3f(0, 0, 1);
            _detector = new HoldArcGestureDetector(configuration ?? new HoldGestureConfiguration(
                HoldMillis: 400,
                MotionStartAccelerationG: 0.10f,
                AccelerationDeadZoneG: 0.03f,
                VerticalAccelerationDeadZoneG: 0.02f,
                LinearAccelerationSmoothingAlpha: 1,
                MinimumArcImpulseEachDirectionGSeconds: 0.006f,
                MinimumArcDepthMeters: 0.015f));
            Latest = _detector.Process(SensorTestData.Filtered(0, RestAcceleration), _buttonPressed);
        }

        public Vector3f RestAcceleration { get; }
        public List<RatingGestureAction> Actions { get; } = [];
        public HoldArcGestureResult Latest { get; private set; }

        public void HoldAtRest(bool expectReady = true)
        {
            Accelerate(RestAcceleration, 30);
            if (!expectReady) return;
            Assert.True(Latest.FaceDown);
            Assert.True(Latest.HoldProgress >= 1);
        }

        public void ParabolicArc(
            bool rightward,
            float horizontalAccelerationG = 0.30f,
            float verticalAccelerationG = 0.15f,
            int quarterFrames = 7,
            float gyroscopeZ = 0)
        {
            var horizontal = rightward ? horizontalAccelerationG : -horizontalAccelerationG;
            Accelerate(new Vector3f(horizontal, 0, RestAcceleration.Z + verticalAccelerationG), quarterFrames, gyroscopeZ);
            Accelerate(new Vector3f(horizontal, 0, RestAcceleration.Z - verticalAccelerationG), quarterFrames, gyroscopeZ);
            Accelerate(new Vector3f(-horizontal, 0, RestAcceleration.Z - verticalAccelerationG), quarterFrames, gyroscopeZ);
            Accelerate(new Vector3f(-horizontal, 0, RestAcceleration.Z + verticalAccelerationG), quarterFrames, gyroscopeZ);
        }

        public void HorizontalSwipe(bool rightward)
        {
            var horizontal = rightward ? 0.30f : -0.30f;
            Accelerate(new Vector3f(horizontal, 0, RestAcceleration.Z), 14);
            Accelerate(new Vector3f(-horizontal, 0, RestAcceleration.Z), 14);
        }

        public void Accelerate(Vector3f acceleration, int frames, float gyroscopeZ = 0)
        {
            for (var index = 0; index < frames; index++)
            {
                _timestampNanos += SamplePeriodNanos;
                Latest = _detector.Process(
                    SensorTestData.Filtered(
                        _timestampNanos,
                        acceleration,
                        new Vector3f(0, 0, gyroscopeZ)),
                    _buttonPressed);
                if (Latest.Action is RatingGestureAction action) Actions.Add(action);
            }
        }

        public void Release()
        {
            _buttonPressed = false;
            _timestampNanos += SamplePeriodNanos;
            Latest = _detector.Process(SensorTestData.Filtered(_timestampNanos, RestAcceleration), _buttonPressed);
        }
    }
}
