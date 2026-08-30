using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Runtime.CompilerServices;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml.Media.Imaging;
using Windows.Storage.Streams;
using TrikiMusicController_Windows.Core;
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
    private byte[]? _currentThumbnailBytes;
    private BitmapImage? _mediaThumbnailSource;
    private string _settingsStatus = string.Empty;
    private int _uiRefreshPending = 1;

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
        _bluetooth.StateChanged += BluetoothOnStateChanged;
        _media.StateChanged += MediaOnStateChanged;
        _runtime.StateChanged += RuntimeOnStateChanged;
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
        TrikiConnectionState.WaitingForWake => "Połączenie na żądanie",
        TrikiConnectionState.Scanning => "Skanowanie Bluetooth LE",
        TrikiConnectionState.Error => "Błąd Bluetooth",
        _ => "Triki rozłączone",
    };

    public string ConnectionDetails => _lastBluetooth.ErrorMessage ?? _lastBluetooth.ConnectionState switch
    {
        TrikiConnectionState.Ready => "Kontroler jest gotowy do sterowania muzyką.",
        TrikiConnectionState.Scanning => "Szukam aktywnego kontrolera Triki…",
        TrikiConnectionState.Connecting => "Przygotowuję bezpieczne połączenie…",
        TrikiConnectionState.WaitingForDevice or TrikiConnectionState.WaitingForWake =>
            "Naciśnij przycisk kontrolera, aby go wybudzić.",
        TrikiConnectionState.Error => "Sprawdź Bluetooth i spróbuj ponownie.",
        _ when _settings.Current.KnownDeviceAddress is not null =>
            "Naciśnij przycisk zapamiętanego Triki, aby połączyć je automatycznie.",
        _ => "Obudź Triki przyciskiem, a następnie znajdź i połącz je pierwszy raz.",
    };
    public bool IsConnected => _lastBluetooth.ConnectionState == TrikiConnectionState.Ready;
    public bool IsConnecting => _lastBluetooth.ConnectionState is TrikiConnectionState.Connecting or TrikiConnectionState.Scanning;
    public string ConnectionStateBadge => _lastBluetooth.ConnectionState switch
    {
        TrikiConnectionState.Ready => "Połączono",
        TrikiConnectionState.Connecting => "Łączenie",
        TrikiConnectionState.Scanning => "Skanowanie",
        TrikiConnectionState.WaitingForDevice or TrikiConnectionState.WaitingForWake => "Czuwanie",
        TrikiConnectionState.Error => "Błąd",
        _ => "Rozłączono",
    };
    public string BatteryText => _lastBluetooth.BatteryPercent is int battery ? $"{battery}%" : "Brak danych";
    public double BatteryFraction => (_lastBluetooth.BatteryPercent ?? 0) / 100.0;
    public bool HasBatteryData => _lastBluetooth.BatteryPercent is not null;
    public string SignalQuality => _lastBluetooth.ConnectedDevice?.Rssi switch
    {
        >= -60 => "Bardzo dobry",
        >= -72 => "Dobry",
        >= -84 => "Słaby",
        short.MinValue => "Brak danych",
        null => "Brak danych",
        _ => "Bardzo słaby",
    };

    public bool HasMediaSession => _lastMedia.HasSession;
    public string MediaTitle => string.IsNullOrWhiteSpace(_lastMedia.Title) ? "Brak odtwarzanego utworu" : _lastMedia.Title;
    public string MediaDetails => string.IsNullOrWhiteSpace(_lastMedia.Artist)
        ? (string.IsNullOrWhiteSpace(_lastMedia.SourceApp) ? "Uruchom Spotify, YouTube lub odtwarzacz multimedialny" : _lastMedia.SourceApp)
        : $"{_lastMedia.Artist} • {_lastMedia.SourceApp}";
    public string PlaybackStatus => _lastMedia.HasSession
        ? (_lastMedia.IsPlaying ? "Odtwarzanie" : "Wstrzymano")
        : "Brak aktywnej sesji multimedialnej";
    public string PlayPauseGlyph => _lastMedia.IsPlaying ? "\uE769" : "\uE768";
    public string PlayPauseLabel => _lastMedia.IsPlaying ? "Wstrzymaj" : "Odtwórz";
    public BitmapImage? MediaThumbnailSource
    {
        get => _mediaThumbnailSource;
        private set => SetField(ref _mediaThumbnailSource, value);
    }
    public bool HasMediaThumbnail => MediaThumbnailSource is not null;
    public string VolumeText => $"Głośność: {_lastMedia.VolumePercent:0}%{(_lastMedia.IsMuted ? " (wyciszona)" : string.Empty)}";
    public double VolumePercentValue => _lastMedia.VolumePercent;

    public double VolumeProgress => _lastRuntime.Volume?.StabilizationProgress ?? 0;
    public bool IsVolumeReady => _lastRuntime.Volume is { TiltStable: true, AccelerationStable: true, WithinTiltRange: true };
    public string VolumeTiltAngleText => _lastRuntime.Volume is { } volume ? $"{volume.TiltDegrees:0.0}°" : "—";
    public string VolumeTiltStatusText => _lastRuntime.Volume is null ? "Brak danych" : (_lastRuntime.Volume.WithinTiltRange ? "Poziom (0–25° OK)" : "Zbyt duży przechył (>25°)");
    public string VolumeAccStatusText => _lastRuntime.Volume is null ? "Brak danych" : (_lastRuntime.Volume.AccelerationStable ? "Stabilnie" : "Wykryto przyspieszenie");

    public string VolumeControlTitle => _lastRuntime.Volume switch
    {
        null => "Sterowanie nieaktywne",
        { SensorValid: false } => "Sprawdź połączenie",
        { WithinTiltRange: false } => "Ustaw Triki prawie poziomo",
        { AccelerationStable: false } => "Ustabilizuj Triki",
        { TiltStable: false } => "Przygotowywanie sterowania…",
        _ => "Gotowe do regulacji",
    };
    public string VolumeControlDetails => _lastRuntime.Volume is { } volume
        ? volume switch
        {
            { SensorValid: false } => "Nie otrzymuję prawidłowych danych ruchu z Triki.",
            { WithinTiltRange: false } => "Utrzymuj kapsel górną stroną do góry w zakresie przechyłu 0–25°.",
            { AccelerationStable: false } => "Gwałtowny ruch przerwał przygotowanie. Trzymaj kapsel spokojnie.",
            { TiltStable: false } => "Utrzymaj pozycję przez 2 sekundy i unikaj szarpnięć.",
            _ => "Obracaj kapsel w poziomie wokół osi Z: ↻ w prawo = głośniej, ↺ w lewo = ciszej.",
        }
        : "Połącz Triki, aby uruchomić gesty i regulację głośności.";
    public string VolumeTechnicalDetails => _lastRuntime.Volume is { } volume
        ? $"Przechył {volume.TiltDegrees:0.0}° • |ACC| {_lastRuntime.LatestSample?.AccelerationMagnitude ?? 0:0.00} g • żyroskop Z {volume.GyroscopeZDps:+0.0;-0.0;0.0}°/s"
        : "Regulator głośności: brak danych IMU.";
    
    public bool IsRotationFaceDown => _lastRuntime.Gesture.FaceDown;
    public string RotationPhaseBadge => _lastRuntime.Gesture.Phase switch
    {
        HoldGesturePhase.Holding => "Stabilizacja",
        HoldGesturePhase.Ready => "Gotowe do obrotu",
        HoldGesturePhase.Tracking => "Obrót…",
        HoldGesturePhase.Completing => "Kończenie",
        HoldGesturePhase.Triggered => "Wykonano!",
        _ => "Oczekiwanie",
    };
    public double GestureProgress => _lastRuntime.Gesture.Phase switch
    {
        HoldGesturePhase.Holding when _lastRuntime.Gesture.FaceDown => _lastRuntime.Gesture.StabilizationProgress,
        HoldGesturePhase.Tracking or HoldGesturePhase.Completing => _lastRuntime.Gesture.StabilizationProgress,
        HoldGesturePhase.Triggered => 1,
        _ => 0,
    };
    public string GestureStatus => _lastRuntime.Gesture.Phase switch
    {
        HoldGesturePhase.Holding => _lastRuntime.Gesture.FaceDown
            ? $"Odwrócenie potwierdzone • stabilizacja {_lastRuntime.Gesture.StabilizationProgress * 100:0}%"
            : "Odwróć kapsel górą w dół i uspokój go przed ruchem",
        HoldGesturePhase.Ready => $"Gotowe — obróć o {_settings.Current.RotationAngleDegrees}°: lewo = następny, prawo = poprzedni",
        HoldGesturePhase.Tracking => _lastRuntime.Gesture.Direction switch
        {
            RotationGestureDirection.Left => $"Następny utwór • ruch w lewo: {GestureProgress * FullRotationGestureDetector.PhysicalRotationTargetDegrees:0}° / {_settings.Current.RotationAngleDegrees}°",
            RotationGestureDirection.Right => $"Poprzedni utwór • ruch w prawo: {GestureProgress * FullRotationGestureDetector.PhysicalRotationTargetDegrees:0}° / {_settings.Current.RotationAngleDegrees}°",
            _ => "Potwierdzam kierunek obrotu…",
        },
        HoldGesturePhase.Completing => _lastRuntime.Gesture.Direction switch
        {
            RotationGestureDirection.Left => $"Następny utwór — dokończ ruch w lewo do {_settings.Current.RotationAngleDegrees}°",
            RotationGestureDirection.Right => $"Poprzedni utwór — dokończ ruch w prawo do {_settings.Current.RotationAngleDegrees}°",
            _ => $"Dokończ obrót do {_settings.Current.RotationAngleDegrees}°",
        },
        HoldGesturePhase.Rearming => "Uspokój ruch na moment przed kolejną próbą",
        HoldGesturePhase.Triggered => _lastRuntime.Gesture.Direction switch
        {
            RotationGestureDirection.Left => "Następny utwór — rozpoznano ruch w lewo",
            RotationGestureDirection.Right => "Poprzedni utwór — rozpoznano ruch w prawo",
            _ => "Zmiana utworu wysłana",
        },
        _ => $"Odwróć kapsel, ustabilizuj go 0,5 s i obróć o {_settings.Current.RotationAngleDegrees}°: lewo = następny, prawo = poprzedni",
    };
    public double ControllerProgress => _lastRuntime.Gesture.Phase switch
    {
        HoldGesturePhase.Holding when _lastRuntime.Gesture.FaceDown => _lastRuntime.Gesture.StabilizationProgress,
        HoldGesturePhase.Tracking or HoldGesturePhase.Completing => _lastRuntime.Gesture.StabilizationProgress,
        _ => _lastRuntime.Volume is { TiltStable: false } volume ? volume.StabilizationProgress : 0,
    };
    public string ControllerStatusTitle => _lastBluetooth.ConnectionState != TrikiConnectionState.Ready
        ? "Sterowanie nieaktywne"
        : _lastRuntime.Gesture.Phase switch
        {
            HoldGesturePhase.Holding when _lastRuntime.Gesture.FaceDown => "Przygotowywanie zmiany utworu…",
            HoldGesturePhase.Tracking => _lastRuntime.Gesture.Direction == RotationGestureDirection.Left
                ? "Następny utwór"
                : "Poprzedni utwór",
            HoldGesturePhase.Rearming => "Ustabilizuj Triki",
            HoldGesturePhase.Triggered => "Gest rozpoznany",
            _ => VolumeControlTitle,
        };
    public string ControllerStatusDetails => _lastBluetooth.ConnectionState != TrikiConnectionState.Ready
        ? "Połącz Triki, aby uruchomić gesty i przycisk."
        : _lastRuntime.Gesture.Phase switch
        {
            HoldGesturePhase.Holding when _lastRuntime.Gesture.FaceDown => "Trzymaj odwrócony kapsel stabilnie przez chwilę.",
            HoldGesturePhase.Tracking => $"Kontynuuj płynny obrót do {_settings.Current.RotationAngleDegrees}°.",
            HoldGesturePhase.Rearming => "Uspokój ruch przed kolejnym gestem.",
            HoldGesturePhase.Triggered => "Zmiana utworu została wysłana.",
            _ => VolumeControlDetails,
        };
    public string LastActionCompact => _lastRuntime.LastAction is MediaAction action && action != MediaAction.None
        ? $"Ostatnio: {action.DisplayName()}"
        : "Oczekiwanie na sterowanie";
    public string LastActionStatus => _lastRuntime.LastActionStatus;
    public bool IsScanning => _lastBluetooth.ConnectionState == TrikiConnectionState.Scanning;
    public bool HasDevices => Devices.Count > 0;
    public bool IsDeviceListEmpty => Devices.Count == 0 && !IsScanning;
    public bool CanConnect => SelectedDevice is not null || _settings.Current.KnownDeviceAddress is not null;
    public bool CanDisconnect => _lastBluetooth.ConnectionState is TrikiConnectionState.Ready or TrikiConnectionState.Connecting;

    // Pomiary telemetryczne na żywo
    public string AccXText => _lastRuntime.LatestSample is { } s ? $"{s.AccelerometerG.X:+0.000;-0.000;0.000} g" : "0.000 g";
    public string AccYText => _lastRuntime.LatestSample is { } s ? $"{s.AccelerometerG.Y:+0.000;-0.000;0.000} g" : "0.000 g";
    public string AccZText => _lastRuntime.LatestSample is { } s ? $"{s.AccelerometerG.Z:+0.000;-0.000;0.000} g" : "0.000 g";
    public double AccXNormalized => Math.Clamp((_lastRuntime.LatestSample?.AccelerometerG.X ?? 0) / 2.0 * 50 + 50, 0, 100);
    public double AccYNormalized => Math.Clamp((_lastRuntime.LatestSample?.AccelerometerG.Y ?? 0) / 2.0 * 50 + 50, 0, 100);
    public double AccZNormalized => Math.Clamp((_lastRuntime.LatestSample?.AccelerometerG.Z ?? 0) / 2.0 * 50 + 50, 0, 100);

    public string GyroXText => _lastRuntime.LatestSample is { } s ? $"{s.GyroscopeDps.X:+0.0;-0.0;0.0} °/s" : "0.0 °/s";
    public string GyroYText => _lastRuntime.LatestSample is { } s ? $"{s.GyroscopeDps.Y:+0.0;-0.0;0.0} °/s" : "0.0 °/s";
    public string GyroZText => _lastRuntime.LatestSample is { } s ? $"{s.GyroscopeDps.Z:+0.0;-0.0;0.0} °/s" : "0.0 °/s";
    public double GyroXNormalized => Math.Clamp((_lastRuntime.LatestSample?.GyroscopeDps.X ?? 0) / 400.0 * 50 + 50, 0, 100);
    public double GyroYNormalized => Math.Clamp((_lastRuntime.LatestSample?.GyroscopeDps.Y ?? 0) / 400.0 * 50 + 50, 0, 100);
    public double GyroZNormalized => Math.Clamp((_lastRuntime.LatestSample?.GyroscopeDps.Z ?? 0) / 400.0 * 50 + 50, 0, 100);

    public string AccMagnitudeText => _lastRuntime.LatestSample is { } s ? $"{s.AccelerationMagnitude:0.00} g" : "—";
    public string OrientationBadgeText => _lastRuntime.LatestSample is null ? "Brak danych" : (_lastRuntime.Gesture.FaceDown ? "Odwrócony (Zmiana utworu)" : "Górą do góry (Głośność)");
    public string SampleRateDisplay => _lastBluetooth.SampleRateHz is { } hz ? $"{hz:0.0} Hz" : "—";
    public string DecodedFramesText => $"{_lastBluetooth.DecodedFrames:N0}";
    public string DroppedBytesText => $"{_lastBluetooth.DroppedProtocolBytes:N0}";

    public string SensorDetails => _lastRuntime.LatestSample is { } sample
        ? $"ACC  X {sample.AccelerometerG.X:+0.000;-0.000;0.000}  Y {sample.AccelerometerG.Y:+0.000;-0.000;0.000}  Z {sample.AccelerometerG.Z:+0.000;-0.000;0.000} g\n" +
          $"GYRO X {sample.GyroscopeDps.X:+0.0;-0.0;0.0}  Y {sample.GyroscopeDps.Y:+0.0;-0.0;0.0}  Z {sample.GyroscopeDps.Z:+0.0;-0.0;0.0} °/s"
        : "Brak próbek IMU.";
    public string ProtocolDetails => $"Tryb przycisku: {ButtonProtocolLabel} • ramki: {_lastBluetooth.DecodedFrames} • odrzucone przy starcie: {_lastBluetooth.DiscardedStartupFrames} • pominięte bajty: {_lastBluetooth.DroppedProtocolBytes}";
    private string ButtonProtocolLabel => _lastRuntime.ButtonProtocol switch
    {
        TrikiButtonProtocolMode.ButtonFlag => "flaga przycisku",
        TrikiButtonProtocolMode.SequenceCounter => "licznik sekwencji",
        _ => "nieustalony",
    };
    public string RememberedDevice => _settings.Current.KnownDeviceAddress is ulong address
        ? $"{_settings.Current.KnownDeviceName ?? "Triki"} • {address:X12}"
        : "Brak zapamiętanego urządzenia";
    public string ConnectionModeSummary => _settings.Current.ConnectOnlyWhenNeeded
        ? "Po 12 sekundach bez ruchu lub przycisku aplikacja zamyka GATT. Następne wybudzenie automatycznie przywraca sterowanie."
        : "Połączenie pozostaje aktywne do czasu uśpienia kapsla lub ręcznego rozłączenia.";
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
            _bluetooth.ConfigureRememberedDevice(
                _settings.Current.KnownDeviceAddress,
                _settings.Current.KnownDeviceName,
                value,
                _settings.Current.ConnectOnlyWhenNeeded);
            OnPropertyChanged();
            _ = SaveSettingsAsync();
        }
    }

    public bool ConnectOnlyWhenNeeded
    {
        get => _settings.Current.ConnectOnlyWhenNeeded;
        set
        {
            if (_settings.Current.ConnectOnlyWhenNeeded == value) return;
            _settings.Current.ConnectOnlyWhenNeeded = value;
            if (value && !_settings.Current.AutoReconnect)
            {
                _settings.Current.AutoReconnect = true;
                OnPropertyChanged(nameof(AutoReconnect));
            }
            _bluetooth.ConfigureRememberedDevice(
                _settings.Current.KnownDeviceAddress,
                _settings.Current.KnownDeviceName,
                _settings.Current.AutoReconnect,
                value);
            OnPropertyChanged();
            OnPropertyChanged(nameof(ConnectionModeSummary));
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

    public bool EnableSoundFeedback
    {
        get => _settings.Current.EnableSoundFeedback;
        set
        {
            if (_settings.Current.EnableSoundFeedback == value) return;
            _settings.Current.EnableSoundFeedback = value;
            OnPropertyChanged();
            _ = SaveSettingsAsync();
        }
    }

    public bool EnableToastNotifications
    {
        get => _settings.Current.EnableToastNotifications;
        set
        {
            if (_settings.Current.EnableToastNotifications == value) return;
            _settings.Current.EnableToastNotifications = value;
            OnPropertyChanged();
            _ = SaveSettingsAsync();
        }
    }

    public int RotationAngleDegrees
    {
        get => _settings.Current.RotationAngleDegrees;
        set
        {
            var clamped = Math.Clamp(value, 90, 360);
            if (_settings.Current.RotationAngleDegrees == clamped) return;
            _settings.Current.RotationAngleDegrees = clamped;
            _runtime.UpdateRotationAngle(clamped);
            OnPropertyChanged();
            OnPropertyChanged(nameof(RotationAngleLabel));
            OnPropertyChanged(nameof(GestureStatus));
            OnPropertyChanged(nameof(ControllerStatusDetails));
            _ = SaveSettingsAsync();
        }
    }

    public string RotationAngleLabel => $"{_settings.Current.RotationAngleDegrees}°";

    public async Task InitializeAsync()
    {
        if (_initialized) return;
        _initialized = true;
        _bluetooth.ConfigureRememberedDevice(
            _settings.Current.KnownDeviceAddress,
            _settings.Current.KnownDeviceName,
            _settings.Current.AutoReconnect,
            _settings.Current.ConnectOnlyWhenNeeded);
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
        _bluetooth.ConfigureRememberedDevice(
            selected.BluetoothAddress,
            selected.Name,
            _settings.Current.AutoReconnect,
            _settings.Current.ConnectOnlyWhenNeeded);
        OnPropertyChanged(nameof(RememberedDevice));
    }

    public Task DisconnectAsync() => _bluetooth.DisconnectAsync(forgetDevice: false);

    public async Task ConnectOrDisconnectAsync()
    {
        if (IsConnected)
            await DisconnectAsync();
        else if (SelectedDevice is not null)
            await ConnectSelectedAsync();
        else
            await ScanAsync();
    }

    public async Task ForgetAsync()
    {
        await _bluetooth.DisconnectAsync(forgetDevice: true);
        await _settings.ForgetDeviceAsync();
        _bluetooth.ConfigureRememberedDevice(
            null,
            null,
            _settings.Current.AutoReconnect,
            _settings.Current.ConnectOnlyWhenNeeded);
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

    private void BluetoothOnStateChanged(object? sender, BluetoothSnapshot state) => MarkUiDirty();
    private void MediaOnStateChanged(object? sender, MediaSnapshot state) => MarkUiDirty();
    private void RuntimeOnStateChanged(object? sender, RuntimeSnapshot state) => MarkUiDirty();
    private void MarkUiDirty() => Interlocked.Exchange(ref _uiRefreshPending, 1);

    private void UiTimerOnTick(DispatcherQueueTimer sender, object args)
    {
        if (Interlocked.Exchange(ref _uiRefreshPending, 0) == 0) return;
        RefreshUi();
    }

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

        CheckAndApplyThumbnail(_lastMedia.ThumbnailBytes);

        OnPropertyChanged(string.Empty);
    }

    private void CheckAndApplyThumbnail(byte[]? newBytes)
    {
        if (ByteArraysEqual(_currentThumbnailBytes, newBytes)) return;
        _currentThumbnailBytes = newBytes;
        if (newBytes is { Length: > 0 })
        {
            _ = UpdateThumbnailBitmapAsync(newBytes);
        }
        else
        {
            MediaThumbnailSource = null;
            OnPropertyChanged(nameof(HasMediaThumbnail));
        }
    }

    private async Task UpdateThumbnailBitmapAsync(byte[] bytes)
    {
        try
        {
            using var stream = new InMemoryRandomAccessStream();
            using (var writer = new DataWriter(stream.GetOutputStreamAt(0)))
            {
                writer.WriteBytes(bytes);
                await writer.StoreAsync();
            }
            stream.Seek(0);
            var bitmap = new BitmapImage();
            await bitmap.SetSourceAsync(stream);
            MediaThumbnailSource = bitmap;
            OnPropertyChanged(nameof(HasMediaThumbnail));
        }
        catch (Exception error)
        {
            System.Diagnostics.Debug.WriteLine($"Błąd ładowania miniatury: {error.Message}");
            MediaThumbnailSource = null;
            OnPropertyChanged(nameof(HasMediaThumbnail));
        }
    }

    private static bool ByteArraysEqual(byte[]? a, byte[]? b)
    {
        if (ReferenceEquals(a, b)) return true;
        if (a is null || b is null) return false;
        return a.AsSpan().SequenceEqual(b.AsSpan());
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
        _bluetooth.StateChanged -= BluetoothOnStateChanged;
        _media.StateChanged -= MediaOnStateChanged;
        _runtime.StateChanged -= RuntimeOnStateChanged;
    }
}
