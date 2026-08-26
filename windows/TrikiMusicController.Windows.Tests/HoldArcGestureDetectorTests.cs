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
        Assert.True(right.Latest.EstimatedHorizontalDisplacementMeters >= 0.10f);
        Assert.True(right.Latest.EstimatedHorizontalDisplacementMeters <= 0.16f);
        Assert.True(left.Latest.EstimatedHorizontalDisplacementMeters <= -0.10f);
        Assert.True(left.Latest.EstimatedHorizontalDisplacementMeters >= -0.16f);
        Assert.True(right.Latest.EstimatedArcDepthMeters >= 0.020f);
    }

    [Fact]
    public void OneArc_EmitsOnlyOneActionAndRearmsAfterQuietStabilization()
    {
        var fixture = new Fixture();
        fixture.HoldAtRest();
        fixture.ParabolicArc(rightward: true);
        fixture.Accelerate(fixture.RestAcceleration, 30);
        fixture.ParabolicArc(rightward: false);

        Assert.Equal([RatingGestureAction.Like, RatingGestureAction.Dislike], fixture.Actions);
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
    public void ShortArcBelowTenCentimeters_IsIgnored()
    {
        var fixture = new Fixture();
        fixture.HoldAtRest();
        fixture.ParabolicArc(rightward: true, horizontalAccelerationG: 0.14f, quarterFrames: 4);

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
        fixture.ParabolicArc(rightward: true, quarterFrames: 3);

        Assert.Empty(fixture.Actions);
        Assert.Equal(HoldGesturePhase.Holding, fixture.Latest.Phase);
    }

    [Fact]
    public void StrongRotation_InvalidatesArcAndRearmsAfterQuietPeriod()
    {
        var fixture = new Fixture();
        fixture.HoldAtRest();
        fixture.ParabolicArc(rightward: true, gyroscopeZ: 180);

        Assert.Empty(fixture.Actions);
        Assert.Equal(HoldGesturePhase.Rearming, fixture.Latest.Phase);

        fixture.Accelerate(fixture.RestAcceleration, 30);
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
    public void RatingGesture_DoesNotNeedButtonSignal()
    {
        var fixture = new Fixture();
        fixture.HoldAtRest();
        fixture.ParabolicArc(rightward: true);

        Assert.Equal([RatingGestureAction.Like], fixture.Actions);
    }

    private sealed class Fixture
    {
        private const long SamplePeriodNanos = 20_000_000;
        private readonly HoldArcGestureDetector _detector;
        private long _timestampNanos;

        public Fixture(
            HoldGestureConfiguration? configuration = null,
            Vector3f? restAcceleration = null)
        {
            RestAcceleration = restAcceleration ?? new Vector3f(0, 0, 1);
            _detector = new HoldArcGestureDetector(configuration ?? new HoldGestureConfiguration(
                HoldMillis: 400,
                MotionStartAccelerationG: 0.10f,
                AccelerationDeadZoneG: 0.03f,
                VerticalAccelerationDeadZoneG: 0.02f,
                LinearAccelerationSmoothingAlpha: 1,
                MinimumArcImpulseEachDirectionGSeconds: 0.006f,
                MinimumArcDepthMeters: 0.015f));
            Latest = _detector.Process(SensorTestData.Filtered(0, RestAcceleration));
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
            float horizontalAccelerationG = 0.35f,
            float verticalAccelerationG = 0.23f,
            int quarterFrames = 5,
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
                        new Vector3f(0, 0, gyroscopeZ)));
                if (Latest.Action is RatingGestureAction action) Actions.Add(action);
            }
        }
    }
}
