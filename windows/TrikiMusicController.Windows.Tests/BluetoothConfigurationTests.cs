using TrikiMusicController_Windows.Models;
using TrikiMusicController_Windows.Services;

namespace TrikiMusicController.Windows.Tests;

public sealed class BluetoothConfigurationTests
{
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
