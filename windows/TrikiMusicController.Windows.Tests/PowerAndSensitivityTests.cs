using TrikiMusicController_Windows.Core;
using TrikiMusicController_Windows.Models;
using TrikiMusicController_Windows.Services;

namespace TrikiMusicController.Windows.Tests;

public sealed class PowerAndSensitivityTests
{
    [Theory]
    [InlineData(ConnectionPowerMode.Eco2, 2)]
    [InlineData(ConnectionPowerMode.Eco5, 5)]
    [InlineData(ConnectionPowerMode.Eco10, 10)]
    [InlineData(ConnectionPowerMode.Normal, 30)]
    public void EveryPowerModeParksAtItsOwnBoundary(ConnectionPowerMode mode, int minutes)
    {
        var timeout = minutes * 60_000_000_000L;
        Assert.Equal(timeout, mode.IdleTimeoutNanos());
        var lease = new ConnectionActivityLease(mode.IdleTimeoutNanos());
        Assert.False(lease.Observe(0, false));
        Assert.False(lease.Observe(timeout - 1, false));
        Assert.True(lease.Observe(timeout, false));
        Assert.False(lease.Observe(timeout + 1, false));
    }

    [Fact]
    public void SwitchingTimeoutStartsAFreshIdleWindowAndAlwaysOnDisablesParking()
    {
        var lease = new ConnectionActivityLease(120);
        Assert.False(lease.Observe(0, false));
        lease.ConfigureTimeout(1800);
        Assert.False(lease.Observe(150, false));
        Assert.False(lease.Observe(1949, false));
        Assert.True(lease.Observe(1950, false));
        Assert.False(new AppSettings { ConnectionPowerMode = ConnectionPowerMode.AlwaysOn }.ConnectOnlyWhenNeeded);
        Assert.Equal(ConnectionPowerMode.Normal, new AppSettings().ConnectionPowerMode);
    }

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public async Task OldSettingsReceiveNormalWithoutLosingMappings(bool oldEco)
    {
        var directory = Path.Combine(Path.GetTempPath(), "TrikiPower-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        try
        {
            await File.WriteAllTextAsync(Path.Combine(directory, "settings.json"),
                $$"""{"connectOnlyWhenNeeded":{{oldEco.ToString().ToLowerInvariant()}},"doubleClickAction":"Next","knownDeviceAddressHex":"AABBCCDDEEFF","knownDeviceName":"Triki test","startWithWindows":true} """);
            var service = new SettingsService(directory);
            await service.LoadAsync();
            Assert.Equal(ConnectionPowerMode.Normal, service.Current.ConnectionPowerMode);
            Assert.True(service.Current.ConnectOnlyWhenNeeded);
            Assert.Equal(MediaAction.Next, service.Current.DoubleClickAction);
            Assert.Equal("Triki test", service.Current.KnownDeviceName);
            Assert.True(service.Current.StartWithWindows);
        }
        finally { Directory.Delete(directory, true); }
    }

    [Fact]
    public void InvalidSettingsRestoreSafeSensitivityAndPowerDefaults()
    {
        var settings = SettingsService.Normalize(new AppSettings { VolumeSensitivityPercent = 0, BrightnessSensitivityPercent = 900,
            ConnectionPowerMode = (ConnectionPowerMode)900 });
        Assert.Equal(100, settings.VolumeSensitivityPercent);
        Assert.Equal(100, settings.BrightnessSensitivityPercent);
        Assert.Equal(ConnectionPowerMode.Normal, settings.ConnectionPowerMode);
    }

    [Fact]
    public void IndependentSensitivityChangesReactionWithoutAmplifyingNoise()
    {
        Assert.True(CountVolumeSteps(0.5f, 60) < CountVolumeSteps(1f, 60));
        Assert.True(CountVolumeSteps(1f, 60) < CountVolumeSteps(2f, 60));
        Assert.Equal(0, CountVolumeSteps(2f, 10));
        var low = BrightnessChange(0.5f);
        var normal = BrightnessChange(1f);
        var high = BrightnessChange(2f);
        Assert.True(low < normal && normal < high);
    }

    [Theory]
    [InlineData(float.NaN)]
    [InlineData(float.PositiveInfinity)]
    [InlineData(0.49f)]
    [InlineData(2.01f)]
    public void ControllersRejectInvalidSensitivity(float invalid)
    {
        Assert.Throws<ArgumentOutOfRangeException>(() => new GyroscopeVolumeController().Sensitivity = invalid);
        Assert.Throws<ArgumentOutOfRangeException>(() => new EdgePoseBrightnessController().Sensitivity = invalid);
    }

    private static int CountVolumeSteps(float sensitivity, float speed)
    {
        var controller = new GyroscopeVolumeController(new VolumeControllerConfiguration(TiltStabilizationMillis: 0)) { Sensitivity = sensitivity };
        var count = 0;
        for (var index = 0; index < 150; index++)
            if (controller.Process(SensorTestData.Filtered(index * SensorTestData.SamplePeriodNanos, new Vector3f(0, 0, -1), new Vector3f(0, 0, speed))).Action is MediaAction.VolumeUp) count++;
        return count;
    }

    private static float BrightnessChange(float sensitivity)
    {
        var controller = new EdgePoseBrightnessController(50) { Sensitivity = sensitivity };
        BrightnessControlResult? result = null;
        for (var index = 0; index < 100; index++)
            result = controller.Process(SensorTestData.Filtered(index * SensorTestData.SamplePeriodNanos,
                new Vector3f(0, 1, 0), new Vector3f(0, 0, 20)), true);
        return result!.BrightnessPercent - 50;
    }
}
