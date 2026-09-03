using System.Text.Json;
using System.Text.Json.Serialization;
using System.Diagnostics;
using System.Globalization;
using Microsoft.Win32;
using TrikiMusicController_Windows.Models;

namespace TrikiMusicController_Windows.Services;

public sealed class SettingsService
{
    private const string RunRegistryPath = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string RunRegistryValue = "TrikiMusicController";
    private readonly SemaphoreSlim _gate = new(1, 1);
    private readonly JsonSerializerOptions _jsonOptions = new()
    {
        WriteIndented = true,
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        Converters = { new JsonStringEnumConverter() },
    };
    private readonly string _settingsDirectory;

    public SettingsService(string? customSettingsDirectory = null)
    {
        _settingsDirectory = customSettingsDirectory ?? Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "TrikiMusicController");
    }

    public AppSettings Current { get; private set; } = new();

    public async Task LoadAsync()
    {
        await _gate.WaitAsync().ConfigureAwait(false);
        try
        {
            Directory.CreateDirectory(_settingsDirectory);
            var path = SettingsPath;
            if (!File.Exists(path))
            {
                Current = new AppSettings { StartWithWindows = IsStartupRegistered() };
                return;
            }

            try
            {
                await using var stream = File.OpenRead(path);
                var loaded = await JsonSerializer.DeserializeAsync<AppSettings>(stream, _jsonOptions).ConfigureAwait(false);
                Current = Normalize(loaded ?? new AppSettings());
            }
            catch (Exception error) when (error is JsonException or IOException or UnauthorizedAccessException)
            {
                TryBackUpCorruptSettings(path, error);
                Current = new AppSettings { StartWithWindows = IsStartupRegistered() };
            }
        }
        finally
        {
            _gate.Release();
        }
    }

    public async Task SaveAsync()
    {
        await _gate.WaitAsync().ConfigureAwait(false);
        try
        {
            Directory.CreateDirectory(_settingsDirectory);
            var temporary = SettingsPath + ".tmp";
            await using (var stream = new FileStream(temporary, FileMode.Create, FileAccess.Write, FileShare.None))
            {
                await JsonSerializer.SerializeAsync(stream, Current, _jsonOptions).ConfigureAwait(false);
                await stream.FlushAsync().ConfigureAwait(false);
            }
            File.Move(temporary, SettingsPath, overwrite: true);
            ApplyStartupPreference();
        }
        finally
        {
            _gate.Release();
        }
    }

    public async Task RememberDeviceAsync(TrikiDeviceInfo device)
    {
        Current.KnownDeviceAddressHex = device.BluetoothAddress.ToString("X12");
        Current.KnownDeviceName = device.Name;
        await SaveAsync().ConfigureAwait(false);
    }

    public async Task ForgetDeviceAsync()
    {
        Current.KnownDeviceAddressHex = null;
        Current.KnownDeviceName = null;
        await SaveAsync().ConfigureAwait(false);
    }

    private string SettingsPath => Path.Combine(_settingsDirectory, "settings.json");

    internal static AppSettings Normalize(AppSettings settings)
    {
        settings.Theme = settings.Theme is "System" or "Light" or "Dark" ? settings.Theme : "System";
        settings.SingleClickAction = NormalizeAction(settings.SingleClickAction, MediaAction.PlayPause);
        settings.DoubleClickAction = NormalizeAction(settings.DoubleClickAction, MediaAction.Like);
        settings.TripleClickAction = NormalizeAction(settings.TripleClickAction, MediaAction.Dislike);
        if (settings.SingleClickAction == MediaAction.PlayPause &&
            settings.DoubleClickAction == MediaAction.Next &&
            settings.TripleClickAction == MediaAction.Previous)
        {
            settings.DoubleClickAction = MediaAction.Like;
            settings.TripleClickAction = MediaAction.Dislike;
        }
        if (settings.ConnectOnlyWhenNeeded) settings.AutoReconnect = true;

        if (!ulong.TryParse(settings.KnownDeviceAddressHex, NumberStyles.HexNumber, CultureInfo.InvariantCulture, out var address) ||
            address is 0 or > 0xFFFFFFFFFFFF)
        {
            settings.KnownDeviceAddressHex = null;
            settings.KnownDeviceName = null;
        }
        else
        {
            settings.KnownDeviceAddressHex = address.ToString("X12", CultureInfo.InvariantCulture);
            settings.KnownDeviceName = string.IsNullOrWhiteSpace(settings.KnownDeviceName) ? "Triki" : settings.KnownDeviceName.Trim();
        }

        settings.Calibration = NormalizeCalibration(settings.Calibration);
        if (settings.RotationAngleDegrees is < 90 or > 360)
            settings.RotationAngleDegrees = 200;
        return settings;
    }

    private static MediaAction NormalizeAction(MediaAction action, MediaAction fallback) =>
        Enum.IsDefined(action) ? action : fallback;

    internal static CalibrationProfile NormalizeCalibration(CalibrationProfile? calibration)
    {
        if (calibration is null ||
            !InRange(calibration.AccelerometerBiasX, 4f) ||
            !InRange(calibration.AccelerometerBiasY, 4f) ||
            !InRange(calibration.AccelerometerBiasZ, 4f) ||
            !InRange(calibration.GyroscopeBiasX, 2_000f) ||
            !InRange(calibration.GyroscopeBiasY, 2_000f) ||
            !InRange(calibration.GyroscopeBiasZ, 2_000f) ||
            !InRange(calibration.AccelerometerNoise, 4f, minimum: 0f) ||
            !InRange(calibration.GyroscopeNoise, 2_000f, minimum: 0f) ||
            calibration.SampleCount < 0)
        {
            return new CalibrationProfile();
        }

        return calibration with
        {
            NeutralPitch = NormalizeDegrees(calibration.NeutralPitch),
            NeutralRoll = NormalizeDegrees(calibration.NeutralRoll),
        };
    }

    private static bool InRange(float value, float maximum, float minimum = float.NegativeInfinity) =>
        float.IsFinite(value) && value >= minimum && value <= maximum && value >= -maximum;

    private static float NormalizeDegrees(float value)
    {
        if (!float.IsFinite(value)) return 0f;
        var normalized = MathF.IEEERemainder(value, 360f);
        return normalized == -180f ? 180f : normalized;
    }

    private void TryBackUpCorruptSettings(string path, Exception originalError)
    {
        try
        {
            var backup = Path.Combine(
                _settingsDirectory,
                $"settings-corrupt-{DateTimeOffset.UtcNow:yyyyMMddHHmmssfff}-{Guid.NewGuid():N}.json");
            File.Copy(path, backup, overwrite: false);
        }
        catch (Exception backupError) when (backupError is IOException or UnauthorizedAccessException)
        {
            Debug.WriteLine($"Nie udało się zachować uszkodzonych ustawień. Odczyt: {originalError}; kopia: {backupError}");
        }
    }

    private void ApplyStartupPreference()
    {
        var executable = Environment.ProcessPath
            ?? throw new InvalidOperationException("Nie można ustalić ścieżki aplikacji.");
        if (IsDevelopmentExecutable(executable))
        {
            System.Diagnostics.Debug.WriteLine("Pominięto modyfikację autostartu dla kompilacji uruchomionej z katalogu bin.");
            return;
        }
        using var key = Registry.CurrentUser.CreateSubKey(RunRegistryPath, writable: true)
            ?? throw new InvalidOperationException("Nie można otworzyć ustawień autostartu Windows.");
        if (Current.StartWithWindows)
        {
            key.SetValue(RunRegistryValue, $"\"{executable}\" --background", RegistryValueKind.String);
        }
        else
        {
            key.DeleteValue(RunRegistryValue, throwOnMissingValue: false);
        }
    }

    private static bool IsDevelopmentExecutable(string executable)
    {
        var segments = Path.GetFullPath(executable)
            .Split(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
        return segments.Any(segment => segment.Equals("bin", StringComparison.OrdinalIgnoreCase));
    }

    private static bool IsStartupRegistered()
    {
        using var key = Registry.CurrentUser.OpenSubKey(RunRegistryPath, writable: false);
        return key?.GetValue(RunRegistryValue) is string value && !string.IsNullOrWhiteSpace(value);
    }
}
