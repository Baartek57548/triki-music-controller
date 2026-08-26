using System.Diagnostics;
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.Advertisement;
using Windows.Devices.Bluetooth.GenericAttributeProfile;
using Windows.Storage.Streams;
using TrikiMusicController_Windows.Core;
using TrikiMusicController_Windows.Models;

namespace TrikiMusicController_Windows.Services;

public sealed class BluetoothService : IDisposable
{
    private const long SampleRateWindowNanos = 2_000_000_000;
    private readonly object _stateLock = new();
    private readonly SemaphoreSlim _connectionGate = new(1, 1);
    private readonly TrikiProtocolDecoder _decoder = new();
    private readonly WakeAdvertisementGate _wakeAdvertisementGate = new();
    private readonly Dictionary<ulong, TrikiDeviceInfo> _discovered = [];
    private readonly Queue<long> _sampleTimestamps = new();
    private BluetoothLEAdvertisementWatcher? _watcher;
    private BluetoothLEDevice? _device;
    private GattDeviceService? _nusService;
    private GattCharacteristic? _txCharacteristic;
    private GattCharacteristic? _rxCharacteristic;
    private GattCharacteristic? _ledCharacteristic;
    private CancellationTokenSource? _wakeArmCancellation;
    private ulong? _knownAddress;
    private string? _knownName;
    private bool _autoReconnect = true;
    private volatile bool _connectOnlyWhenNeeded;
    private volatile bool _waitingForWake;
    private volatile bool _wakeWatcherArmed;
    private bool _manualDisconnect;
    private bool _disposed;

    public event EventHandler<BluetoothSnapshot>? StateChanged;
    public event EventHandler<TrikiSensorData>? SampleReceived;
    public BluetoothSnapshot State { get; private set; } = BluetoothSnapshot.Initial;

    public void ConfigureRememberedDevice(
        ulong? address,
        string? name,
        bool autoReconnect,
        bool connectOnlyWhenNeeded)
    {
        var configurationChanged = _knownAddress != address ||
            _autoReconnect != autoReconnect ||
            _connectOnlyWhenNeeded != connectOnlyWhenNeeded;
        _knownAddress = address;
        _knownName = string.IsNullOrWhiteSpace(name) ? "Triki" : name;
        _autoReconnect = autoReconnect;
        _connectOnlyWhenNeeded = connectOnlyWhenNeeded;
        _manualDisconnect = false;
        if (!configurationChanged) return;
        if (!autoReconnect || address is null)
        {
            CancelWakeWatcher();
            if (State.ConnectionState is not TrikiConnectionState.Ready and not TrikiConnectionState.Connecting)
                UpdateState(state => state with { ConnectionState = TrikiConnectionState.Disconnected, WakeWatcherArmed = false });
            return;
        }
        if (connectOnlyWhenNeeded && State.ConnectionState is not TrikiConnectionState.Ready and not TrikiConnectionState.Connecting)
        {
            BeginWaitingForWake();
        }
        else if (!connectOnlyWhenNeeded)
        {
            CancelWakeWatcher();
            if (_knownAddress is not null && _autoReconnect)
                UpdateState(state => state with { ConnectionState = TrikiConnectionState.WaitingForDevice, WakeWatcherArmed = false });
        }
    }

    public Task StartAsync()
    {
        ThrowIfDisposed();
        if (_watcher?.Status == BluetoothLEAdvertisementWatcherStatus.Aborted)
            DisposeWatcher();
        EnsureWatcher();
        if (_watcher!.Status is BluetoothLEAdvertisementWatcherStatus.Created or BluetoothLEAdvertisementWatcherStatus.Stopped)
            _watcher.Start();
        UpdateState(state => state with
        {
            ConnectionState = _knownAddress is not null && _autoReconnect
                ? (_connectOnlyWhenNeeded ? TrikiConnectionState.WaitingForWake : TrikiConnectionState.WaitingForDevice)
                : TrikiConnectionState.Scanning,
            WakeWatcherArmed = false,
            ErrorMessage = null,
        });
        if (_knownAddress is not null && _autoReconnect && _connectOnlyWhenNeeded) BeginWaitingForWake();
        return Task.CompletedTask;
    }

    public async Task ConnectAsync(TrikiDeviceInfo target, bool userInitiated = true)
    {
        ThrowIfDisposed();
        await _connectionGate.WaitAsync().ConfigureAwait(false);
        try
        {
            if (_device?.BluetoothAddress == target.BluetoothAddress && State.ConnectionState == TrikiConnectionState.Ready) return;
            _manualDisconnect = false;
            CancelWakeWatcher();
            UpdateState(state => state with
            {
                ConnectionState = TrikiConnectionState.Connecting,
                ConnectedDevice = target,
                WakeWatcherArmed = false,
                ErrorMessage = null,
            });
            CleanupConnection();
            _decoder.Reset();
            lock (_stateLock) _sampleTimestamps.Clear();

            _device = await BluetoothLEDevice.FromBluetoothAddressAsync(target.BluetoothAddress);
            if (_device is null) throw new IOException("Windows nie może otworzyć urządzenia BLE. Wybudź Triki i spróbuj ponownie.");
            _device.ConnectionStatusChanged += DeviceOnConnectionStatusChanged;

            var serviceResult = await _device.GetGattServicesForUuidAsync(TrikiProtocol.NusServiceUuid, BluetoothCacheMode.Uncached);
            if (serviceResult.Status != GattCommunicationStatus.Success || serviceResult.Services.Count == 0)
                throw new IOException($"Triki nie udostępniło usługi Nordic UART ({serviceResult.Status}).");
            _nusService = serviceResult.Services[0];

            _txCharacteristic = await GetRequiredCharacteristicAsync(_nusService, TrikiProtocol.NusTxUuid, "NUS TX");
            _rxCharacteristic = await GetRequiredCharacteristicAsync(_nusService, TrikiProtocol.NusRxUuid, "NUS RX");
            _ledCharacteristic = await GetOptionalCharacteristicAsync(_nusService, TrikiProtocol.LedUuid);

            _txCharacteristic.ValueChanged += TxCharacteristicOnValueChanged;
            var notificationStatus = await _txCharacteristic.WriteClientCharacteristicConfigurationDescriptorAsync(
                GattClientCharacteristicConfigurationDescriptorValue.Notify);
            if (notificationStatus != GattCommunicationStatus.Success)
                throw new IOException($"Nie udało się włączyć notyfikacji IMU ({notificationStatus}).");

            using var writer = new DataWriter();
            writer.WriteBytes(TrikiProtocol.StartStreamCommand);
            var writeResult = await _rxCharacteristic.WriteValueWithResultAsync(
                writer.DetachBuffer(),
                GattWriteOption.WriteWithoutResponse);
            if (writeResult.Status != GattCommunicationStatus.Success)
                throw new IOException($"Triki odrzuciło komendę uruchomienia strumienia ({writeResult.Status}).");

            var connected = new TrikiDeviceInfo(
                target.BluetoothAddress,
                string.IsNullOrWhiteSpace(_device.Name) ? target.Name : _device.Name,
                target.Rssi);
            if (userInitiated)
            {
                _knownAddress = connected.BluetoothAddress;
                _knownName = connected.Name;
            }
            UpdateState(state => state with
            {
                ConnectionState = TrikiConnectionState.Ready,
                ConnectedDevice = connected,
                ErrorMessage = null,
                DecodedFrames = 0,
                DiscardedStartupFrames = 0,
                DroppedProtocolBytes = 0,
                WakeWatcherArmed = false,
            });
            _ = RefreshBatteryAsync(_device);
        }
        catch (Exception error)
        {
            CleanupConnection();
            var shouldWait = !_manualDisconnect && _autoReconnect && _knownAddress is not null;
            if (shouldWait && _connectOnlyWhenNeeded)
            {
                BeginWaitingForWake(error.Message);
            }
            else
            {
                UpdateState(state => state with
                {
                    ConnectionState = shouldWait ? TrikiConnectionState.WaitingForDevice : TrikiConnectionState.Error,
                    WakeWatcherArmed = false,
                    ErrorMessage = error.Message,
                });
            }
            if (userInitiated) throw;
        }
        finally
        {
            _connectionGate.Release();
        }
    }

    public async Task DisconnectAsync(bool forgetDevice)
    {
        ThrowIfDisposed();
        await _connectionGate.WaitAsync().ConfigureAwait(false);
        try
        {
            _manualDisconnect = true;
            CancelWakeWatcher();
            if (forgetDevice)
            {
                _knownAddress = null;
                _knownName = null;
            }
            CleanupConnection();
            _decoder.Reset();
            lock (_stateLock) _sampleTimestamps.Clear();
            PublishSnapshot(BluetoothSnapshot.Initial with
            {
                ConnectionState = TrikiConnectionState.Disconnected,
                DiscoveredDevices = SnapshotDiscoveredDevices(),
            });
        }
        finally
        {
            _connectionGate.Release();
        }
    }

    public async Task ParkUntilWakeAsync()
    {
        ThrowIfDisposed();
        if (!_connectOnlyWhenNeeded || !_autoReconnect || _knownAddress is null) return;
        await _connectionGate.WaitAsync().ConfigureAwait(false);
        try
        {
            if (State.ConnectionState != TrikiConnectionState.Ready) return;
            _manualDisconnect = false;
            CleanupConnection();
            _decoder.Reset();
            lock (_stateLock) _sampleTimestamps.Clear();
            BeginWaitingForWake();
        }
        finally
        {
            _connectionGate.Release();
        }
    }

    public async Task SetLedAsync(bool enabled)
    {
        ThrowIfDisposed();
        var characteristic = _ledCharacteristic ?? throw new InvalidOperationException("Triki nie udostępnia sterowania diodą LED.");
        using var writer = new DataWriter();
        writer.WriteByte(enabled ? (byte)1 : (byte)0);
        var result = await characteristic.WriteValueWithResultAsync(writer.DetachBuffer(), GattWriteOption.WriteWithResponse);
        if (result.Status != GattCommunicationStatus.Success)
            throw new IOException($"Nie udało się zmienić stanu LED ({result.Status}).");
    }

    private void EnsureWatcher()
    {
        if (_watcher is not null) return;
        _watcher = new BluetoothLEAdvertisementWatcher
        {
            ScanningMode = BluetoothLEScanningMode.Active,
            AllowExtendedAdvertisements = true,
        };
        _watcher.Received += WatcherOnReceived;
        _watcher.Stopped += WatcherOnStopped;
    }

    private void WatcherOnReceived(BluetoothLEAdvertisementWatcher sender, BluetoothLEAdvertisementReceivedEventArgs args)
    {
        if (_disposed) return;
        var advertisedName = args.Advertisement.LocalName;
        var knownMatch = _knownAddress == args.BluetoothAddress;
        if (!knownMatch && (string.IsNullOrWhiteSpace(advertisedName) || !advertisedName.Contains("Triki", StringComparison.OrdinalIgnoreCase))) return;

        var device = new TrikiDeviceInfo(
            args.BluetoothAddress,
            string.IsNullOrWhiteSpace(advertisedName) ? _knownName ?? "Triki" : advertisedName,
            args.RawSignalStrengthInDBm);
        lock (_stateLock) _discovered[device.BluetoothAddress] = device;
        UpdateState(state => state with { DiscoveredDevices = SnapshotDiscoveredDevices() });

        if (!knownMatch || !_autoReconnect || _manualDisconnect ||
            State.ConnectionState is TrikiConnectionState.Connecting or TrikiConnectionState.Ready) return;

        if (_connectOnlyWhenNeeded && _waitingForWake)
        {
            if (!_wakeAdvertisementGate.ObserveAdvertisement(TimestampNanos())) return;
            CancelWakeWatcher();
        }
        _ = ConnectAsync(device, userInitiated: false);
    }

    private void WatcherOnStopped(BluetoothLEAdvertisementWatcher sender, BluetoothLEAdvertisementWatcherStoppedEventArgs args)
    {
        if (_disposed || args.Error == BluetoothError.Success) return;
        UpdateState(state => state with { ConnectionState = TrikiConnectionState.Error, ErrorMessage = $"Skaner Bluetooth zatrzymał się: {args.Error}. Ponawianie…" });
        _ = RestartWatcherAsync();
    }

    private void DeviceOnConnectionStatusChanged(BluetoothLEDevice sender, object args)
    {
        if (_disposed || sender.ConnectionStatus == BluetoothConnectionStatus.Connected) return;
        _ = HandleUnexpectedDisconnectAsync(sender);
    }

    private async Task HandleUnexpectedDisconnectAsync(BluetoothLEDevice sender)
    {
        await _connectionGate.WaitAsync().ConfigureAwait(false);
        try
        {
            if (_disposed || !ReferenceEquals(sender, _device)) return;
            CleanupConnection();
            if (!_manualDisconnect && _autoReconnect && _knownAddress is not null && _connectOnlyWhenNeeded)
            {
                BeginWaitingForWake();
            }
            else
            {
                UpdateState(state => state with
                {
                    ConnectionState = !_manualDisconnect && _autoReconnect && _knownAddress is not null
                        ? TrikiConnectionState.WaitingForDevice
                        : TrikiConnectionState.Disconnected,
                    ConnectedDevice = null,
                    WakeWatcherArmed = false,
                    ErrorMessage = null,
                });
            }
        }
        catch (Exception error)
        {
            if (!_disposed) UpdateState(state => state with { ConnectionState = TrikiConnectionState.Error, ErrorMessage = error.Message });
        }
        finally
        {
            _connectionGate.Release();
        }
    }

    private void TxCharacteristicOnValueChanged(GattCharacteristic sender, GattValueChangedEventArgs args)
    {
        try
        {
            using var reader = DataReader.FromBuffer(args.CharacteristicValue);
            var bytes = new byte[reader.UnconsumedBufferLength];
            reader.ReadBytes(bytes);
            var samples = _decoder.Decode(bytes, TimestampNanos());
            foreach (var sample in samples)
            {
                lock (_stateLock)
                {
                    _sampleTimestamps.Enqueue(sample.TimestampNanos);
                    while (_sampleTimestamps.Count > 2 && sample.TimestampNanos - _sampleTimestamps.Peek() > SampleRateWindowNanos)
                        _sampleTimestamps.Dequeue();
                }
                SampleReceived?.Invoke(this, sample);
            }
            var statistics = _decoder.Statistics;
            UpdateState(state => state with
            {
                SampleRateHz = CalculateSampleRate(),
                DecodedFrames = statistics.DecodedFrames,
                DiscardedStartupFrames = statistics.DiscardedStartupFrames,
                DroppedProtocolBytes = statistics.DroppedBytes,
            });
        }
        catch (Exception error)
        {
            UpdateState(state => state with { ErrorMessage = $"Błąd dekodowania strumienia Triki: {error.Message}" });
        }
    }

    private async Task RefreshBatteryAsync(BluetoothLEDevice device)
    {
        try
        {
            var serviceResult = await device.GetGattServicesForUuidAsync(TrikiProtocol.BatteryServiceUuid, BluetoothCacheMode.Uncached);
            if (serviceResult.Status != GattCommunicationStatus.Success || serviceResult.Services.Count == 0) return;
            using var service = serviceResult.Services[0];
            var characteristicResult = await service.GetCharacteristicsForUuidAsync(TrikiProtocol.BatteryLevelUuid, BluetoothCacheMode.Uncached);
            if (characteristicResult.Status != GattCommunicationStatus.Success || characteristicResult.Characteristics.Count == 0) return;
            var read = await characteristicResult.Characteristics[0].ReadValueAsync(BluetoothCacheMode.Uncached);
            if (read.Status != GattCommunicationStatus.Success || read.Value.Length == 0) return;
            using var reader = DataReader.FromBuffer(read.Value);
            var percent = reader.ReadByte();
            if (percent <= 100) UpdateState(state => state with { BatteryPercent = percent });
        }
        catch (Exception error)
        {
            // Battery Service is optional in known Triki firmware and must not break IMU control.
            Debug.WriteLine($"Nie udało się odczytać opcjonalnej baterii Triki: {error}");
        }
    }

    private static async Task<GattCharacteristic> GetRequiredCharacteristicAsync(GattDeviceService service, Guid uuid, string label)
    {
        var result = await service.GetCharacteristicsForUuidAsync(uuid, BluetoothCacheMode.Uncached);
        if (result.Status != GattCommunicationStatus.Success || result.Characteristics.Count == 0)
            throw new IOException($"Brak wymaganej charakterystyki {label} ({result.Status}).");
        return result.Characteristics[0];
    }

    private static async Task<GattCharacteristic?> GetOptionalCharacteristicAsync(GattDeviceService service, Guid uuid)
    {
        var result = await service.GetCharacteristicsForUuidAsync(uuid, BluetoothCacheMode.Uncached);
        return result.Status == GattCommunicationStatus.Success ? result.Characteristics.FirstOrDefault() : null;
    }

    private float? CalculateSampleRate()
    {
        lock (_stateLock)
        {
            if (_sampleTimestamps.Count < 10) return null;
            var samples = _sampleTimestamps.ToArray();
            var duration = samples[^1] - samples[0];
            return duration <= 0 ? null : (samples.Length - 1) * 1_000_000_000f / duration;
        }
    }

    private IReadOnlyList<TrikiDeviceInfo> SnapshotDiscoveredDevices()
    {
        lock (_stateLock)
            return _discovered.Values.OrderByDescending(device => device.BluetoothAddress == _knownAddress).ThenByDescending(device => device.Rssi).ToArray();
    }

    private void BeginWaitingForWake(string? errorMessage = null)
    {
        if (_disposed || _knownAddress is null || !_autoReconnect || !_connectOnlyWhenNeeded) return;
        CancelWakeWatcher();
        _waitingForWake = true;
        _wakeWatcherArmed = false;
        _wakeAdvertisementGate.Reset(TimestampNanos());
        var cancellation = new CancellationTokenSource();
        _wakeArmCancellation = cancellation;
        UpdateState(state => state with
        {
            ConnectionState = TrikiConnectionState.WaitingForWake,
            ConnectedDevice = null,
            SampleRateHz = null,
            DecodedFrames = 0,
            DiscardedStartupFrames = 0,
            DroppedProtocolBytes = 0,
            WakeWatcherArmed = false,
            ErrorMessage = errorMessage,
        });
        _ = ArmWakeWatcherAfterSilenceAsync(cancellation.Token);
    }

    private async Task ArmWakeWatcherAfterSilenceAsync(CancellationToken cancellationToken)
    {
        try
        {
            while (!cancellationToken.IsCancellationRequested && _waitingForWake && !_wakeWatcherArmed)
            {
                await Task.Delay(WakeArmPollInterval, cancellationToken).ConfigureAwait(false);
                if (!_wakeAdvertisementGate.TryArm(TimestampNanos())) continue;
                _wakeWatcherArmed = true;
                UpdateState(state => state with
                {
                    ConnectionState = TrikiConnectionState.WaitingForWake,
                    WakeWatcherArmed = true,
                    ErrorMessage = null,
                });
            }
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
        }
    }

    private void CancelWakeWatcher()
    {
        _waitingForWake = false;
        _wakeWatcherArmed = false;
        var cancellation = Interlocked.Exchange(ref _wakeArmCancellation, null);
        if (cancellation is null) return;
        cancellation.Cancel();
        cancellation.Dispose();
    }

    private void CleanupConnection()
    {
        if (_txCharacteristic is not null) _txCharacteristic.ValueChanged -= TxCharacteristicOnValueChanged;
        if (_device is not null) _device.ConnectionStatusChanged -= DeviceOnConnectionStatusChanged;
        _txCharacteristic = null;
        _rxCharacteristic = null;
        _ledCharacteristic = null;
        _nusService?.Dispose();
        _nusService = null;
        _device?.Dispose();
        _device = null;
    }

    private async Task RestartWatcherAsync()
    {
        try
        {
            await Task.Delay(TimeSpan.FromSeconds(2)).ConfigureAwait(false);
            if (_disposed) return;
            DisposeWatcher();
            await StartAsync().ConfigureAwait(false);
        }
        catch (Exception error)
        {
            if (_disposed) return;
            UpdateState(state => state with
            {
                ConnectionState = TrikiConnectionState.Error,
                ErrorMessage = $"Nie udało się ponownie uruchomić skanera Bluetooth: {error.Message}",
            });
        }
    }

    private void DisposeWatcher()
    {
        if (_watcher is null) return;
        _watcher.Received -= WatcherOnReceived;
        _watcher.Stopped -= WatcherOnStopped;
        if (_watcher.Status == BluetoothLEAdvertisementWatcherStatus.Started) _watcher.Stop();
        _watcher = null;
    }

    private void UpdateState(Func<BluetoothSnapshot, BluetoothSnapshot> update)
    {
        BluetoothSnapshot state;
        lock (_stateLock)
        {
            State = update(State);
            state = State;
        }
        StateChanged?.Invoke(this, state);
    }

    private void PublishSnapshot(BluetoothSnapshot state)
    {
        lock (_stateLock) State = state;
        StateChanged?.Invoke(this, state);
    }

    private static long TimestampNanos() => (long)(Stopwatch.GetTimestamp() * (1_000_000_000d / Stopwatch.Frequency));
    private void ThrowIfDisposed() => ObjectDisposedException.ThrowIf(_disposed, this);

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        CancelWakeWatcher();
        DisposeWatcher();
        CleanupConnection();
    }

    private static readonly TimeSpan WakeArmPollInterval = TimeSpan.FromMilliseconds(250);
}
