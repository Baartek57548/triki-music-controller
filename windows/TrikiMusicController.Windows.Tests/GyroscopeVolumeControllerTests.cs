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

    private static GyroscopeVolumeController CreateController() => new(new VolumeControllerConfiguration(
        MaximumTiltDegrees: 25,
        TiltStabilizationMillis: 2_000,
        ActivationGyroscopeDps: 18,
        ReleaseGyroscopeDps: 10,
        DegreesPerVolumeStep: 15,
        GyroscopeSmoothingAlpha: 0.22f,
        MinimumStepIntervalMillis: 100));

    private static FilteredSensorData Sample(long milliseconds, float accelerometerZ, float gyroscopeZ) =>
        SensorTestData.Filtered(
            milliseconds * 1_000_000,
            new Vector3f(0, 0, accelerometerZ),
            new Vector3f(0, 0, gyroscopeZ));
}
