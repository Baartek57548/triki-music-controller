using System.Collections.ObjectModel;

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
    string? ErrorMessage)
{
    public static MediaSnapshot Initial { get; } = new(
        false, false, "Brak aktywnego utworu", "—", "—", false, false, false, false, 0, false, null);
}

public sealed record RuntimeSnapshot(
    FilteredSensorData? LatestSample,
    VolumeControlResult? Volume,
    HoldArcGestureResult Gesture,
    TrikiButtonProtocolMode ButtonProtocol,
    MediaAction? LastAction,
    string LastActionStatus,
    DateTimeOffset? LastActionAt)
{
    public static RuntimeSnapshot Initial { get; } = new(
        null,
        null,
        new HoldArcGestureResult(null, null, HoldGesturePhase.Idle, 0, false, 0, 0),
        TrikiButtonProtocolMode.Unknown,
        null,
        "Oczekiwanie na dane Triki",
        null);
}

public sealed class AppSettings
{
    public string? KnownDeviceAddressHex { get; set; }
    public string? KnownDeviceName { get; set; }
    public bool AutoReconnect { get; set; } = true;
    public bool ConnectOnlyWhenNeeded { get; set; }
    public bool StartWithWindows { get; set; }
    public string Theme { get; set; } = "System";
    public MediaAction SingleClickAction { get; set; } = MediaAction.PlayPause;
    public MediaAction DoubleClickAction { get; set; } = MediaAction.Next;
    public MediaAction TripleClickAction { get; set; } = MediaAction.Previous;
    public CalibrationProfile Calibration { get; set; } = new();

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
    public const string Version = "2.5.4";
    public const string GitHubOwner = "Baartek57548";
    public const string GitHubRepository = "triki-music-controller";
}
