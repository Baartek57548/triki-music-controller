using System.Text.Json;
using System.Text.Json.Serialization;
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
    private readonly string _settingsDirectory = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "TrikiMusicController");

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
                Current = await JsonSerializer.DeserializeAsync<AppSettings>(stream, _jsonOptions).ConfigureAwait(false)
                    ?? new AppSettings();
            }
            catch (Exception error) when (error is JsonException or IOException or UnauthorizedAccessException)
            {
                var backup = Path.Combine(
                    _settingsDirectory,
                    $"settings-corrupt-{DateTimeOffset.UtcNow:yyyyMMddHHmmssfff}-{Guid.NewGuid():N}.json");
                File.Copy(path, backup, overwrite: false);
                Current = new AppSettings();
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
