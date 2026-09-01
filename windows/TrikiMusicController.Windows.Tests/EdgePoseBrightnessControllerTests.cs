using TrikiMusicController_Windows.Core;
using TrikiMusicController_Windows.Models;

namespace TrikiMusicController.Windows.Tests;

public sealed class EdgePoseBrightnessControllerTests
{
    [Fact]
    public void FlatPoseIsNotActive()
    {
        var controller = new EdgePoseBrightnessController(50f);
        var sample = SensorTestData.Filtered(
            1_000_000_000L,
            new Vector3f(0f, 0f, 1f),
            new Vector3f(0f, 0f, 0f));

        var result = controller.Process(sample);

        Assert.False(result.Active);
        Assert.False(result.Ready);
        Assert.Equal(50f, result.BrightnessPercent);
    }

    [Fact]
    public void EdgePoseStabilizesAfter400ms()
    {
        var controller = new EdgePoseBrightnessController(50f);
        var t0 = 1_000_000_000L;
        // Edge pose: Acc Z is 0, Acc Y is 1.0g
        var sample0 = SensorTestData.Filtered(
            t0,
            new Vector3f(0f, 1f, 0f),
            new Vector3f(0f, 0f, 0f));

        var res0 = controller.Process(sample0);
        Assert.True(res0.Active);
        Assert.False(res0.Ready);

        // After 200ms -> still stabilizing
        var sample200 = SensorTestData.Filtered(
            t0 + 200_000_000L,
            new Vector3f(0f, 1f, 0f),
            new Vector3f(0f, 0f, 0f));
        var res200 = controller.Process(sample200);
        Assert.True(res200.Active);
        Assert.False(res200.Ready);
        Assert.True(res200.StabilizationProgress >= 0.5f);

        // After 400ms -> Ready!
        var sample400 = SensorTestData.Filtered(
            t0 + 400_000_000L,
            new Vector3f(0f, 1f, 0f),
            new Vector3f(0f, 0f, 0f));
        var res400 = controller.Process(sample400);
        Assert.True(res400.Active);
        Assert.True(res400.Ready);
    }

    [Fact]
    public void RotationInEdgePoseAdjustsBrightness()
    {
        var controller = new EdgePoseBrightnessController(50f);
        var t0 = 1_000_000_000L;

        // Stabilize
        controller.Process(SensorTestData.Filtered(t0, new Vector3f(0f, 1f, 0f), new Vector3f(0f, 0f, 0f)));
        controller.Process(SensorTestData.Filtered(t0 + 400_000_000L, new Vector3f(0f, 1f, 0f), new Vector3f(0f, 0f, 0f)));

        // Rotate at +180 deg/s for 0.1s -> 18 degrees -> 5% brightness increase
        var res = controller.Process(SensorTestData.Filtered(
            t0 + 500_000_000L,
            new Vector3f(0f, 1f, 0f),
            new Vector3f(0f, 0f, 180f)));

        Assert.True(res.Active);
        Assert.True(res.Ready);
        Assert.True(res.BrightnessPercent >= 55f);
    }
}
