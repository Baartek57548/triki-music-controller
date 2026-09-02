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
    public void EdgePoseStabilizesAfter150ms()
    {
        var controller = new EdgePoseBrightnessController(50f);
        var t0 = 1_000_000_000L;
        // Edge pose: Acc Z is 0, Acc Y is 1.0g
        var sample0 = SensorTestData.Filtered(
            t0,
            new Vector3f(0f, 1f, 0f),
            new Vector3f(0f, 0f, 0f));

        var res0 = controller.Process(sample0, isButtonPressed: false);
        Assert.True(res0.Active);
        Assert.False(res0.Ready);

        // After 80ms -> still stabilizing
        var sample80 = SensorTestData.Filtered(
            t0 + 80_000_000L,
            new Vector3f(0f, 1f, 0f),
            new Vector3f(0f, 0f, 0f));
        var res80 = controller.Process(sample80, isButtonPressed: false);
        Assert.True(res80.Active);
        Assert.False(res80.Ready);
        Assert.True(res80.StabilizationProgress >= 0.5f);

        // After 150ms -> Ready!
        var sample150 = SensorTestData.Filtered(
            t0 + 150_000_000L,
            new Vector3f(0f, 1f, 0f),
            new Vector3f(0f, 0f, 0f));
        var res150 = controller.Process(sample150, isButtonPressed: true);
        Assert.True(res150.Active);
        Assert.True(res150.Ready);
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

    [Fact]
    public void ButtonMustBeHeldToAdjustBrightness()
    {
        var controller = new EdgePoseBrightnessController(50f);
        var t0 = 1_000_000_000L;

        // Stabilize
        controller.Process(SensorTestData.Filtered(t0, new Vector3f(0f, 1f, 0f), new Vector3f(0f, 0f, 0f)));
        controller.Process(SensorTestData.Filtered(t0 + 400_000_000L, new Vector3f(0f, 1f, 0f), new Vector3f(0f, 0f, 0f)));

        // Rotate at +180 deg/s without pressing button -> brightness should NOT change
        var resWithoutButton = controller.Process(SensorTestData.Filtered(
            t0 + 500_000_000L,
            new Vector3f(0f, 1f, 0f),
            new Vector3f(0f, 0f, 180f)), isButtonPressed: false);

        Assert.True(resWithoutButton.Active);
        Assert.False(resWithoutButton.Ready);
        Assert.Equal(50f, resWithoutButton.BrightnessPercent);
        Assert.Equal(0f, resWithoutButton.DeltaPercent);

        // Rotate with button pressed -> brightness changes
        var resWithButton = controller.Process(SensorTestData.Filtered(
            t0 + 600_000_000L,
            new Vector3f(0f, 1f, 0f),
            new Vector3f(0f, 0f, 180f)), isButtonPressed: true);

        Assert.True(resWithButton.Active);
        Assert.True(resWithButton.Ready);
        Assert.True(resWithButton.BrightnessPercent > 50f);
    }
}
