using TrikiMusicController_Windows.Core;
using TrikiMusicController_Windows.Models;

namespace TrikiMusicController.Windows.Tests;

public sealed class ConnectionActivityLeaseTests
{
    [Theory]
    [InlineData(-1f)]
    [InlineData(1f)]
    public void StationarySensorAndRealGestureDetectorParkOnceAfterTwelveSeconds(float gravityZ)
    {
        var lease = new ConnectionActivityLease();
        var filter = new SensorFilter();
        var detector = new FullRotationGestureDetector();
        var parks = new List<long>();
        for (var index = 0; index <= 1_500; index++)
        {
            var input = SensorTestData.Filtered(index * SensorTestData.SamplePeriodNanos, new Vector3f(0, 0, gravityZ));
            var filtered = filter.Process(input.Source, new CalibrationProfile());
            var gesture = detector.Process(filtered);
            if (lease.Observe(filtered, false, false, gesture.Phase)) parks.Add(input.Source.TimestampNanos);
        }
        Assert.Equal(new[] { ConnectionActivityLease.DefaultIdleTimeoutNanos }, parks);
    }

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
