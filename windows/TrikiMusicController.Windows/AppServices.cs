using Microsoft.UI.Dispatching;
using TrikiMusicController_Windows.Runtime;
using TrikiMusicController_Windows.Services;
using TrikiMusicController_Windows.ViewModels;

namespace TrikiMusicController_Windows;

public sealed class AppServices : IDisposable
{
    public AppServices(SettingsService settings, DispatcherQueue dispatcherQueue)
    {
        Settings = settings;
        Volume = new SystemVolumeService();
        Brightness = new SystemBrightnessService();
        Mouse = new SystemMouseService();
        Media = new MediaControlService(Volume);
        Updates = new UpdateService();
        Bluetooth = new BluetoothService();
        RatingFeedback = new FeedbackToneService();
        Spotify = new SpotifyConnectService();
        Hud = new CompactHudService(dispatcherQueue);
        Runtime = new TrikiRuntimeEngine(Bluetooth, Media, Volume, Brightness, Mouse, Settings, RatingFeedback, Hud);
        ViewModel = new MainViewModel(dispatcherQueue, Settings, Bluetooth, Media, Runtime, Hud);
    }

    public SettingsService Settings { get; }
    public SystemVolumeService Volume { get; }
    public SystemBrightnessService Brightness { get; }
    public SystemMouseService Mouse { get; }
    public SpotifyConnectService Spotify { get; }
    public CompactHudService Hud { get; }
    public MediaControlService Media { get; }
    public UpdateService Updates { get; }
    public BluetoothService Bluetooth { get; }
    public FeedbackToneService RatingFeedback { get; }
    public TrikiRuntimeEngine Runtime { get; }
    public MainViewModel ViewModel { get; }

    public void Dispose()
    {
        ViewModel.Dispose();
        Runtime.Dispose();
        Spotify.Dispose();
        RatingFeedback.Dispose();
        Bluetooth.Dispose();
        Media.Dispose();
        Updates.Dispose();
    }
}
