using TrikiMusicController_Windows.Core;

namespace TrikiMusicController.Windows.Tests;

public sealed class WakeAdvertisementGateTests
{
    [Fact]
    public void ContinuousAdvertisementsNeverLookLikeNewWake()
    {
        var gate = new WakeAdvertisementGate(requiredSilenceNanos: 5_000);
        gate.Reset(0);

        Assert.False(gate.ObserveAdvertisement(1_000));
        Assert.False(gate.ObserveAdvertisement(4_500));
        Assert.False(gate.ObserveAdvertisement(9_000));
    }

    [Fact]
    public void FirstAdvertisementAfterSilenceStartsConnection()
    {
        var gate = new WakeAdvertisementGate(requiredSilenceNanos: 5_000);
        gate.Reset(1_000);

        Assert.True(gate.ObserveAdvertisement(6_000));
    }

    [Fact]
    public void TimerCanArmGateBeforeNextAdvertisement()
    {
        var gate = new WakeAdvertisementGate(requiredSilenceNanos: 5_000);
        gate.Reset(2_000);

        Assert.False(gate.TryArm(6_999));
        Assert.True(gate.TryArm(7_000));
        Assert.True(gate.IsArmed);
        Assert.True(gate.ObserveAdvertisement(7_100));
    }

    [Fact]
    public void TimestampRollbackSafelyRestartsSilenceWindow()
    {
        var gate = new WakeAdvertisementGate(requiredSilenceNanos: 5_000);
        gate.Reset(10_000);

        Assert.False(gate.ObserveAdvertisement(2_000));
        Assert.False(gate.TryArm(6_999));
        Assert.True(gate.TryArm(7_000));
    }
}
