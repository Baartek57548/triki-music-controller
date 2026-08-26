using TrikiMusicController_Windows.Core;
using TrikiMusicController_Windows.Models;

namespace TrikiMusicController.Windows.Tests;

public sealed class SensorFilterTests
{
    [Fact]
    public void ExtremeCalibrationAnglesRemainFiniteAndBounded()
    {
        var calibration = new CalibrationProfile(NeutralPitch: float.MaxValue, NeutralRoll: -float.MaxValue);
        var sample = SensorTestData.Filtered(0, new Vector3f(0, 0, -1)).Source;

        var result = new SensorFilter(1f).Process(sample, calibration);

        Assert.True(float.IsFinite(result.Orientation.Pitch));
        Assert.True(float.IsFinite(result.Orientation.Roll));
        Assert.InRange(result.Orientation.Pitch, -180f, 180f);
        Assert.InRange(result.Orientation.Roll, -180f, 180f);
    }
}
