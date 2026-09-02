using System.Collections.ObjectModel;
using TrikiMusicController_Windows.Core;

namespace TrikiMusicController_Windows.Models;

public enum TrikiConnectionState
{
    Disconnected,
    Scanning,
    WaitingForDevice,
    WaitingForWake,
    Connecting,
    Ready,
    Error,
}

public enum MultiDeviceArbitrationMode
{
    MediaPriority = 0,
    AlwaysConnect = 1,
    OnlyWhenPlaying = 2,
}

public sealed record TrikiDeviceInfo(ulong BluetoothAddress, string Name, short Rssi)
{
    public string AddressDisplay => string.Join(":", Enumerable.Range(0, 6)
        .Reverse()
        .Select(index => ((BluetoothAddress >> (index * 8)) & 0xFF).ToString("X2")));

    public string DisplayName => $"{Name}  •  {Rssi} dBm  •  {AddressDisplay}";
}

public sealed record BluetoothSnapshot(
    TrikiConnectionState ConnectionState,
    TrikiDeviceInfo? ConnectedDevice,
    IReadOnlyList<TrikiDeviceInfo> DiscoveredDevices,
    int? BatteryPercent,
    float? SampleRateHz,
    long DecodedFrames,
    long DiscardedStartupFrames,
    long DroppedProtocolBytes,
    bool WakeWatcherArmed,
    string? ErrorMessage)
{
    public static BluetoothSnapshot Initial { get; } = new(
        TrikiConnectionState.Disconnected, null, [], null, null, 0, 0, 0, false, null);
}

public sealed record MediaSnapshot(
    bool HasSession,
    bool IsPlaying,
    string Title,
    string Artist,
    string SourceApp,
    bool CanPlay,
    bool CanPause,
    bool CanNext,
    bool CanPrevious,
    float VolumePercent,
    bool IsMuted,
    byte[]? ThumbnailBytes,
    string? ErrorMessage)
{
    public static MediaSnapshot Initial { get; } = new(
        false, false, "Brak aktywnego utworu", "—", "—", false, false, false, false, 0, false, null, null);
}

public sealed record RuntimeSnapshot(
    FilteredSensorData? LatestSample,
    VolumeControlResult? Volume,
    BrightnessControlResult? Brightness,
    FullRotationGestureResult Gesture,
    TrikiButtonProtocolMode ButtonProtocol,
    MediaAction? LastAction,
    string LastActionStatus,
    DateTimeOffset? LastActionAt,
    bool IsMouseMode = false,
    bool IsMouseScrollMode = false)
{
    public static RuntimeSnapshot Initial { get; } = new(
        null,
        null,
        null,
        new FullRotationGestureResult(false, null, HoldGesturePhase.Idle, 0, false, 0, 0),
        TrikiButtonProtocolMode.Unknown,
        null,
        "Oczekiwanie na dane Triki",
        null,
        false,
        false);
}

public sealed class AppSettings
{
    public string? KnownDeviceAddressHex { get; set; }
    public string? KnownDeviceName { get; set; }
    public bool AutoReconnect { get; set; } = true;
    public bool ConnectOnlyWhenNeeded { get; set; }
    public MultiDeviceArbitrationMode MultiDeviceArbitration { get; set; } = MultiDeviceArbitrationMode.MediaPriority;
    public bool StartWithWindows { get; set; }
    public string Theme { get; set; } = "System";
    public MediaAction SingleClickAction { get; set; } = MediaAction.PlayPause;
    public MediaAction DoubleClickAction { get; set; } = MediaAction.Like;
    public MediaAction TripleClickAction { get; set; } = MediaAction.Dislike;
    public bool EnableSoundFeedback { get; set; } = true;
    public bool EnableToastNotifications { get; set; } = true;
    public CalibrationProfile Calibration { get; set; } = new();
    /// <summary>Fizyczny kąt obrotu (w stopniach) wymagany do zmiany utworu. Zakres: 90–360.</summary>
    public int RotationAngleDegrees { get; set; } = 200;
    /// <summary>Wersja aplikacji, dla której użytkownik widział już okno Co nowego.</summary>
    public string? LastSeenVersion { get; set; }

    public ulong? KnownDeviceAddress => ulong.TryParse(
        KnownDeviceAddressHex,
        System.Globalization.NumberStyles.HexNumber,
        System.Globalization.CultureInfo.InvariantCulture,
        out var address)
        ? address
        : null;

    public MediaAction ActionFor(ButtonClickType type) => type switch
    {
        ButtonClickType.Single => SingleClickAction,
        ButtonClickType.Double => DoubleClickAction,
        ButtonClickType.Triple => TripleClickAction,
        _ => MediaAction.None,
    };
}

public static class AppInfo
{
    public const string Version = "3.0.1";
    public const string GitHubOwner = "Baartek57548";
    public const string GitHubRepository = "triki-music-controller";
}
