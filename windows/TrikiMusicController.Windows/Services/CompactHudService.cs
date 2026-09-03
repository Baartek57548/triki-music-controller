using Microsoft.UI.Dispatching;
using TrikiMusicController_Windows.Models;
using TrikiMusicController_Windows.Views;

namespace TrikiMusicController_Windows.Services;

public sealed class CompactHudService : IDisposable
{
    private readonly DispatcherQueue _dispatcherQueue;
    private CompactHudWindow? _window;
    private bool _disposed;

    public CompactHudService(DispatcherQueue dispatcherQueue)
    {
        _dispatcherQueue = dispatcherQueue;
    }

    private CompactHudWindow? GetOrCreateWindow()
    {
        if (_disposed) return null;
        return _window ??= new CompactHudWindow();
    }

    public void ShowVolume(int volumePercent, string trackTitle, string artist, byte[]? thumbnailBytes = null)
    {
        if (_disposed) return;
        _dispatcherQueue.TryEnqueue(() =>
        {
            if (_disposed) return;
            try
            {
                var win = GetOrCreateWindow();
                win?.ShowVolume(volumePercent, trackTitle, artist, thumbnailBytes);
            }
            catch
            {
                // UI dispatcher safety
            }
        });
    }

    public void ShowBrightness(int brightnessPercent)
    {
        if (_disposed) return;
        _dispatcherQueue.TryEnqueue(() =>
        {
            if (_disposed) return;
            try
            {
                var win = GetOrCreateWindow();
                win?.ShowBrightness(brightnessPercent);
            }
            catch
            {
                // UI dispatcher safety
            }
        });
    }

    public void ShowTrack(string trackTitle, string artist, MediaAction action, byte[]? thumbnailBytes = null)
    {
        if (_disposed) return;
        _dispatcherQueue.TryEnqueue(() =>
        {
            if (_disposed) return;
            try
            {
                var win = GetOrCreateWindow();
                win?.ShowTrack(trackTitle, artist, action, thumbnailBytes);
            }
            catch
            {
                // UI dispatcher safety
            }
        });
    }

    public void ShowMouseMode(bool enabled, bool isScroll = false)
    {
        if (_disposed) return;
        _dispatcherQueue.TryEnqueue(() =>
        {
            if (_disposed) return;
            try
            {
                var win = GetOrCreateWindow();
                win?.ShowMouseMode(enabled, isScroll);
            }
            catch
            {
                // UI dispatcher safety
            }
        });
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        _dispatcherQueue.TryEnqueue(() =>
        {
            try
            {
                _window?.Dispose();
                _window = null;
            }
            catch
            {
                // UI dispatcher safety
            }
        });
    }
}
