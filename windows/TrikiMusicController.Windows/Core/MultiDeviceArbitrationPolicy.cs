using TrikiMusicController_Windows.Models;

namespace TrikiMusicController_Windows.Core;

public static class MultiDeviceArbitrationPolicy
{
    public static readonly TimeSpan RecentPlaybackWindow = TimeSpan.FromMinutes(3);
    public static readonly TimeSpan IdlePlaybackWindow = TimeSpan.FromMinutes(10);

    public static readonly TimeSpan ActivePlaybackDelay = TimeSpan.Zero;
    public static readonly TimeSpan ForegroundOrActiveUserDelay = TimeSpan.FromMilliseconds(100);
    public static readonly TimeSpan RecentPlaybackDelay = TimeSpan.FromMilliseconds(200);
    public static readonly TimeSpan StalePlaybackDelay = TimeSpan.FromMilliseconds(500);
    public static readonly TimeSpan IdleYieldDelay = TimeSpan.FromMilliseconds(1000);

    public static bool ShouldAttemptConnection(
        MultiDeviceArbitrationMode mode,
        bool isMediaPlaying,
        bool isUserActiveOrForeground) =>
        mode switch
        {
            MultiDeviceArbitrationMode.OnlyWhenPlaying => isMediaPlaying || isUserActiveOrForeground,
            MultiDeviceArbitrationMode.AlwaysConnect => true,
            MultiDeviceArbitrationMode.MediaPriority => true,
            _ => true,
        };

    public static TimeSpan CalculateConnectionDelay(
        MultiDeviceArbitrationMode mode,
        bool isMediaPlaying,
        DateTimeOffset? lastPlaybackTime,
        bool isUserActiveOrForeground,
        DateTimeOffset now)
    {
        if (mode == MultiDeviceArbitrationMode.AlwaysConnect) return TimeSpan.Zero;
        if (mode == MultiDeviceArbitrationMode.OnlyWhenPlaying) return TimeSpan.Zero;

        // MediaPriority mode:
        if (isMediaPlaying) return ActivePlaybackDelay;
        if (isUserActiveOrForeground) return ForegroundOrActiveUserDelay;

        if (lastPlaybackTime is DateTimeOffset lastTime)
        {
            var elapsed = now - lastTime;
            if (elapsed < TimeSpan.Zero) elapsed = TimeSpan.Zero;

            if (elapsed <= RecentPlaybackWindow) return RecentPlaybackDelay;
            if (elapsed <= IdlePlaybackWindow) return StalePlaybackDelay;
        }

        return IdleYieldDelay;
    }

    public static bool ShouldYieldConnection(
        MultiDeviceArbitrationMode mode,
        bool isMediaPlaying,
        TimeSpan connectedDurationWithoutMedia)
    {
        if (isMediaPlaying) return false;
        if (mode is MultiDeviceArbitrationMode.MediaPriority or MultiDeviceArbitrationMode.OnlyWhenPlaying)
        {
            return connectedDurationWithoutMedia >= TimeSpan.FromSeconds(10);
        }
        return false;
    }
}
