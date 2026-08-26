using TrikiMusicController_Windows.Services;

namespace TrikiMusicController.Windows.Tests;

public sealed class SystemVolumeServiceIntegrationTests
{
    [Fact]
    [Trait("Category", "HardwareIntegration")]
    public void VolumeStepChangesDefaultWindowsEndpointAndRestoresIt()
    {
        if (!string.Equals(Environment.GetEnvironmentVariable("TRIKI_RUN_AUDIO_INTEGRATION"), "1", StringComparison.Ordinal))
            return;

        var service = new SystemVolumeService();
        var initial = service.GetState();
        var increase = initial.Percent <= 98f;
        try
        {
            if (increase) service.StepUp();
            else service.StepDown();

            var changed = service.GetState();
            var expected = Math.Clamp(initial.Percent + (increase ? 2f : -2f), 0f, 100f);
            Assert.InRange(changed.Percent, expected - 0.05f, expected + 0.05f);
            if (increase) Assert.False(changed.Muted);
        }
        finally
        {
            service.SetPercent(initial.Percent);
            service.SetMute(initial.Muted);
        }
    }
}
