using TrikiMusicController_Windows.Core;
using TrikiMusicController_Windows.Models;

namespace TrikiMusicController.Windows.Tests;

public sealed class GyroscopeVolumeControllerTests
{
    [Fact]
    public void FlatPosition_MustRemainInRangeForTwoSeconds()
    {
        var controller = CreateController();
        var result = controller.Process(Sample(0, -1, 0));

        for (var milliseconds = 20; milliseconds < 2_000; milliseconds += 20)
            result = controller.Process(Sample(milliseconds, -1, 0));

        Assert.False(result.TiltStable);
        Assert.InRange(result.StabilizationProgress, 0.98f, 1f);

        result = controller.Process(Sample(2_000, -1, 0));
        Assert.True(result.TiltStable);
        Assert.True(result.Active);
    }

    [Theory]
    [InlineData(25, true)]
    [InlineData(26, false)]
    public void Stabilization_IsAllowedOnlyUpToTwentyFiveDegrees(float degrees, bool expectedInRange)
    {
        var radians = degrees * MathF.PI / 180f;
        var accelerometer = new Vector3f(MathF.Sin(radians), 0, -MathF.Cos(radians));
        var controller = CreateController();

        var result = controller.Process(SensorTestData.Filtered(0, accelerometer));

        Assert.Equal(expectedInRange, result.WithinTiltRange);
        Assert.False(result.TiltStable);
    }

    [Theory]
    [InlineData(60, MediaAction.VolumeUp)]
    [InlineData(-60, MediaAction.VolumeDown)]
    public void GyroscopeZ_AfterStabilization_ControlsVolumeSmoothly(float gyroscopeZ, MediaAction expectedAction)
    {
        var controller = CreateController();
        for (var milliseconds = 0; milliseconds <= 2_020; milliseconds += 20)
            controller.Process(Sample(milliseconds, -1, 0));

        MediaAction? emitted = null;
        for (var milliseconds = 2_040; milliseconds <= 2_600 && emitted is null; milliseconds += 20)
            emitted = controller.Process(Sample(milliseconds, -1, gyroscopeZ)).Action;

        Assert.Equal(expectedAction, emitted);
    }

    [Theory]
    [InlineData(-0.8f)]
    [InlineData(-1.2f)]
    public void AccelerationWithinTwentyPercent_AllowsInAirControl(float accelerometerZ)
    {
        var controller = CreateController();
        for (var milliseconds = 0; milliseconds <= 2_020; milliseconds += 20)
            controller.Process(Sample(milliseconds, accelerometerZ, 0));

        var result = controller.Process(Sample(2_040, accelerometerZ, 60));

        Assert.True(result.AccelerationStable);
        Assert.True(result.Active);
    }

    [Fact]
    public void SuddenAcceleration_BlocksVolumeAndRestartsFullStabilization()
    {
        var controller = CreateController();
        for (var milliseconds = 0; milliseconds <= 2_020; milliseconds += 20)
            controller.Process(Sample(milliseconds, -1, 0));

        controller.Process(Sample(2_040, -1, 600));
        var suddenMovement = controller.Process(Sample(2_060, -1.21f, 600));
        var recoveryStart = controller.Process(Sample(2_080, -1, 600));
        VolumeControlResult? beforeStable = null;
        for (var milliseconds = 2_100; milliseconds < 4_080; milliseconds += 20)
            beforeStable = controller.Process(Sample(milliseconds, -1, 600));
        var stableAgain = controller.Process(Sample(4_080, -1, 600));

        Assert.False(suddenMovement.AccelerationStable);
        Assert.False(suddenMovement.Active);
        Assert.Null(suddenMovement.Action);
        Assert.Equal(0, suddenMovement.StabilizationProgress);
        Assert.False(recoveryStart.Active);
        Assert.Equal(0, recoveryStart.StabilizationProgress);
        Assert.NotNull(beforeStable);
        Assert.False(beforeStable.Active);
        Assert.True(stableAgain.Active);
        Assert.Null(stableAgain.Action);
    }

    [Fact]
    public void DefaultVolumeResponse_IsGentleForModerateRotation()
    {
        var controller = CreateController();
        for (var milliseconds = 0; milliseconds <= 2_020; milliseconds += 20)
            controller.Process(Sample(milliseconds, -1, 0));

        var earlyActions = new List<MediaAction>();
        for (var milliseconds = 2_040; milliseconds <= 2_340; milliseconds += 20)
        {
            if (controller.Process(Sample(milliseconds, -1, 60)).Action is MediaAction action)
                earlyActions.Add(action);
        }

        MediaAction? laterAction = null;
        for (var milliseconds = 2_360; milliseconds <= 3_200 && laterAction is null; milliseconds += 20)
            laterAction = controller.Process(Sample(milliseconds, -1, 60)).Action;

        Assert.Empty(earlyActions);
        Assert.Equal(MediaAction.VolumeUp, laterAction);
    }

    private static GyroscopeVolumeController CreateController() => new(new VolumeControllerConfiguration(
        MaximumTiltDegrees: 25,
        TiltStabilizationMillis: 2_000,
        MaximumAccelerationDeviationG: 0.20f,
        ActivationGyroscopeDps: 22,
        ReleaseGyroscopeDps: 12,
        DegreesPerVolumeStep: 22,
        GyroscopeSmoothingAlpha: 0.16f,
        MinimumStepIntervalMillis: 140));

    private static FilteredSensorData Sample(long milliseconds, float accelerometerZ, float gyroscopeZ) =>
        SensorTestData.Filtered(
            milliseconds * 1_000_000,
            new Vector3f(0, 0, accelerometerZ),
            new Vector3f(0, 0, gyroscopeZ));
}
