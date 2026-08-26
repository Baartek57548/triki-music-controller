using TrikiMusicController_Windows.Core;
using TrikiMusicController_Windows.Models;

namespace TrikiMusicController.Windows.Tests;

public sealed class FullRotationGestureDetectorTests
{
    [Fact]
    public void RightFullRotationTriggersNextDirection()
    {
        var fixture = new Fixture();
        fixture.Stabilize();
        fixture.Rotate(positive: true);

        Assert.Equal([RotationGestureDirection.Right], fixture.Triggers);
        Assert.True(fixture.Latest.FaceDown);
        Assert.True(fixture.Latest.EstimatedRotationDegrees >= 330);
    }

    [Fact]
    public void LeftFullRotationTriggersPreviousDirection()
    {
        var fixture = new Fixture();
        fixture.Stabilize();
        fixture.Rotate(positive: false);

        Assert.Equal([RotationGestureDirection.Left], fixture.Triggers);
    }

    [Fact]
    public void PartialTurnIsNotEnough()
    {
        var fixture = new Fixture();
        fixture.Stabilize();
        fixture.Rotate(positive: true, frames: 100);

        Assert.Empty(fixture.Triggers);
        Assert.InRange(fixture.Latest.EstimatedRotationDegrees, -329.99f, 329.99f);
    }

    [Fact]
    public void FaceUpCapsuleCannotTriggerNavigationRotation()
    {
        var fixture = new Fixture(new Vector3f(0, 0, -1));
        fixture.Stabilize();
        fixture.Rotate(positive: true);

        Assert.Empty(fixture.Triggers);
        Assert.False(fixture.Latest.FaceDown);
        Assert.Equal(HoldGesturePhase.Holding, fixture.Latest.Phase);
    }

    [Fact]
    public void DirectionReversalStartsFreshRotation()
    {
        var fixture = new Fixture();
        fixture.Stabilize();
        fixture.Rotate(positive: true, frames: 60);
        fixture.Rotate(positive: false, frames: 60);

        Assert.Empty(fixture.Triggers);
        Assert.Equal(HoldGesturePhase.Tracking, fixture.Latest.Phase);
    }

    [Fact]
    public void TriggerRearmsAfterQuietStabilization()
    {
        var fixture = new Fixture();
        fixture.Stabilize();
        fixture.Rotate(positive: true);
        fixture.Quiet(20);
        fixture.Stabilize();
        fixture.Rotate(positive: false);

        Assert.Equal([RotationGestureDirection.Right, RotationGestureDirection.Left], fixture.Triggers);
    }

    private sealed class Fixture
    {
        private const long SamplePeriodNanos = 20_000_000;
        private readonly FullRotationGestureDetector _detector = new(new FullRotationGestureConfiguration(
            StabilizationMillis: 200,
            RequiredRotationDegrees: 330,
            MaximumRotationDegrees: 500,
            MaximumRotationMillis: 4_000,
            ActivationGyroscopeDps: 18,
            ReleaseGyroscopeDps: 8,
            GyroscopeSmoothingAlpha: 1,
            RearmQuietMillis: 100));
        private readonly Vector3f _rest;
        private long _timestampNanos;

        public Fixture(Vector3f? rest = null)
        {
            _rest = rest ?? new Vector3f(0, 0, 1);
            Latest = _detector.Process(SensorTestData.Filtered(0, _rest));
        }

        public List<RotationGestureDirection> Triggers { get; } = [];
        public FullRotationGestureResult Latest { get; private set; }

        public void Stabilize() => Feed(_rest, 12);

        public void Quiet(int frames) => Feed(_rest, frames);

        public void Rotate(bool positive, int frames = 150)
        {
            var gyroscopeZ = positive ? 130f : -130f;
            Feed(_rest, 1, gyroscopeZ);
            Feed(_rest, frames, gyroscopeZ);
        }

        private void Feed(Vector3f acceleration, int frames, float gyroscopeZ = 0)
        {
            for (var index = 0; index < frames; index++)
            {
                _timestampNanos += SamplePeriodNanos;
                Latest = _detector.Process(SensorTestData.Filtered(
                    _timestampNanos,
                    acceleration,
                    new Vector3f(0, 0, gyroscopeZ)));
                if (Latest.Triggered && Latest.Direction is RotationGestureDirection direction)
                    Triggers.Add(direction);
            }
        }
    }
}
