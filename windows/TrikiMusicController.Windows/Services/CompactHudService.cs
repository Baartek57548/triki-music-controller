using Microsoft.UI.Dispatching;
using TrikiMusicController_Windows.Views;

namespace TrikiMusicController_Windows.Services;

public sealed class CompactHudService
{
    private readonly DispatcherQueue _dispatcherQueue;
    private CompactHudWindow? _window;

    public CompactHudService(DispatcherQueue dispatcherQueue)
    {
        _dispatcherQueue = dispatcherQueue;
    }

    private CompactHudWindow GetOrCreateWindow()
    {
        return _window ??= new CompactHudWindow();
    }

    public void ShowVolume(int volumePercent, string trackTitle, string artist)
    {
        _dispatcherQueue.TryEnqueue(() =>
        {
            try
            {
                var win = GetOrCreateWindow();
                win.ShowVolume(volumePercent, trackTitle, artist);
            }
            catch
            {
                // UI dispatcher safety
            }
        });
    }

    public void ShowBrightness(int brightnessPercent)
    {
        _dispatcherQueue.TryEnqueue(() =>
        {
            try
            {
                var win = GetOrCreateWindow();
                win.ShowBrightness(brightnessPercent);
            }
            catch
            {
                // UI dispatcher safety
            }
        });
    }
}
