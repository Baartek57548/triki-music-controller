using TrikiMusicController_Windows.Models;
using TrikiMusicController_Windows.Services;

namespace TrikiMusicController.Windows.Tests;

public sealed class BluetoothConfigurationTests
{
    [Fact]
    public async Task CancelledIdleRequestDoesNotDisconnectTheController()
    {
        using var service = new BluetoothService(BluetoothSnapshot.Initial with { ConnectionState = TrikiConnectionState.Ready });
        service.ConfigureRememberedDevice(0xAABBCCDDEEFF, "Triki", true, true);
        await service.ParkUntilWakeAsync(() => false);
        Assert.Equal(TrikiConnectionState.Ready, service.State.ConnectionState);
    }

    [Fact]
    public void RememberedDeviceIsReadyForAdvertisementsWithoutWaitingForSilence()
    {
        using var service = new BluetoothService();
        service.ConfigureRememberedDevice(0xAABBCCDDEEFF, "Triki", true, true);
        Assert.Equal(TrikiConnectionState.WaitingForWake, service.State.ConnectionState);
        Assert.True(service.State.WakeWatcherArmed);
    }

    [Fact]
    public async Task DeliberateEcoParkingUsesTheBoundedWakePause()
    {
        using var service = new BluetoothService(BluetoothSnapshot.Initial with { ConnectionState = TrikiConnectionState.Ready });
        service.ConfigureRememberedDevice(0xAABBCCDDEEFF, "Triki", true, true);
        await service.ParkUntilWakeAsync();
        Assert.Equal(TrikiConnectionState.WaitingForWake, service.State.ConnectionState);
        Assert.False(service.State.WakeWatcherArmed);
    }

    [Fact]
    public async Task ExplicitDisconnectStaysDisconnectedUntilUserConnects()
    {
        using var service = new BluetoothService();
        service.ConfigureRememberedDevice(0xAABBCCDDEEFF, "Triki", true, true);
        await service.DisconnectAsync(forgetDevice: false);
        service.ConfigureRememberedDevice(0xAABBCCDDEEFF, "Triki", true, true);
        Assert.Equal(TrikiConnectionState.Disconnected, service.State.ConnectionState);
        Assert.False(service.State.WakeWatcherArmed);
    }

    [Theory]
    [InlineData(TrikiConnectionState.Ready)]
    [InlineData(TrikiConnectionState.Connecting)]
    public void ConnectionModeAndArbitrationChangesPreserveActiveConnection(TrikiConnectionState connectionState)
    {
        using var service = new BluetoothService(BluetoothSnapshot.Initial with { ConnectionState = connectionState });
        service.ConfigureRememberedDevice(0xAABBCCDDEEFF, "Triki", true, true);
        service.ConfigureRememberedDevice(0xAABBCCDDEEFF, "Triki", true, false, MultiDeviceArbitrationMode.AlwaysConnect);
        Assert.Equal(connectionState, service.State.ConnectionState);
        service.ConfigureRememberedDevice(0xAABBCCDDEEFF, "Triki", false, false);
        Assert.Equal(connectionState, service.State.ConnectionState);
    }
}
