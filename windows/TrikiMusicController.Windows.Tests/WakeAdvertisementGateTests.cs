using TrikiMusicController_Windows.Core;

namespace TrikiMusicController.Windows.Tests;

public sealed class WakeAdvertisementGateTests
{
    [Fact]
    public void ContinuousAdvertisementsRespectInitialParkingDelay()
    {
        var gate = new WakeAdvertisementGate(requiredSilenceNanos: 5_000);
        gate.Reset(0);

        Assert.False(gate.ObserveAdvertisement(1_000));
        Assert.False(gate.ObserveAdvertisement(4_500));
        Assert.False(gate.ObserveAdvertisement(9_000));
    }

    [Fact]
    public void ContinuousAdvertisementsCannotBlockReconnectBeyondFifteenSeconds()
    {
        var gate = new WakeAdvertisementGate();
        gate.Reset(0);
        for (long now = 1_000_000_000; now < 15_000_000_000; now += 1_000_000_000)
            Assert.False(gate.ObserveAdvertisement(now));
        Assert.True(gate.ObserveAdvertisement(15_000_000_000));
    }

    [Fact]
    public void TimerArmsAfterMaximumWaitEvenWithContinuousAdvertisements()
    {
        var gate = new WakeAdvertisementGate();
        gate.Reset(0);
        for (long now = 1_000_000_000; now < 15_000_000_000; now += 1_000_000_000)
        {
            Assert.False(gate.ObserveAdvertisement(now));
            Assert.False(gate.TryArm(now));
        }
        Assert.True(gate.TryArm(15_000_000_000));
        Assert.True(gate.ObserveAdvertisement(15_100_000_000));
    }

    [Fact]
    public void StartupAndUnexpectedDisconnectAcceptTheFirstAdvertisement()
    {
        var gate = new WakeAdvertisementGate();
        gate.Reset(1_000, waitForSilence: false);
        Assert.True(gate.IsArmed);
        Assert.True(gate.ObserveAdvertisement(1_001));
        gate.Reset(2_000, waitForSilence: true);
        Assert.False(gate.IsArmed);
        Assert.False(gate.ObserveAdvertisement(2_001));
    }

    [Fact]
    public void ClockRollbackRestartsTheMaximumWaitAndResetDropsOldDeadline()
    {
        var gate = new WakeAdvertisementGate(requiredSilenceNanos: 5_000, maximumWaitNanos: 15_000);
        gate.Reset(10_000);
        Assert.False(gate.ObserveAdvertisement(2_000));
        for (long now = 3_000; now < 17_000; now += 1_000)
            Assert.False(gate.ObserveAdvertisement(now));
        Assert.True(gate.ObserveAdvertisement(17_000));
        gate.Reset(18_000);
        Assert.False(gate.ObserveAdvertisement(18_001));
    }

    [Fact]
    public void MaximumWaitCannotBeShorterThanSilenceWindow() =>
        Assert.Throws<ArgumentOutOfRangeException>(() => new WakeAdvertisementGate(5_000, 4_999));

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
