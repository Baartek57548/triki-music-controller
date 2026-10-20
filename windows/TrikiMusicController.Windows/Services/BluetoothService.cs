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
    private readonly object _decoderLock = new();
    private readonly CancellationTokenSource _lifetime = new();
    private readonly SemaphoreSlim _connectionGate = new(1, 1);
    private readonly TrikiProtocolDecoder _decoder = new();
    private readonly WakeAdvertisementGate _wakeAdvertisementGate = new();
    private readonly Dictionary<ulong, TrikiDeviceInfo> _discovered = [];
    private readonly Queue<long> _sampleTimestamps = new();
    private BluetoothLEAdvertisementWatcher? _watcher;
    private BluetoothLEDevice? _device;
    private GattSession? _gattSession;
    private GattDeviceService? _nusService;
    private GattCharacteristic? _txCharacteristic;
    private GattCharacteristic? _rxCharacteristic;
    private GattCharacteristic? _ledCharacteristic;
    private CancellationTokenSource? _wakeArmCancellation;
    private CancellationTokenSource? _connectionCancellation;
    private TaskCompletionSource<bool>? _firstFrame;
    private ulong? _knownAddress;
    private string? _knownName;
    private bool _autoReconnect = true;
    private volatile bool _connectOnlyWhenNeeded;
    private volatile MultiDeviceArbitrationMode _arbitrationMode = MultiDeviceArbitrationMode.MediaPriority;
    private Func<bool>? _isMediaPlayingProvider;
    private Func<DateTimeOffset?>? _lastPlaybackTimeProvider;
    private Func<bool>? _isUserActiveProvider;
    private volatile bool _waitingForWake;
    private volatile bool _wakeWatcherArmed;
    private bool _manualDisconnect;
    private bool _disposed;
    private long _lastAutoConnectAttemptNanos;
    private long _lastDiscoveryPublishNanos;
    private long _lastSampleReceivedNanos;
    private long _lastTelemetryPublishNanos;
    private const long AutoConnectCooldownNanos = 2_500_000_000;
    private const long DiscoveryPublishThrottleNanos = 400_000_000;
    private const long TelemetryPublishThrottleNanos = 250_000_000;

    public event EventHandler<BluetoothSnapshot>? StateChanged;
    public event EventHandler<TrikiSensorData>? SampleReceived;
    public BluetoothSnapshot State { get; private set; } = BluetoothSnapshot.Initial;

    public BluetoothService() { }

    internal BluetoothService(BluetoothSnapshot initialState) => State = initialState;

    public void SetMediaStateProviders(
        Func<bool> isMediaPlaying,
        Func<DateTimeOffset?> lastPlaybackTime,
        Func<bool> isUserActive)
    {
        _isMediaPlayingProvider = isMediaPlaying;
        _lastPlaybackTimeProvider = lastPlaybackTime;
        _isUserActiveProvider = isUserActive;
    }

    public void ConfigureRememberedDevice(
        ulong? address,
        string? name,
        bool autoReconnect,
        bool connectOnlyWhenNeeded,
        MultiDeviceArbitrationMode arbitrationMode = MultiDeviceArbitrationMode.MediaPriority)
    {
        var configurationChanged = _knownAddress != address ||
            _autoReconnect != autoReconnect ||
            _connectOnlyWhenNeeded != connectOnlyWhenNeeded ||
            _arbitrationMode != arbitrationMode;
        _knownAddress = address;
        _knownName = string.IsNullOrWhiteSpace(name) ? "Triki" : name;
        _autoReconnect = autoReconnect;
        _connectOnlyWhenNeeded = connectOnlyWhenNeeded;
        _arbitrationMode = arbitrationMode;
        if (!configurationChanged) return;
        _manualDisconnect = false;
        if (State.ConnectionState is TrikiConnectionState.Ready or TrikiConnectionState.Connecting)
        {
            CancelWakeWatcher();
            return;
        }
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
        if (State.ConnectionState is TrikiConnectionState.Ready or TrikiConnectionState.Connecting)
            return Task.CompletedTask;
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
            ThrowIfDisposed();
            if (!userInitiated && (_manualDisconnect || !_autoReconnect || _knownAddress != target.BluetoothAddress)) return;
            if (_device?.BluetoothAddress == target.BluetoothAddress && State.ConnectionState == TrikiConnectionState.Ready) return;
            _manualDisconnect = false;
            _lastAutoConnectAttemptNanos = TimestampNanos();
            CancelWakeWatcher();
            UpdateState(state => state with
            {
                ConnectionState = TrikiConnectionState.Connecting,
                ConnectedDevice = target,
                WakeWatcherArmed = false,
                ErrorMessage = null,
            });
            CleanupConnection();
            lock (_decoderLock) _decoder.Reset();
            lock (_stateLock) _sampleTimestamps.Clear();
            var cancellation = CancellationTokenSource.CreateLinkedTokenSource(_lifetime.Token);
            cancellation.CancelAfter(TimeSpan.FromSeconds(30));
            lock (_stateLock) _connectionCancellation = cancellation;
            var token = cancellation.Token;
            _firstFrame = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);

            _device = await BluetoothLEDevice.FromBluetoothAddressAsync(target.BluetoothAddress).AsTask(token);
            if (_device is null) throw new IOException("Windows nie może otworzyć urządzenia BLE. Wybudź Triki i spróbuj ponownie.");
            _device.ConnectionStatusChanged += DeviceOnConnectionStatusChanged;

            try
            {
                _gattSession = await GattSession.FromDeviceIdAsync(_device.BluetoothDeviceId).AsTask(token);
                if (_gattSession is not null)
                {
                    _gattSession.MaintainConnection = true;
                }
            }
            catch (Exception ex) when (ex is not OperationCanceledException)
            {
                Debug.WriteLine($"GattSession error: {ex.Message}");
            }

            var serviceResult = await _device.GetGattServicesForUuidAsync(TrikiProtocol.NusServiceUuid, BluetoothCacheMode.Uncached).AsTask(token);
            if (serviceResult.Status != GattCommunicationStatus.Success || serviceResult.Services.Count == 0)
                throw new IOException($"Triki nie udostępniło usługi Nordic UART ({serviceResult.Status}).");
            _nusService = serviceResult.Services[0];

            _txCharacteristic = await GetRequiredCharacteristicAsync(_nusService, TrikiProtocol.NusTxUuid, "NUS TX", token);
            _rxCharacteristic = await GetRequiredCharacteristicAsync(_nusService, TrikiProtocol.NusRxUuid, "NUS RX", token);
            _ledCharacteristic = await GetOptionalCharacteristicAsync(_nusService, TrikiProtocol.LedUuid, token);

            _txCharacteristic.ValueChanged += TxCharacteristicOnValueChanged;
            var notificationStatus = await _txCharacteristic.WriteClientCharacteristicConfigurationDescriptorAsync(
                GattClientCharacteristicConfigurationDescriptorValue.Notify).AsTask(token);
            if (notificationStatus != GattCommunicationStatus.Success)
                throw new IOException($"Nie udało się włączyć notyfikacji IMU ({notificationStatus}).");

            using var writer = new DataWriter();
            writer.WriteBytes(TrikiProtocol.StartStreamCommand);
            var writeResult = await _rxCharacteristic.WriteValueWithResultAsync(
                writer.DetachBuffer(),
                GattWriteOption.WriteWithoutResponse).AsTask(token);
            if (writeResult.Status != GattCommunicationStatus.Success)
                throw new IOException($"Triki odrzuciło komendę uruchomienia strumienia ({writeResult.Status}).");

            await _firstFrame.Task.WaitAsync(TimeSpan.FromSeconds(5), token).ConfigureAwait(false);
            token.ThrowIfCancellationRequested();
            cancellation.CancelAfter(Timeout.InfiniteTimeSpan);

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
            _ = WatchStreamAsync(_device, token);
            _ = RefreshBatteryAsync(_device);
        }
        catch (Exception error)
        {
            CleanupConnection();
            if (_disposed) return;
            var shouldWait = !_manualDisconnect && _autoReconnect && _knownAddress is not null;
            if (shouldWait && _connectOnlyWhenNeeded)
            {
                BeginWaitingForWake(error.Message);
            }
            else
            {
                UpdateState(state => state with
                {
                    ConnectionState = _manualDisconnect ? TrikiConnectionState.Disconnected :
                        shouldWait ? TrikiConnectionState.WaitingForDevice : TrikiConnectionState.Error,
                    ConnectedDevice = null,
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
        _manualDisconnect = true;
        CancelConnection();
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
            lock (_decoderLock) _decoder.Reset();
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
            if (_disposed || _manualDisconnect || !_connectOnlyWhenNeeded || !_autoReconnect ||
                _knownAddress is null || State.ConnectionState != TrikiConnectionState.Ready) return;
            _manualDisconnect = false;
            CleanupConnection();
            lock (_decoderLock) _decoder.Reset();
            lock (_stateLock) _sampleTimestamps.Clear();
            BeginWaitingForWake(waitForSilence: true);
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

        var now = TimestampNanos();
        var isNewDevice = false;
        lock (_stateLock)
        {
            isNewDevice = !_discovered.ContainsKey(device.BluetoothAddress);
            _discovered[device.BluetoothAddress] = device;
        }

        if (isNewDevice || now - _lastDiscoveryPublishNanos >= DiscoveryPublishThrottleNanos)
        {
            _lastDiscoveryPublishNanos = now;
            UpdateState(state => state with { DiscoveredDevices = SnapshotDiscoveredDevices() });
        }

        if (!knownMatch || !_autoReconnect || _manualDisconnect ||
            State.ConnectionState is TrikiConnectionState.Connecting or TrikiConnectionState.Ready) return;

        var isMediaPlaying = _isMediaPlayingProvider?.Invoke() ?? false;
        var isUserActive = _isUserActiveProvider?.Invoke() ?? false;
        var lastPlayback = _lastPlaybackTimeProvider?.Invoke();

        if (!MultiDeviceArbitrationPolicy.ShouldAttemptConnection(_arbitrationMode, isMediaPlaying, isUserActive))
            return;

        if (now - _lastAutoConnectAttemptNanos < AutoConnectCooldownNanos) return;

        if (_connectOnlyWhenNeeded && _waitingForWake)
        {
            if (!_wakeAdvertisementGate.ObserveAdvertisement(now)) return;
            CancelWakeWatcher();
        }

        _lastAutoConnectAttemptNanos = now;

        var delay = MultiDeviceArbitrationPolicy.CalculateConnectionDelay(
            _arbitrationMode, isMediaPlaying, lastPlayback, isUserActive, DateTimeOffset.UtcNow);

        if (delay <= TimeSpan.Zero)
        {
            _ = ConnectAsync(device, userInitiated: false);
        }
        else
        {
            _ = Task.Run(async () =>
            {
                await Task.Delay(delay).ConfigureAwait(false);
                if (_disposed || _manualDisconnect || !_autoReconnect) return;
                if (State.ConnectionState is TrikiConnectionState.Connecting or TrikiConnectionState.Ready) return;
                await ConnectAsync(device, userInitiated: false).ConfigureAwait(false);
            });
        }
    }

    public void NotifyMediaPlaybackStarted()
    {
        if (_disposed || _knownAddress is null || !_autoReconnect || State.ConnectionState is TrikiConnectionState.Ready or TrikiConnectionState.Connecting)
            return;

        _lastAutoConnectAttemptNanos = 0;
        if (State.ConnectionState == TrikiConnectionState.WaitingForWake)
        {
            CancelWakeWatcher();
            UpdateState(state => state with { ConnectionState = TrikiConnectionState.WaitingForDevice });
        }
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

    private async Task HandleUnexpectedDisconnectAsync(BluetoothLEDevice sender, bool streamStalled = false)
    {
        await Task.Delay(600).ConfigureAwait(false);
        if (_disposed || !ReferenceEquals(sender, _device) || _manualDisconnect) return;

        if (!streamStalled && (sender.ConnectionStatus == BluetoothConnectionStatus.Connected ||
            (TimestampNanos() - Interlocked.Read(ref _lastSampleReceivedNanos) < 1_500_000_000)))
        {
            return;
        }

        await _connectionGate.WaitAsync().ConfigureAwait(false);
        try
        {
            if (_disposed || !ReferenceEquals(sender, _device) || _manualDisconnect) return;
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
        if (_disposed || !ReferenceEquals(sender, _txCharacteristic)) return;
        try
        {
            var receivedAt = TimestampNanos();
            using var reader = DataReader.FromBuffer(args.CharacteristicValue);
            var bytes = new byte[reader.UnconsumedBufferLength];
            reader.ReadBytes(bytes);
            IReadOnlyList<TrikiSensorData> samples;
            DecoderStatistics statistics;
            lock (_decoderLock)
            {
                if (!ReferenceEquals(sender, _txCharacteristic)) return;
                samples = _decoder.Decode(bytes, receivedAt);
                statistics = _decoder.Statistics;
            }
            if (samples.Count > 0)
            {
                Interlocked.Exchange(ref _lastSampleReceivedNanos, receivedAt);
                _firstFrame?.TrySetResult(true);
            }
            foreach (var sample in samples)
            {
                lock (_stateLock)
                {
                    _sampleTimestamps.Enqueue(sample.TimestampNanos);
                    while (_sampleTimestamps.Count > 2 && sample.TimestampNanos - _sampleTimestamps.Peek() > SampleRateWindowNanos)
                        _sampleTimestamps.Dequeue();
                }
                if (State.ConnectionState == TrikiConnectionState.Ready && ReferenceEquals(sender, _txCharacteristic))
                    SampleReceived?.Invoke(this, sample);
            }
            var sampleRate = CalculateSampleRate();
            var now = receivedAt;
            bool shouldPublishTelemetry;
            BluetoothSnapshot snapshot;
            lock (_stateLock)
            {
                State = State with
                {
                    SampleRateHz = sampleRate,
                    DecodedFrames = statistics.DecodedFrames,
                    DiscardedStartupFrames = statistics.DiscardedStartupFrames,
                    DroppedProtocolBytes = statistics.DroppedBytes,
                };
                snapshot = State;
                shouldPublishTelemetry = now - _lastTelemetryPublishNanos >= TelemetryPublishThrottleNanos;
                if (shouldPublishTelemetry)
                {
                    _lastTelemetryPublishNanos = now;
                }
            }
            if (shouldPublishTelemetry)
            {
                StateChanged?.Invoke(this, snapshot);
            }
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

    private static async Task<GattCharacteristic> GetRequiredCharacteristicAsync(GattDeviceService service, Guid uuid, string label, CancellationToken token)
    {
        var result = await service.GetCharacteristicsForUuidAsync(uuid, BluetoothCacheMode.Uncached).AsTask(token);
        if (result.Status != GattCommunicationStatus.Success || result.Characteristics.Count == 0)
            throw new IOException($"Brak wymaganej charakterystyki {label} ({result.Status}).");
        return result.Characteristics[0];
    }

    private static async Task<GattCharacteristic?> GetOptionalCharacteristicAsync(GattDeviceService service, Guid uuid, CancellationToken token)
    {
        var result = await service.GetCharacteristicsForUuidAsync(uuid, BluetoothCacheMode.Uncached).AsTask(token);
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

    private void BeginWaitingForWake(string? errorMessage = null, bool waitForSilence = false)
    {
        if (_disposed || _knownAddress is null || !_autoReconnect || !_connectOnlyWhenNeeded) return;
        CancelWakeWatcher();
        _waitingForWake = true;
        // Startup and connection loss must accept an already advertising controller immediately.
        // Only deliberate Eco parking needs a short pause to avoid an instant disconnect/reconnect loop.
        _wakeAdvertisementGate.Reset(TimestampNanos(), waitForSilence);
        _wakeWatcherArmed = _wakeAdvertisementGate.IsArmed;
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
            WakeWatcherArmed = _wakeWatcherArmed,
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
        CancelConnection();
        if (_txCharacteristic is not null) _txCharacteristic.ValueChanged -= TxCharacteristicOnValueChanged;
        if (_device is not null) _device.ConnectionStatusChanged -= DeviceOnConnectionStatusChanged;
        _txCharacteristic = null;
        _rxCharacteristic = null;
        _ledCharacteristic = null;
        _gattSession?.Dispose();
        _gattSession = null;
        _nusService?.Dispose();
        _nusService = null;
        _device?.Dispose();
        _device = null;
    }

    private void CancelConnection()
    {
        lock (_stateLock)
        {
            _connectionCancellation?.Cancel();
            _connectionCancellation?.Dispose();
            _connectionCancellation = null;
        }
    }

    private async Task WatchStreamAsync(BluetoothLEDevice device, CancellationToken token)
    {
        try
        {
            while (!token.IsCancellationRequested)
            {
                await Task.Delay(TimeSpan.FromSeconds(1), token).ConfigureAwait(false);
                if (TimestampNanos() - Interlocked.Read(ref _lastSampleReceivedNanos) <= 3_000_000_000) continue;
                await HandleUnexpectedDisconnectAsync(device, streamStalled: true).ConfigureAwait(false);
                return;
            }
        }
        catch (OperationCanceledException) when (token.IsCancellationRequested) { }
        catch (Exception error)
        {
            if (!_disposed) Debug.WriteLine($"Nadzór strumienia BLE: {error}");
        }
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
        _lifetime.Cancel();
        CancelWakeWatcher();
        DisposeWatcher();
        CleanupConnection();
    }

    private static readonly TimeSpan WakeArmPollInterval = TimeSpan.FromMilliseconds(250);
}
