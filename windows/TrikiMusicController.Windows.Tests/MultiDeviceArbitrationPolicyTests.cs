using TrikiMusicController_Windows.Core;
using TrikiMusicController_Windows.Models;

namespace TrikiMusicController.Windows.Tests;

public sealed class MultiDeviceArbitrationPolicyTests
{
    [Fact]
    public void ActiveMediaPlaybackHasZeroDelayAndHighestPriority()
    {
        var now = DateTimeOffset.UtcNow;
        var delay = MultiDeviceArbitrationPolicy.CalculateConnectionDelay(
            MultiDeviceArbitrationMode.MediaPriority,
            isMediaPlaying: true,
            lastPlaybackTime: null,
            isUserActiveOrForeground: false,
            now: now);

        Assert.Equal(TimeSpan.Zero, delay);
    }

    [Fact]
    public void ForegroundOrActiveUserHasNearInstantDelayWhenNotPlaying()
    {
        var now = DateTimeOffset.UtcNow;
        var delay = MultiDeviceArbitrationPolicy.CalculateConnectionDelay(
            MultiDeviceArbitrationMode.MediaPriority,
            isMediaPlaying: false,
            lastPlaybackTime: null,
            isUserActiveOrForeground: true,
            now: now);

        Assert.Equal(TimeSpan.FromMilliseconds(100), delay);
    }

    [Fact]
    public void RecentPlaybackWithin3MinutesHasShortDelay()
    {
        var now = DateTimeOffset.UtcNow;
        var lastPlayback = now.AddMinutes(-2);
        var delay = MultiDeviceArbitrationPolicy.CalculateConnectionDelay(
            MultiDeviceArbitrationMode.MediaPriority,
            isMediaPlaying: false,
            lastPlaybackTime: lastPlayback,
            isUserActiveOrForeground: false,
            now: now);

        Assert.Equal(TimeSpan.FromMilliseconds(200), delay);
    }

    [Fact]
    public void StalePlaybackWithin10MinutesHasMediumDelay()
    {
        var now = DateTimeOffset.UtcNow;
        var lastPlayback = now.AddMinutes(-6);
        var delay = MultiDeviceArbitrationPolicy.CalculateConnectionDelay(
            MultiDeviceArbitrationMode.MediaPriority,
            isMediaPlaying: false,
            lastPlaybackTime: lastPlayback,
            isUserActiveOrForeground: false,
            now: now);

        Assert.Equal(TimeSpan.FromMilliseconds(500), delay);
    }

    [Fact]
    public void IdleDeviceWithNoRecentPlaybackYieldsWith1SecondDelay()
    {
        var now = DateTimeOffset.UtcNow;
        var lastPlayback = now.AddHours(-1);
        var delay = MultiDeviceArbitrationPolicy.CalculateConnectionDelay(
            MultiDeviceArbitrationMode.MediaPriority,
            isMediaPlaying: false,
            lastPlaybackTime: lastPlayback,
            isUserActiveOrForeground: false,
            now: now);

        Assert.Equal(TimeSpan.FromMilliseconds(1000), delay);
    }

    [Fact]
    public void AlwaysConnectModeAlwaysHasZeroDelay()
    {
        var now = DateTimeOffset.UtcNow;
        var delay = MultiDeviceArbitrationPolicy.CalculateConnectionDelay(
            MultiDeviceArbitrationMode.AlwaysConnect,
            isMediaPlaying: false,
            lastPlaybackTime: null,
            isUserActiveOrForeground: false,
            now: now);

        Assert.Equal(TimeSpan.Zero, delay);
    }

    [Fact]
    public void OnlyWhenPlayingModeBlocksAttemptWhenNotPlayingAndInactive()
    {
        var shouldAttempt = MultiDeviceArbitrationPolicy.ShouldAttemptConnection(
            MultiDeviceArbitrationMode.OnlyWhenPlaying,
            isMediaPlaying: false,
            isUserActiveOrForeground: false);

        Assert.False(shouldAttempt);
    }

    [Fact]
    public void OnlyWhenPlayingModeAllowsAttemptWhenPlaying()
    {
        var shouldAttempt = MultiDeviceArbitrationPolicy.ShouldAttemptConnection(
            MultiDeviceArbitrationMode.OnlyWhenPlaying,
            isMediaPlaying: true,
            isUserActiveOrForeground: false);

        Assert.True(shouldAttempt);
    }

    [Fact]
    public void YieldsConnectionAfter10SecondsOfInactivityWithoutMedia()
    {
        var shouldYieldShort = MultiDeviceArbitrationPolicy.ShouldYieldConnection(
            MultiDeviceArbitrationMode.MediaPriority,
            isMediaPlaying: false,
            connectedDurationWithoutMedia: TimeSpan.FromSeconds(5));
        Assert.False(shouldYieldShort);

        var shouldYieldLong = MultiDeviceArbitrationPolicy.ShouldYieldConnection(
            MultiDeviceArbitrationMode.MediaPriority,
            isMediaPlaying: false,
            connectedDurationWithoutMedia: TimeSpan.FromSeconds(11));
        Assert.True(shouldYieldLong);

        var shouldYieldWhilePlaying = MultiDeviceArbitrationPolicy.ShouldYieldConnection(
            MultiDeviceArbitrationMode.MediaPriority,
            isMediaPlaying: true,
            connectedDurationWithoutMedia: TimeSpan.FromSeconds(20));
        Assert.False(shouldYieldWhilePlaying);
    }
}
