using System.Globalization;
using System.Text.Json;
using TrikiMusicController_Windows.Models;
using TrikiMusicController_Windows.Services;
using Xunit;

namespace TrikiMusicController.Windows.Tests;

public sealed class SettingsServiceTests : IDisposable
{
    private readonly string _tempDirectory;

    public SettingsServiceTests()
    {
        _tempDirectory = Path.Combine(Path.GetTempPath(), "TrikiSettingsTests_" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(_tempDirectory);
    }

    public void Dispose()
    {
        try
        {
            if (Directory.Exists(_tempDirectory))
            {
                Directory.Delete(_tempDirectory, recursive: true);
            }
        }
        catch
        {
            // Ignore test cleanup errors
        }
    }

    [Fact]
    public void Normalize_ValidSettings_PreservesProperties()
    {
        var original = new AppSettings
        {
            Theme = "Dark",
            SingleClickAction = MediaAction.PlayPause,
            DoubleClickAction = MediaAction.Like,
            TripleClickAction = MediaAction.Dislike,
            ConnectOnlyWhenNeeded = false,
            AutoReconnect = true,
            KnownDeviceAddressHex = "001122334455",
            KnownDeviceName = "Triki Custom",
            RotationAngleDegrees = 180,
            Calibration = new CalibrationProfile
            {
                AccelerometerBiasX = 0.05f,
                AccelerometerBiasY = -0.02f,
                AccelerometerBiasZ = 0.01f,
                GyroscopeBiasX = 1.2f,
                GyroscopeBiasY = -0.8f,
                GyroscopeBiasZ = 0.4f,
                AccelerometerNoise = 0.01f,
                GyroscopeNoise = 0.5f,
                SampleCount = 100,
                NeutralPitch = 15f,
                NeutralRoll = -20f,
            },
        };

        var normalized = SettingsService.Normalize(original);

        Assert.Equal("Dark", normalized.Theme);
        Assert.Equal(MediaAction.PlayPause, normalized.SingleClickAction);
        Assert.Equal(MediaAction.Like, normalized.DoubleClickAction);
        Assert.Equal(MediaAction.Dislike, normalized.TripleClickAction);
        Assert.Equal("001122334455", normalized.KnownDeviceAddressHex);
        Assert.Equal("Triki Custom", normalized.KnownDeviceName);
        Assert.Equal(180, normalized.RotationAngleDegrees);
        Assert.Equal(100, normalized.Calibration.SampleCount);
        Assert.Equal(15f, normalized.Calibration.NeutralPitch);
        Assert.Equal(-20f, normalized.Calibration.NeutralRoll);
    }

    [Fact]
    public void Normalize_InvalidTheme_FallsBackToSystem()
    {
        var settings = new AppSettings { Theme = "CyberpunkNeon" };
        var normalized = SettingsService.Normalize(settings);
        Assert.Equal("System", normalized.Theme);
    }

    [Fact]
    public void Normalize_UndefinedMediaAction_FallsBackToDefaults()
    {
        var settings = new AppSettings
        {
            SingleClickAction = (MediaAction)999,
            DoubleClickAction = (MediaAction)888,
            TripleClickAction = (MediaAction)777,
        };

        var normalized = SettingsService.Normalize(settings);

        Assert.Equal(MediaAction.PlayPause, normalized.SingleClickAction);
        Assert.Equal(MediaAction.Like, normalized.DoubleClickAction);
        Assert.Equal(MediaAction.Dislike, normalized.TripleClickAction);
    }

    [Fact]
    public void Normalize_LegacyDefaultActions_MigratesToLikeAndDislike()
    {
        var legacy = new AppSettings
        {
            SingleClickAction = MediaAction.PlayPause,
            DoubleClickAction = MediaAction.Next,
            TripleClickAction = MediaAction.Previous,
        };

        var normalized = SettingsService.Normalize(legacy);

        Assert.Equal(MediaAction.PlayPause, normalized.SingleClickAction);
        Assert.Equal(MediaAction.Like, normalized.DoubleClickAction);
        Assert.Equal(MediaAction.Dislike, normalized.TripleClickAction);
    }

    [Fact]
    public void Normalize_ConnectOnlyWhenNeeded_EnforcesAutoReconnect()
    {
        var settings = new AppSettings
        {
            ConnectOnlyWhenNeeded = true,
            AutoReconnect = false,
        };

        var normalized = SettingsService.Normalize(settings);

        Assert.True(normalized.AutoReconnect);
    }

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("   ")]
    [InlineData("ZZZZZZZZZZZZ")]
    [InlineData("0")]
    [InlineData("1000000000000")] // Exceeds 48-bit (0xFFFFFFFFFFFF)
    public void Normalize_InvalidDeviceAddressHex_NullsAddressAndName(string? invalidAddress)
    {
        var settings = new AppSettings
        {
            KnownDeviceAddressHex = invalidAddress,
            KnownDeviceName = "Triki Fake",
        };

        var normalized = SettingsService.Normalize(settings);

        Assert.Null(normalized.KnownDeviceAddressHex);
        Assert.Null(normalized.KnownDeviceName);
    }

    [Fact]
    public void Normalize_ValidHexAddress_FormatsTo12HexDigitsAndDefaultsNameIfEmpty()
    {
        var settings = new AppSettings
        {
            KnownDeviceAddressHex = "A1B2C3",
            KnownDeviceName = "  ",
        };

        var normalized = SettingsService.Normalize(settings);

        Assert.Equal("000000A1B2C3", normalized.KnownDeviceAddressHex);
        Assert.Equal("Triki", normalized.KnownDeviceName);
    }

    [Theory]
    [InlineData(89, 200)]
    [InlineData(361, 200)]
    [InlineData(90, 90)]
    [InlineData(200, 200)]
    [InlineData(360, 360)]
    public void Normalize_RotationAngleDegrees_ClampsOrPreserves(int input, int expected)
    {
        var settings = new AppSettings { RotationAngleDegrees = input };
        var normalized = SettingsService.Normalize(settings);
        Assert.Equal(expected, normalized.RotationAngleDegrees);
    }

    [Fact]
    public void NormalizeCalibration_NullOrInvalidBias_ResetsToDefaults()
    {
        Assert.Equal(0, SettingsService.NormalizeCalibration(null).SampleCount);

        var excessiveAccel = new CalibrationProfile { AccelerometerBiasX = 5.0f, SampleCount = 50 };
        Assert.Equal(0, SettingsService.NormalizeCalibration(excessiveAccel).SampleCount);

        var excessiveGyro = new CalibrationProfile { GyroscopeBiasY = 2500f, SampleCount = 50 };
        Assert.Equal(0, SettingsService.NormalizeCalibration(excessiveGyro).SampleCount);

        var negativeNoise = new CalibrationProfile { AccelerometerNoise = -0.1f, SampleCount = 50 };
        Assert.Equal(0, SettingsService.NormalizeCalibration(negativeNoise).SampleCount);

        var negativeCount = new CalibrationProfile { SampleCount = -1 };
        Assert.Equal(0, SettingsService.NormalizeCalibration(negativeCount).SampleCount);
    }

    [Fact]
    public void NormalizeCalibration_NeutralAngles_WrapsDegreesWithinRange()
    {
        var profile = new CalibrationProfile
        {
            NeutralPitch = 450f, // 450 % 360 = 90
            NeutralRoll = -270f, // -270 % 360 = 90
            SampleCount = 10,
        };

        var normalized = SettingsService.NormalizeCalibration(profile);

        Assert.Equal(90f, normalized.NeutralPitch);
        Assert.Equal(90f, normalized.NeutralRoll);
        Assert.Equal(10, normalized.SampleCount);
    }

    [Fact]
    public async Task LoadAsync_WhenFileDoesNotExist_InitializesDefaults()
    {
        var service = new SettingsService(_tempDirectory);
        await service.LoadAsync();

        Assert.NotNull(service.Current);
        Assert.Equal("System", service.Current.Theme);
        Assert.Equal(MediaAction.PlayPause, service.Current.SingleClickAction);
        Assert.Equal(MediaAction.Like, service.Current.DoubleClickAction);
        Assert.Equal(MediaAction.Dislike, service.Current.TripleClickAction);
    }

    [Fact]
    public async Task SaveAsync_And_LoadAsync_RoundtripsSuccessfully()
    {
        var service = new SettingsService(_tempDirectory);
        await service.LoadAsync();

        service.Current.Theme = "Dark";
        service.Current.SingleClickAction = MediaAction.Next;
        service.Current.DoubleClickAction = MediaAction.Previous;
        service.Current.TripleClickAction = MediaAction.PlayPause;
        service.Current.RotationAngleDegrees = 240;
        service.Current.EnableToastNotifications = false;

        await service.SaveAsync();

        var service2 = new SettingsService(_tempDirectory);
        await service2.LoadAsync();

        Assert.Equal("Dark", service2.Current.Theme);
        Assert.Equal(MediaAction.Next, service2.Current.SingleClickAction);
        Assert.Equal(MediaAction.Previous, service2.Current.DoubleClickAction);
        Assert.Equal(MediaAction.PlayPause, service2.Current.TripleClickAction);
        Assert.Equal(240, service2.Current.RotationAngleDegrees);
        Assert.False(service2.Current.EnableToastNotifications);
    }

    [Fact]
    public async Task LoadAsync_WhenCorruptedJson_CreatesBackupAndRestoresDefaults()
    {
        var settingsFilePath = Path.Combine(_tempDirectory, "settings.json");
        await File.WriteAllTextAsync(settingsFilePath, "{ invalid json structure ::: 999 }");

        var service = new SettingsService(_tempDirectory);
        await service.LoadAsync();

        Assert.NotNull(service.Current);
        Assert.Equal("System", service.Current.Theme);

        var backupFiles = Directory.GetFiles(_tempDirectory, "settings-corrupt-*.json");
        Assert.Single(backupFiles);
    }

    [Fact]
    public async Task RememberDeviceAsync_And_ForgetDeviceAsync_PersistsCorrectly()
    {
        var service = new SettingsService(_tempDirectory);
        await service.LoadAsync();

        var device = new TrikiDeviceInfo(0xAABBCCDDEEFF, "Triki Remote", -45);
        await service.RememberDeviceAsync(device);

        Assert.Equal("AABBCCDDEEFF", service.Current.KnownDeviceAddressHex);
        Assert.Equal("Triki Remote", service.Current.KnownDeviceName);

        var serviceReloaded = new SettingsService(_tempDirectory);
        await serviceReloaded.LoadAsync();
        Assert.Equal("AABBCCDDEEFF", serviceReloaded.Current.KnownDeviceAddressHex);
        Assert.Equal("Triki Remote", serviceReloaded.Current.KnownDeviceName);

        await serviceReloaded.ForgetDeviceAsync();
        Assert.Null(serviceReloaded.Current.KnownDeviceAddressHex);
        Assert.Null(serviceReloaded.Current.KnownDeviceName);

        var serviceFinal = new SettingsService(_tempDirectory);
        await serviceFinal.LoadAsync();
        Assert.Null(serviceFinal.Current.KnownDeviceAddressHex);
        Assert.Null(serviceFinal.Current.KnownDeviceName);
    }
}
