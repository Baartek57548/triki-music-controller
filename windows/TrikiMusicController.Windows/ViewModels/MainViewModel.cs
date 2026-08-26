using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Runtime.CompilerServices;
using Microsoft.UI.Dispatching;
using TrikiMusicController_Windows.Models;
using TrikiMusicController_Windows.Runtime;
using TrikiMusicController_Windows.Services;

namespace TrikiMusicController_Windows.ViewModels;

public sealed class MainViewModel : INotifyPropertyChanged, IDisposable
{
    private readonly SettingsService _settings;
    private readonly BluetoothService _bluetooth;
    private readonly MediaControlService _media;
    private readonly TrikiRuntimeEngine _runtime;
    private readonly DispatcherQueueTimer _uiTimer;
    private bool _initialized;
    private bool _disposed;
    private TrikiDeviceInfo? _selectedDevice;
    private BluetoothSnapshot _lastBluetooth = BluetoothSnapshot.Initial;
    private MediaSnapshot _lastMedia = MediaSnapshot.Initial;
    private RuntimeSnapshot _lastRuntime = RuntimeSnapshot.Initial;
    private string _settingsStatus = string.Empty;

    public MainViewModel(
        DispatcherQueue dispatcherQueue,
        SettingsService settings,
        BluetoothService bluetooth,
        MediaControlService media,
        TrikiRuntimeEngine runtime)
    {
        _settings = settings;
        _bluetooth = bluetooth;
        _media = media;
        _runtime = runtime;
        _uiTimer = dispatcherQueue.CreateTimer();
        _uiTimer.Interval = TimeSpan.FromMilliseconds(100);
        _uiTimer.IsRepeating = true;
        _uiTimer.Tick += UiTimerOnTick;
    }

    public event PropertyChangedEventHandler? PropertyChanged;
    public ObservableCollection<TrikiDeviceInfo> Devices { get; } = [];
    public IReadOnlyList<MediaActionOption> AvailableActions { get; } = Enum.GetValues<MediaAction>()
        .Select(action => new MediaActionOption(action, action.DisplayName()))
        .ToArray();
    public IReadOnlyList<ThemeOption> AvailableThemes { get; } =
    [
        new("System", "Zgodny z Windows"),
        new("Light", "Jasny"),
        new("Dark", "Ciemny"),
    ];

    public TrikiDeviceInfo? SelectedDevice
    {
        get => _selectedDevice;
        set => SetField(ref _selectedDevice, value);
    }

    public string ConnectionTitle => _lastBluetooth.ConnectionState switch
    {
        TrikiConnectionState.Ready => "Triki połączone",
        TrikiConnectionState.Connecting => "Łączenie z Triki…",
        TrikiConnectionState.WaitingForDevice => "Oczekiwanie na wybudzenie",
        TrikiConnectionState.Scanning => "Skanowanie Bluetooth LE",
        TrikiConnectionState.Error => "Błąd Bluetooth",
        _ => "Triki rozłączone",
    };

    public string ConnectionDetails => _lastBluetooth.ConnectionState == TrikiConnectionState.Ready
        ? $"{_lastBluetooth.ConnectedDevice?.Name} • {_lastBluetooth.SampleRateHz?.ToString("0.0") ?? "—"} Hz • bateria {_lastBluetooth.BatteryPercent?.ToString() ?? "—"}%"
        : _lastBluetooth.ErrorMessage ?? (_settings.Current.KnownDeviceAddress is not null
            ? "Naciśnij przycisk kapsla. Aplikacja połączy się, gdy urządzenie zacznie nadawać."
            : "Wybierz urządzenie z listy i połącz je po raz pierwszy.");

    public string MediaTitle => _lastMedia.Title;
    public string MediaDetails => $"{_lastMedia.Artist} • {_lastMedia.SourceApp}";
    public string PlaybackStatus => _lastMedia.HasSession
        ? (_lastMedia.IsPlaying ? "Odtwarzanie" : "Wstrzymano")
        : "Brak aktywnej sesji multimedialnej";
    public string VolumeText => $"Głośność systemowa: {_lastMedia.VolumePercent:0}%{(_lastMedia.IsMuted ? " (wyciszona)" : string.Empty)}";

    public double VolumeProgress => _lastRuntime.Volume?.StabilizationProgress ?? 0;
    public string VolumeControlTitle => _lastRuntime.Volume switch
    {
        null => "Regulator oczekuje na dane",
        { SensorValid: false } => "Nieprawidłowe dane czujnika",
        { WithinTiltRange: false } volume => $"Poza zakresem: {volume.TiltDegrees:0.0}°",
        { AccelerationStable: false } => "Gwałtowny ruch — stabilizacja od nowa",
        { TiltStable: false } volume => $"Stabilizacja kąta {volume.StabilizationProgress * 100:0}%",
        _ => "Regulator głośności gotowy",
    };
    public string VolumeControlDetails => _lastRuntime.Volume is { } volume
        ? $"Przechył {volume.TiltDegrees:0.0}° • |ACC| {_lastRuntime.LatestSample?.AccelerationMagnitude ?? 0:0.00} g • żyroskop Z {volume.GyroscopeZDps:+0.0;-0.0;0.0}°/s"
        : "Utrzymuj kapsel w zakresie 0–25° przez 2 sekundy i unikaj gwałtownych ruchów.";
    public string GestureStatus => _lastRuntime.Gesture.Phase switch
    {
        HoldGesturePhase.Holding => $"Przytrzymanie: {_lastRuntime.Gesture.HoldProgress * 100:0}%",
        HoldGesturePhase.Ready => "Przycisk przytrzymany — podnieś lub opuść kapsel",
        HoldGesturePhase.Tracking => _lastRuntime.Gesture.Direction switch
        {
            RatingGestureAction.Like => $"Ruch w górę: {Math.Abs(_lastRuntime.Gesture.EstimatedDisplacementMeters * 100):0.0} cm",
            RatingGestureAction.Dislike => $"Ruch w dół: {Math.Abs(_lastRuntime.Gesture.EstimatedDisplacementMeters * 100):0.0} cm",
            _ => "Potwierdzam kierunek ruchu…",
        },
        HoldGesturePhase.Completing => _lastRuntime.Gesture.Direction switch
        {
            RatingGestureAction.Like => "Kierunek w górę potwierdzony — łagodnie wyhamuj ruch",
            RatingGestureAction.Dislike => "Kierunek w dół potwierdzony — łagodnie wyhamuj ruch",
            _ => "Łagodnie wyhamuj ruch",
        },
        HoldGesturePhase.Rearming => "Uspokój ruch na moment przed kolejną próbą",
        HoldGesturePhase.Triggered => "Gest oceny rozpoznany",
        _ => "Przytrzymaj 0,5 s, potem wykonaj ruch pionowy 20–30 cm",
    };
    public string LastActionStatus => _lastRuntime.LastActionStatus;
    public string SensorDetails => _lastRuntime.LatestSample is { } sample
        ? $"ACC  X {sample.AccelerometerG.X:+0.000;-0.000;0.000}  Y {sample.AccelerometerG.Y:+0.000;-0.000;0.000}  Z {sample.AccelerometerG.Z:+0.000;-0.000;0.000} g\n" +
          $"GYRO X {sample.GyroscopeDps.X:+0.0;-0.0;0.0}  Y {sample.GyroscopeDps.Y:+0.0;-0.0;0.0}  Z {sample.GyroscopeDps.Z:+0.0;-0.0;0.0} °/s"
        : "Brak próbek IMU.";
    public string ProtocolDetails => $"Tryb przycisku: {_lastRuntime.ButtonProtocol} • ramki: {_lastBluetooth.DecodedFrames} • odrzucone przy starcie: {_lastBluetooth.DiscardedStartupFrames} • pominięte bajty: {_lastBluetooth.DroppedProtocolBytes}";
    public string RememberedDevice => _settings.Current.KnownDeviceAddress is ulong address
        ? $"{_settings.Current.KnownDeviceName ?? "Triki"} • {address:X12}"
        : "Brak zapamiętanego urządzenia";
    public string SettingsStatus
    {
        get => _settingsStatus;
        private set => SetField(ref _settingsStatus, value);
    }

    public bool AutoReconnect
    {
        get => _settings.Current.AutoReconnect;
        set
        {
            if (_settings.Current.AutoReconnect == value) return;
            _settings.Current.AutoReconnect = value;
            _bluetooth.ConfigureRememberedDevice(_settings.Current.KnownDeviceAddress, _settings.Current.KnownDeviceName, value);
            OnPropertyChanged();
            _ = SaveSettingsAsync();
        }
    }

    public bool StartWithWindows
    {
        get => _settings.Current.StartWithWindows;
        set
        {
            if (_settings.Current.StartWithWindows == value) return;
            _settings.Current.StartWithWindows = value;
            OnPropertyChanged();
            _ = SaveSettingsAsync();
        }
    }

    public ThemeOption SelectedTheme
    {
        get => AvailableThemes.FirstOrDefault(option => option.Value == _settings.Current.Theme) ?? AvailableThemes[0];
        set
        {
            if (_settings.Current.Theme == value.Value) return;
            _settings.Current.Theme = value.Value;
            ((App)Microsoft.UI.Xaml.Application.Current).MainWindow?.ApplyTheme(value.Value);
            OnPropertyChanged();
            _ = SaveSettingsAsync();
        }
    }

    public MediaActionOption SingleClickAction
    {
        get => OptionFor(_settings.Current.SingleClickAction);
        set { if (_settings.Current.SingleClickAction == value.Action) return; _settings.Current.SingleClickAction = value.Action; OnPropertyChanged(); _ = SaveSettingsAsync(); }
    }
    public MediaActionOption DoubleClickAction
    {
        get => OptionFor(_settings.Current.DoubleClickAction);
        set { if (_settings.Current.DoubleClickAction == value.Action) return; _settings.Current.DoubleClickAction = value.Action; OnPropertyChanged(); _ = SaveSettingsAsync(); }
    }
    public MediaActionOption TripleClickAction
    {
        get => OptionFor(_settings.Current.TripleClickAction);
        set { if (_settings.Current.TripleClickAction == value.Action) return; _settings.Current.TripleClickAction = value.Action; OnPropertyChanged(); _ = SaveSettingsAsync(); }
    }

    public async Task InitializeAsync()
    {
        if (_initialized) return;
        _initialized = true;
        _bluetooth.ConfigureRememberedDevice(
            _settings.Current.KnownDeviceAddress,
            _settings.Current.KnownDeviceName,
            _settings.Current.AutoReconnect);
        await _media.InitializeAsync();
        await _bluetooth.StartAsync();
        _uiTimer.Start();
        RefreshUi();
    }

    public Task ScanAsync() => _bluetooth.StartAsync();

    public async Task ConnectSelectedAsync()
    {
        var selected = SelectedDevice ?? throw new InvalidOperationException("Najpierw wybierz Triki z listy.");
        await _bluetooth.ConnectAsync(selected);
        await _settings.RememberDeviceAsync(selected);
        _bluetooth.ConfigureRememberedDevice(selected.BluetoothAddress, selected.Name, _settings.Current.AutoReconnect);
        OnPropertyChanged(nameof(RememberedDevice));
    }

    public Task DisconnectAsync() => _bluetooth.DisconnectAsync(forgetDevice: false);

    public async Task ForgetAsync()
    {
        await _bluetooth.DisconnectAsync(forgetDevice: true);
        await _settings.ForgetDeviceAsync();
        _bluetooth.ConfigureRememberedDevice(null, null, _settings.Current.AutoReconnect);
        OnPropertyChanged(nameof(RememberedDevice));
    }

    public Task SetLedAsync(bool enabled) => _bluetooth.SetLedAsync(enabled);
    public async Task ExecuteMediaActionAsync(MediaAction action) => await _media.ExecuteAsync(action);

    private async Task SaveSettingsAsync()
    {
        try
        {
            await _settings.SaveAsync();
            SettingsStatus = "Ustawienia zapisane.";
        }
        catch (Exception error)
        {
            SettingsStatus = $"Nie udało się zapisać ustawień: {error.Message}";
            System.Diagnostics.Debug.WriteLine(error);
        }
    }

    private MediaActionOption OptionFor(MediaAction action) =>
        AvailableActions.First(option => option.Action == action);

    private void UiTimerOnTick(DispatcherQueueTimer sender, object args) => RefreshUi();

    private void RefreshUi()
    {
        _lastBluetooth = _bluetooth.State;
        _lastMedia = _media.State;
        _lastRuntime = _runtime.State;
        if (!Devices.SequenceEqual(_lastBluetooth.DiscoveredDevices))
        {
            var selectedAddress = SelectedDevice?.BluetoothAddress;
            Devices.Clear();
            foreach (var device in _lastBluetooth.DiscoveredDevices) Devices.Add(device);
            SelectedDevice = selectedAddress is ulong address
                ? Devices.FirstOrDefault(device => device.BluetoothAddress == address)
                : null;
        }
        OnPropertyChanged(string.Empty);
    }

    private bool SetField<T>(ref T field, T value, [CallerMemberName] string? propertyName = null)
    {
        if (EqualityComparer<T>.Default.Equals(field, value)) return false;
        field = value;
        OnPropertyChanged(propertyName);
        return true;
    }

    private void OnPropertyChanged([CallerMemberName] string? propertyName = null) =>
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(propertyName));

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        _uiTimer.Stop();
        _uiTimer.Tick -= UiTimerOnTick;
    }
}
