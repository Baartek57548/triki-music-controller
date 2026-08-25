using TrikiMusicController_Windows.Core;
using TrikiMusicController_Windows.Models;

namespace TrikiMusicController.Windows.Tests;

public sealed class HoldVerticalGestureDetectorTests
{
    [Fact]
    public void LiftWhileHeld_EmitsLikeExactlyOnce()
    {
        var fixture = new Fixture();
        fixture.HoldAtRest();

        fixture.Accelerate(-0.6f, 13);
        fixture.Accelerate(-1.4f, 13);
        fixture.Accelerate(-1f, 10);

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
    public void MovementWithoutPressedButton_IsIgnored()
    {
        var fixture = new Fixture(buttonPressed: false);
        fixture.Accelerate(-1.5f, 40);

        Assert.Empty(fixture.Actions);
        Assert.Equal(HoldGesturePhase.Idle, fixture.Latest.Phase);
    }

    private sealed class Fixture
    {
        private readonly HoldVerticalGestureDetector _detector = new(new HoldGestureConfiguration(
            HoldMillis: 400,
            TriggerDisplacementMeters: 0.20f,
            MotionStartAccelerationG: 0.10f,
            AccelerationDeadZoneG: 0.03f,
            LinearAccelerationSmoothingAlpha: 1f));
        private bool _buttonPressed;
        private long _timestampNanos;

        public Fixture(bool buttonPressed = true)
        {
            _buttonPressed = buttonPressed;
            Latest = _detector.Process(SensorTestData.Filtered(0, new Vector3f(0, 0, -1)), _buttonPressed);
        }

        public List<RatingGestureAction> Actions { get; } = [];
        public HoldVerticalGestureResult Latest { get; private set; }

        public void HoldAtRest()
        {
            Accelerate(-1f, 25);
            Assert.Equal(1f, Latest.HoldProgress);
        }

        public void Accelerate(float z, int frames)
        {
            for (var frame = 0; frame < frames; frame++)
            {
                _timestampNanos += SensorTestData.SamplePeriodNanos;
                Latest = _detector.Process(
                    SensorTestData.Filtered(_timestampNanos, new Vector3f(0, 0, z), status: 1),
                    _buttonPressed);
                if (Latest.Action is { } action) Actions.Add(action);
            }
        }

        public void Release()
        {
            _buttonPressed = false;
            _timestampNanos += SensorTestData.SamplePeriodNanos;
            Latest = _detector.Process(
                SensorTestData.Filtered(_timestampNanos, new Vector3f(0, 0, -1)),
                _buttonPressed);
        }
    }
}
