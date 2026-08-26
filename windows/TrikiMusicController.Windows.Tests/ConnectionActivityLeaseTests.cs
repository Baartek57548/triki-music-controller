using TrikiMusicController_Windows.Core;

namespace TrikiMusicController.Windows.Tests;

public sealed class ConnectionActivityLeaseTests
{
    [Fact]
    public void ParksExactlyOnceAfterIdleTimeout()
    {
        var lease = new ConnectionActivityLease(idleTimeoutNanos: 12_000);

        Assert.False(lease.Observe(1_000, active: false));
        Assert.False(lease.Observe(12_999, active: false));
        Assert.True(lease.Observe(13_000, active: false));
        Assert.False(lease.Observe(20_000, active: false));
    }

    [Fact]
    public void ActivityRenewsTheConnectionLease()
    {
        var lease = new ConnectionActivityLease(idleTimeoutNanos: 12_000);

        Assert.False(lease.Observe(1_000, active: false));
        Assert.False(lease.Observe(10_000, active: true));
        Assert.False(lease.Observe(21_999, active: false));
        Assert.True(lease.Observe(22_000, active: false));
    }

    [Fact]
    public void ResetAndTimestampRollbackRequireFreshIdleWindow()
    {
        var lease = new ConnectionActivityLease(idleTimeoutNanos: 12_000);

        Assert.False(lease.Observe(20_000, active: false));
        Assert.False(lease.Observe(5_000, active: false));
        Assert.False(lease.Observe(16_999, active: false));
        Assert.True(lease.Observe(17_000, active: false));
        lease.Reset();
        Assert.False(lease.Observe(50_000, active: false));
    }
}
